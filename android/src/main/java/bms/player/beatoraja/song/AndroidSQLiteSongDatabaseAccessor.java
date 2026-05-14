package bms.player.beatoraja.song;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import bms.model.BMSDecoder;
import bms.model.BMSModel;
import bms.player.beatoraja.song.SongData;
import bms.player.beatoraja.song.FolderData;
import bms.player.beatoraja.song.SongInformationAccessor;

/**
 * Android 原生 SQLite 实现的 SongDatabaseAccessor（初始版，主要支持读取接口）
 */
public class AndroidSQLiteSongDatabaseAccessor implements SongDatabaseAccessor {
    private static final String TAG = "AndroidSongDB";
    private final DBHelper helper;
    private final String[] bmsroot;
    // 多线程扫描：最少2个
    private static final int PARALLEL_THREAD_COUNT = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
    // 小于此数量则使用串行处理（避免线程创建开销）
    private static final int PARALLEL_THRESHOLD = 50;

    public AndroidSQLiteSongDatabaseAccessor(Context context, String dbPath, String[] bmsroot) {
        Log.i(TAG, "AndroidSQLiteSongDatabaseAccessor constructor, dbPath: " + dbPath);
        this.helper = new DBHelper(context, dbPath);
        this.bmsroot = bmsroot != null ? bmsroot : new String[0];
        // Force open database to trigger onCreate/table creation.
        // 不调用 close()，保持长连接（SQLiteOpenHelper 自身管理连接生命周期），
        // 避免频繁 open/close 引起的锁争用。
        SQLiteDatabase db = helper.getWritableDatabase();
        Log.i(TAG, "Database opened successfully: " + db.getPath());
    }

    /**
     * 获取兼容多版本 Android 的 Download 目录路径
     */
    private String getDownloadPath() {
        try {
            // Android 10 (API 29) 以下使用传统方式
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();
            } else {
                // Android 10+ 使用外部存储的公共 Download 目录
                // 由于我们已经请求了 MANAGE_EXTERNAL_STORAGE 权限，可以直接访问
                return "/storage/emulated/0/Download";
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get Download path, using fallback", e);
            return "/storage/emulated/0/Download";
        }
    }

    /**
     * 获取最匹配的 BMS 根目录路径，用于 CRC 计算。
     * 返回规范化的路径（无尾部斜杠），如果找不到匹配项则返回第一个根目录或空字符串。
     */
    private String findMatchingRoot(String path) {
        if (path == null || bmsroot == null || bmsroot.length == 0) return "";
        String normalizedPath = path.replace('\\', '/');
        String bestMatch = "";
        for (String root : bmsroot) {
            if (root == null) continue;
            String r = root.replace('\\', '/');
            if (r.endsWith("/")) r = r.substring(0, r.length() - 1);
            if (normalizedPath.startsWith(r) && r.length() > bestMatch.length()) {
                bestMatch = r;
            }
        }
        // 如果没有找到匹配的（比如路径在根目录之外），退而求其次返回第一个根目录以保证 CRC 工具能运行
        if (bestMatch.isEmpty() && bmsroot.length > 0) {
            bestMatch = bmsroot[0].replace('\\', '/');
            if (bestMatch.endsWith("/")) bestMatch = bestMatch.substring(0, bestMatch.length() - 1);
        }
        return bestMatch;
    }

    private String getBmsRootPath() {
        return findMatchingRoot("");
    }

    @Override
    public SongData[] getSongDatas(String key, String value) {
        SQLiteDatabase db = helper.getReadableDatabase();
        List<SongData> list = new ArrayList<>();
        Cursor c = null;
        try {
            // Use rawQuery with direct string instead of db.query() with parameters
            // This avoids SQLDroid issue where getParameterMetaData is not implemented
            String sql = "SELECT * FROM song WHERE " + key + " = '" + value.replace("'", "''") + "'";
            c = db.rawQuery(sql, null);
            while (c.moveToNext()) {
                SongData sd = cursorToSongData(c);
                list.add(sd);
            }
        } catch (Throwable t) {
            Log.e(TAG, "getSongDatas failed: " + t.getMessage(), t);
        } finally {
            if (c != null) c.close();
        }
        return list.toArray(new SongData[0]);
    }

    @Override
    public SongData[] getSongDatas(String[] hashes) {
        if (hashes == null || hashes.length == 0) return SongData.EMPTY;
        StringBuilder md5s = new StringBuilder();
        StringBuilder sha256s = new StringBuilder();
        for (String h : hashes) {
            if (h.length() > 32) {
                if (sha256s.length() > 0) sha256s.append(',');
                sha256s.append('\'').append(h).append('\'');
            } else {
                if (md5s.length() > 0) md5s.append(',');
                md5s.append('\'').append(h).append('\'');
            }
        }
        String sql = "SELECT * FROM song WHERE "
                + (md5s.length() > 0 ? "md5 IN (" + md5s.toString() + ")" : "1=0")
                + " OR "
                + (sha256s.length() > 0 ? "sha256 IN (" + sha256s.toString() + ")" : "1=0");
        SQLiteDatabase db = helper.getReadableDatabase();
        List<SongData> list = new ArrayList<>();
        Cursor c = null;
        try {
            c = db.rawQuery(sql, null);
            while (c.moveToNext()) {
                list.add(cursorToSongData(c));
            }
        } catch (Throwable t) {
            Log.w(TAG, "getSongDatas by hashes failed", t);
        } finally {
            if (c != null) c.close();
        }
        return list.toArray(new SongData[0]);
    }

    /**
     * 从外部 score.db 读取 sha256 → clear/combo/minbp 的内存映射。
     * 使用独立的 SQLiteDatabase.OPEN_READONLY 实例，不依赖 ATTACH DATABASE，
     * 彻底避免与 JDBC/SQLDroid 写连接的 SQLiteDatabaseLockedException 死锁。
     */
    private java.util.Map<String, int[]> readScoreMapFromFile(String scorePath) {
        java.util.Map<String, int[]> map = new java.util.HashMap<>();
        if (scorePath == null || scorePath.isEmpty()) return map;
        File f = new File(scorePath);
        if (!f.exists()) return map;
        SQLiteDatabase scoreDb = null;
        try {
            scoreDb = SQLiteDatabase.openDatabase(scorePath, null, SQLiteDatabase.OPEN_READONLY);
            // beatoraja score.db: 表名 "score"，列：sha256, clear, combo, minbp
            Cursor c = scoreDb.rawQuery("SELECT sha256, clear, combo, minbp FROM score", null);
            while (c.moveToNext()) {
                String sha = c.getString(0);
                int clear  = c.getInt(1);
                int combo  = c.getInt(2);
                int minbp  = c.getInt(3);
                // 只保留 clear 最高的记录
                int[] existing = map.get(sha);
                if (existing == null || clear > existing[0]) {
                    map.put(sha, new int[]{clear, combo, minbp});
                }
            }
            c.close();
        } catch (Throwable t) {
            Log.w(TAG, "readScoreMapFromFile failed: " + scorePath + " - " + t.getMessage());
        } finally {
            if (scoreDb != null) {
                try { scoreDb.close(); } catch (Throwable ignore) {}
            }
        }
        return map;
    }

    @Override
    public SongData[] getSongDatas(String sql, String score, String scorelog, String info) {
        // -----------------------------------------------------------------------
        // 方案：不使用 ATTACH DATABASE（会导致锁问题），
        // 而是解析 SQL，提取可能涉及的 score 表字段，
        // 先查询所有歌曲，再在 Java 层用 score 数据进行过滤
        // -----------------------------------------------------------------------
        SQLiteDatabase db = helper.getReadableDatabase();
        List<SongData> list = new ArrayList<>();

        try {
            // 先读取 score 数据到内存
            java.util.Map<String, ScoreData> scoreMap = new java.util.HashMap<>();
            if (score != null && new File(score).exists()) {
                scoreMap = readScoreDataFromFile(score);
                Log.i(TAG, "Loaded " + scoreMap.size() + " score records for filtering");
            }

            // 先尝试原样执行查询，看是否能工作
            String baseSelect = "SELECT DISTINCT md5, sha256, title, subtitle, genre, artist, subartist, "
                    + "path, folder, stagefile, banner, backbmp, parent, level, difficulty, "
                    + "maxbpm, minbpm, mode, judge, feature, content, date, favorite, notes, "
                    + "adddate, preview, length, charthash FROM song";

            Cursor c = null;
            boolean querySucceeded = false;

            try {
                c = db.rawQuery(baseSelect + " WHERE " + sql, null);
                while (c.moveToNext()) {
                    try {
                        SongData sd = cursorToSongData(c);
                        list.add(sd);
                    } catch (Throwable ignored) {}
                }
                c.close();
                querySucceeded = true;
                Log.i(TAG, "getSongDatas(sql) direct query succeeded: " + list.size() + " results");
            } catch (Throwable sqlErr) {
                Log.w(TAG, "getSongDatas(sql) WHERE clause failed: " + sqlErr.getMessage());
                try { if (c != null) c.close(); } catch (Throwable ignore) {}

                // 如果直接查询失败（可能因为引用了 score 表字段），
                // 则查询所有歌曲，然后在 Java 层根据 SQL 进行过滤
                list.clear();
                c = db.rawQuery(baseSelect, null);
                while (c.moveToNext()) {
                    try {
                        SongData sd = cursorToSongData(c);
                        list.add(sd);
                    } catch (Throwable ignored) {}
                }
                c.close();

                // 现在在 Java 层进行过滤
                if (scoreMap.size() > 0) {
                    list = filterSongDataList(list, scoreMap, sql);
                }
            }

            Log.i(TAG, "getSongDatas(sql) completed: " + list.size() + " results");

        } catch (Throwable t) {
            Log.w(TAG, "getSongDatas(sql) failed", t);
        }
        return list.toArray(new SongData[0]);
    }

    /**
     * 从 score.db 读取完整的分数数据
     */
    private java.util.Map<String, ScoreData> readScoreDataFromFile(String scorePath) {
        java.util.Map<String, ScoreData> map = new java.util.HashMap<>();
        if (scorePath == null || scorePath.isEmpty()) return map;
        File f = new File(scorePath);
        if (!f.exists()) return map;

        SQLiteDatabase scoreDb = null;
        try {
            scoreDb = SQLiteDatabase.openDatabase(scorePath, null, SQLiteDatabase.OPEN_READONLY);

            // Detect available columns by querying the table schema
            java.util.Set<String> columns = new java.util.HashSet<>();
            Cursor cols = scoreDb.rawQuery("PRAGMA table_info(score)", null);
            while (cols.moveToNext()) {
                columns.add(cols.getString(cols.getColumnIndex("name")));
            }
            cols.close();

            if (columns.isEmpty()) {
                Log.w(TAG, "score table has no columns in: " + scorePath);
                return map;
            }

            // Build query dynamically based on available columns.
            // For missing columns, use "0 AS colname" so SQLite always returns a valid column.
            StringBuilder sql = new StringBuilder("SELECT ");
            // sha256, playcount, clear - always include as-is (required)
            sql.append("sha256, playcount, clear, ");
            // exscore: try exscore, then score, then 0
            if (columns.contains("exscore")) {
                sql.append("exscore, ");
            } else if (columns.contains("score")) {
                sql.append("score AS exscore, ");
            } else {
                sql.append("0 AS exscore, ");
            }
            // All other columns: include if present, otherwise 0
            sql.append(columns.contains("maxcombo") ? "maxcombo, " : "0 AS maxcombo, ");
            sql.append(columns.contains("minbp") ? "minbp, " : "0 AS minbp, ");
            sql.append(columns.contains("perfect") ? "perfect, " : "0 AS perfect, ");
            sql.append(columns.contains("great") ? "great, " : "0 AS great, ");
            sql.append(columns.contains("good") ? "good, " : "0 AS good, ");
            sql.append(columns.contains("bad") ? "bad, " : "0 AS bad, ");
            sql.append(columns.contains("poor") ? "poor, " : "0 AS poor, ");
            sql.append(columns.contains("totalnotes") ? "totalnotes, " : "0 AS totalnotes, ");
            sql.append(columns.contains("fast") ? "fast, " : "0 AS fast, ");
            sql.append(columns.contains("slow") ? "slow, " : "0 AS slow, ");
            sql.append(columns.contains("date") ? "date FROM score" : "0 AS date FROM score");

            Cursor c = scoreDb.rawQuery(sql.toString(), null);
            while (c.moveToNext()) {
                ScoreData sd = new ScoreData();
                sd.sha256 = getStringSafe(c, "sha256");
                sd.playcount = getIntSafe(c, "playcount");
                sd.clear = getIntSafe(c, "clear");
                sd.score = getIntSafe(c, "score");
                sd.exscore = getIntSafe(c, "exscore");
                sd.maxcombo = getIntSafe(c, "maxcombo");
                sd.minbp = getIntSafe(c, "minbp");
                sd.perfect = getIntSafe(c, "perfect");
                sd.great = getIntSafe(c, "great");
                sd.good = getIntSafe(c, "good");
                sd.bad = getIntSafe(c, "bad");
                sd.poor = getIntSafe(c, "poor");
                sd.totalnotes = getIntSafe(c, "totalnotes");
                sd.fast = getIntSafe(c, "fast");
                sd.slow = getIntSafe(c, "slow");
                sd.date = getIntSafe(c, "date");
                map.put(sd.sha256, sd);
            }
            c.close();
        } catch (Throwable t) {
            Log.w(TAG, "readScoreDataFromFile failed: " + scorePath + " - " + t.getMessage());
        } finally {
            if (scoreDb != null) {
                try { scoreDb.close(); } catch (Throwable ignore) {}
            }
        }
        return map;
    }

    /**
     * 在 Java 层根据 SQL 过滤歌曲列表（只处理常见情况）
     */
    private List<SongData> filterSongDataList(List<SongData> songs, java.util.Map<String, ScoreData> scoreMap, String sql) {
        List<SongData> filtered = new ArrayList<>();

        // 处理常见情况：playcount > 0
        String lowerSql = sql.toLowerCase();
        boolean hasPlaycountCondition = lowerSql.contains("playcount");

        for (SongData song : songs) {
            boolean include = true;

            if (hasPlaycountCondition) {
                ScoreData sd = scoreMap.get(song.getSha256());
                int playcount = (sd != null) ? sd.playcount : 0;

                // 简单处理常见模式
                if (lowerSql.contains("playcount > 0")) {
                    include = playcount > 0;
                } else if (lowerSql.contains("playcount >= 0")) {
                    include = playcount >= 0;
                } else if (lowerSql.contains("playcount = 0")) {
                    include = playcount == 0;
                }
                // 对于更复杂的情况，我们需要解析 SQL，但这里做简化处理
            }

            // 处理 ORDER BY playcount DESC LIMIT 10 等（在最后处理）

            if (include) {
                filtered.add(song);
            }
        }

        // 处理排序和限制
        if (lowerSql.contains("order by playcount")) {
            final boolean desc = lowerSql.contains("desc");
            Collections.sort(filtered, new java.util.Comparator<SongData>() {
                @Override
                public int compare(SongData a, SongData b) {
                    ScoreData sa = scoreMap.get(a.getSha256());
                    ScoreData sb = scoreMap.get(b.getSha256());
                    int pa = (sa != null) ? sa.playcount : 0;
                    int pb = (sb != null) ? sb.playcount : 0;
                    return desc ? Integer.compare(pb, pa) : Integer.compare(pa, pb);
                }
            });
        }

        // 处理 LIMIT
        if (lowerSql.contains("limit")) {
            try {
                int limitIdx = lowerSql.indexOf("limit");
                String limitStr = sql.substring(limitIdx + 5).trim();
                // 只取数字部分
                StringBuilder numStr = new StringBuilder();
                for (int i = 0; i < limitStr.length(); i++) {
                    char ch = limitStr.charAt(i);
                    if (Character.isDigit(ch)) {
                        numStr.append(ch);
                    } else {
                        break;
                    }
                }
                if (numStr.length() > 0) {
                    int limit = Integer.parseInt(numStr.toString());
                    if (limit > 0 && filtered.size() > limit) {
                        filtered = filtered.subList(0, limit);
                    }
                }
            } catch (Throwable ignore) {}
        }

        return filtered;
    }

    /**
     * 简单的分数数据容器
     */
    private static class ScoreData {
        String sha256;
        int playcount;
        int clear;
        int score;
        int exscore;
        int maxcombo;
        int minbp;
        int perfect;
        int great;
        int good;
        int bad;
        int poor;
        int totalnotes;
        int fast;
        int slow;
        int date;
    }

    @Override
    public void setSongDatas(SongData[] songs) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            for (SongData sd : songs) {
                ContentValues cv = new ContentValues();
                cv.put("md5", sd.getMd5());
                cv.put("sha256", sd.getSha256());
                cv.put("title", sd.getTitle());
                cv.put("subtitle", sd.getSubtitle());
                cv.put("genre", sd.getGenre());
                cv.put("artist", sd.getArtist());
                cv.put("subartist", sd.getSubartist());
                cv.put("tag", sd.getTag());
                cv.put("path", sd.getPath());
                cv.put("folder", sd.getFolder());
                cv.put("stagefile", sd.getStagefile());
                cv.put("banner", sd.getBanner());
                cv.put("backbmp", sd.getBackbmp());
                cv.put("preview", sd.getPreview());
                cv.put("parent", sd.getParent());
                cv.put("level", sd.getLevel());
                cv.put("difficulty", sd.getDifficulty());
                cv.put("maxbpm", sd.getMaxbpm());
                cv.put("minbpm", sd.getMinbpm());
                cv.put("length", sd.getLength());
                cv.put("mode", sd.getMode());
                cv.put("judge", sd.getJudge());
                cv.put("feature", sd.getFeature());
                cv.put("content", sd.getContent());
                cv.put("date", sd.getDate());
                cv.put("favorite", sd.getFavorite());
                cv.put("adddate", sd.getAdddate());
                cv.put("notes", sd.getNotes());
                cv.put("charthash", sd.getCharthash());
                db.insertWithOnConflict("song", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } catch (Throwable t) {
            Log.w(TAG, "setSongDatas failed", t);
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public SongData[] getSongDatasByText(String text) {
        SQLiteDatabase db = helper.getReadableDatabase();
        List<SongData> list = new ArrayList<>();
        Cursor c = null;
        String sql = "SELECT * FROM song WHERE title LIKE ? OR artist LIKE ? OR genre LIKE ?";
        try {
            c = db.rawQuery(sql, new String[]{"%" + text + "%", "%" + text + "%", "%" + text + "%"});
            while (c.moveToNext()) list.add(cursorToSongData(c));
        } catch (Throwable t) {
            Log.w(TAG, "getSongDatasByText failed", t);
        } finally { if (c != null) c.close(); }
        return list.toArray(new SongData[0]);
    }

    @Override
    public FolderData[] getFolderDatas(String key, String value) {
        SQLiteDatabase db = helper.getReadableDatabase();
        List<FolderData> list = new ArrayList<>();
        Cursor c = null;
        try {
            // Use rawQuery with direct string instead of db.query() with parameters
            // This avoids SQLDroid issue where getParameterMetaData is not implemented
            String sql = "SELECT * FROM folder WHERE " + key + " = '" + value.replace("'", "''") + "'";
            c = db.rawQuery(sql, null);
            while (c.moveToNext()) {
                FolderData fd = new FolderData();
                fd.setTitle(getStringSafe(c, "title"));
                fd.setPath(getStringSafe(c, "path"));
                fd.setDate(getIntSafe(c, "date"));
                list.add(fd);
            }
        } catch (Throwable t) {
            Log.e(TAG, "getFolderDatas failed: " + t.getMessage(), t);
        } finally { if (c != null) c.close(); }
        return list.toArray(new FolderData[0]);
    }

    @Override
    public String[] getBmsRoot() {
        return bmsroot;
    }

    @Override
    public void updateSongDatas(String updatepath, String[] bmsroot, boolean updateAll, SongInformationAccessor info) {
        if (updatepath != null) {
            updateSongDatas(new String[]{updatepath}, updateAll);
        } else {
            updateSongDatas(bmsroot, updateAll);
        }
    }

    /**
     * Update song database by scanning the given paths recursively using LibGDX Gdx.files.absolute() API.
     * Uses LibGDX FileHandle API to bypass standard Android IO limitations and ensure cross-platform compatibility.
     * Implements incremental sync, deletion detection, and force refresh functionality.
     *
     * @param paths Array of root paths to scan for BMS files
     * @param forceRefresh Whether to force a full re-scan (ignores existing records and timestamps)
     */
    public void updateSongDatas(String[] paths, boolean forceRefresh) {
        long startTime = System.currentTimeMillis();
        final AtomicInteger fileCount = new AtomicInteger(0);
        BMSDecoder decoder = new BMSDecoder(BMSModel.LNTYPE_LONGNOTE);
        SQLiteDatabase db = helper.getWritableDatabase();

        // 检查数据库中已有多少歌曲记录，用于统计
        Cursor songCountCursor = db.rawQuery("SELECT COUNT(*) FROM song", null);
        int existingSongCount = 0;
        if (songCountCursor.moveToFirst()) {
            existingSongCount = songCountCursor.getInt(0);
        }
        songCountCursor.close();
        Log.i(TAG, "updateSongDatas: Existing songs in DB: " + existingSongCount + ", forceRefresh: " + forceRefresh);

        // Force create all tables if they don't exist BEFORE scanning
        // This guarantees tables exist regardless of whether onCreate was called
        Log.i(TAG, "updateSongDatas: Force-creating all core tables if not exists...");
        db.execSQL("CREATE TABLE IF NOT EXISTS song ("
                + "md5 TEXT, "
                + "sha256 TEXT, "
                + "title TEXT, "
                + "subtitle TEXT, "
                + "genre TEXT, "
                + "artist TEXT, "
                + "subartist TEXT, "
                + "tag TEXT, "
                + "path TEXT, "
                + "folder TEXT, "
                + "stagefile TEXT, "
                + "banner TEXT, "
                + "backbmp TEXT, "
                + "preview TEXT, "
                + "parent TEXT, "
                + "level INTEGER, "
                + "difficulty INTEGER, "
                + "maxbpm INTEGER, "
                + "minbpm INTEGER, "
                + "length INTEGER, "
                + "mode INTEGER, "
                + "judge INTEGER, "
                + "feature INTEGER, "
                + "content INTEGER, "
                + "date INTEGER, "
                + "favorite INTEGER, "
                + "adddate INTEGER, "
                + "notes INTEGER, "
                + "playcount INTEGER, "
                + "charthash TEXT"
                + ", PRIMARY KEY (sha256))");
        System.out.println("Table Created: song (updateSongDatas force check)");
        Log.i(TAG, "Table Created: song (updateSongDatas force check)");

        db.execSQL("CREATE TABLE IF NOT EXISTS folder ("
                + "title TEXT, "
                + "subtitle TEXT, "
                + "command TEXT, "
                + "path TEXT, "
                + "banner TEXT, "
                + "parent TEXT, "
                + "type INTEGER, "
                + "date INTEGER, "
                + "adddate INTEGER, "
                + "max INTEGER"
                + ", PRIMARY KEY (path))");
        System.out.println("Table Created: folder (updateSongDatas force check)");
        Log.i(TAG, "Table Created: folder (updateSongDatas force check)");

        Log.i(TAG, "updateSongDatas: All tables verified. Starting scan with " + paths.length + " root paths");
        Log.i(TAG, "Database file path: " + db.getPath());
        Log.i(TAG, "Database file exists: " + new java.io.File(db.getPath()).exists());

        // 确保默认的 Download/beatoraja/songs 目录存在
        String defaultRoot = getDownloadPath();
        String defaultPath = defaultRoot + "/beatoraja/songs";
        FileHandle defaultDir = Gdx.files.absolute(defaultPath);
        if (!defaultDir.exists()) {
            Log.i(TAG, "Creating default BMS directory: " + defaultPath);
            defaultDir.mkdirs();
            if (!defaultDir.exists()) {
                Log.w(TAG, "LibGDX mkdirs failed, trying Android native API...");
                java.io.File file = new java.io.File(defaultPath);
                file.mkdirs();
            }
        }

        // 创建 .nomedia 文件，防止系统媒体扫描器扫描 BMS 文件夹
        File nomediaFile = new java.io.File(defaultPath, ".nomedia");
        if (!nomediaFile.exists()) {
            try {
                boolean created = nomediaFile.createNewFile();
                Log.i(TAG, "Created .nomedia file: " + created + " - " + nomediaFile.getAbsolutePath());
            } catch (java.io.IOException e) {
                Log.e(TAG, "Failed to create .nomedia file", e);
            }
        }

        db.beginTransaction();

        try {
            int deleteCount = 0;
            // 策略优化：删除同步只在 forceRefresh 时执行，避免每次都扫描所有文件
            if (forceRefresh) {
                // Step 1: Deletion Sync - Detect and remove files that no longer exist on disk
                Log.i(TAG, "Deletion Sync: Querying all existing file paths from song table...");
            List<String> existingPaths = new ArrayList<>();
            Cursor cursor = db.rawQuery("SELECT path FROM song", null);
            while (cursor.moveToNext()) {
                existingPaths.add(getStringSafe(cursor, "path"));
            }
            cursor.close();
            Log.i(TAG, "Deletion Sync: Found " + existingPaths.size() + " existing paths in database");

            for (String path : existingPaths) {
                FileHandle file = Gdx.files.absolute(path);
                if (!file.exists()) {
                    Log.w(TAG, "Deletion Sync: File not found on disk, deleting from database: " + path);
                    db.delete("song", "path = ?", new String[]{path});
                    deleteCount++;
                }
            }
            Log.i(TAG, "Deletion Sync: Deleted " + deleteCount + " records");
            } else {
                Log.i(TAG, "Deletion Sync: Skipped (forceRefresh is false) - maintaining existing records");
            }

            // Step 2: forceRefresh 为 true 时使用多线程并行扫描（首次全量扫描场景）
            if (forceRefresh) {
                Log.i(TAG, "Step 2: Using parallel multi-threaded scan (forceRefresh=true)");
                updateSongDatasParallel(paths, forceRefresh);
                return;
            }

            // Step 2 (增量模式): 扫描所有传入的路径
            Log.i(TAG, "================================================================================");
            Log.i(TAG, "Step 2: Starting folder scan with " + paths.length + " root path(s)");
            Log.i(TAG, "================================================================================");

            for (String pathToScan : paths) {
                if (pathToScan == null || pathToScan.trim().isEmpty()) {
                    Log.w(TAG, "Skipping null or empty path");
                    continue;
                }

                // 【诊断点 1】打印真实路径并检查目录状态
                String trimmedPath = pathToScan.trim();
                FileHandle scanDir = Gdx.files.absolute(trimmedPath);

                Log.i(TAG, "================================================================================");
                Log.i(TAG, "[ScannerDebug] Preparing to scan directory:");
                Log.i(TAG, "[ScannerDebug]   Absolute path: " + scanDir.path());
                Log.i(TAG, "[ScannerDebug]   exists: " + scanDir.exists());

                // 额外检查：尝试获取底层 File 对象进行更详细的检查
                try {
                    java.io.File realFile = scanDir.file();
                    Log.i(TAG, "[ScannerDebug]   File.exists(): " + realFile.exists());
                    Log.i(TAG, "[ScannerDebug]   File.isDirectory(): " + realFile.isDirectory());
                    Log.i(TAG, "[ScannerDebug]   File.canRead(): " + realFile.canRead());
                    Log.i(TAG, "[ScannerDebug]   File.canExecute(): " + realFile.canExecute());
                    if (realFile.exists() && realFile.isDirectory()) {
                        java.io.File[] testList = realFile.listFiles();
                        Log.i(TAG, "[ScannerDebug]   listFiles() returned: " + (testList == null ? "NULL" : testList.length + " items"));
                    }
                } catch (Exception e) {
                    Log.e(TAG, "[ScannerDebug]   Failed to get underlying File object: " + e.getMessage());
                }

                Log.i(TAG, "[ScannerDebug]   forceRefresh: " + forceRefresh);
                Log.i(TAG, "================================================================================");

                if (scanDir.exists()) {
                    Log.i(TAG, "Starting recursive scan of: " + scanDir.path());
                    long folderScanStart = System.currentTimeMillis();
                    scanFolderRecursively(scanDir, decoder, db, fileCount, forceRefresh);
                    long folderScanElapsed = System.currentTimeMillis() - folderScanStart;
                    Log.i(TAG, "Recursive scan completed for: " + scanDir.path() + " in " + folderScanElapsed + "ms");
                } else {
                    Log.e(TAG, "================================================================================");
                    Log.e(TAG, "[ERROR] BMS directory does not exist or cannot be accessed: " + scanDir.path());
                    Log.e(TAG, "[ERROR] This is likely an Android Scoped Storage permission issue!");
                    Log.e(TAG, "[ERROR] Please verify:");
                    Log.e(TAG, "[ERROR]   1. MANAGE_EXTERNAL_STORAGE permission is granted");
                    Log.e(TAG, "[ERROR]   2. Path string is correct (no typos)");
                    Log.e(TAG, "[ERROR]   3. Directory actually exists on disk");
                    Log.e(TAG, "================================================================================");
                }
            }

            Log.i(TAG, "================================================================================");
            Log.i(TAG, "All paths scanned. Transaction committing...");
            Log.i(TAG, "================================================================================");

            db.setTransactionSuccessful();
            long endTime = System.currentTimeMillis();

            // 统计扫描后的数量
            Cursor newSongCountCursor = db.rawQuery("SELECT COUNT(*) FROM song", null);
            int newSongCount = 0;
            if (newSongCountCursor.moveToFirst()) {
                newSongCount = newSongCountCursor.getInt(0);
            }
            newSongCountCursor.close();

            Log.i(TAG, "updateSongDatas completed: " + fileCount.get() + " files processed/updated, "
                    + deleteCount + " files deleted, "
                    + (existingSongCount - fileCount.get() - deleteCount) + " files skipped (unmodified), "
                    + "total songs now: " + newSongCount
                    + " in " + (endTime - startTime) + "ms");
        } catch (Throwable t) {
            Log.e(TAG, "================================================================================");
            Log.e(TAG, "updateSongDatas failed with fatal error", t);
            Log.e(TAG, "================================================================================");
        } finally {
            db.endTransaction();
            // 强制 checkpoint 将 WAL 写入主数据库文件，确保后续读取可见
            try {
                db.execSQL("PRAGMA wal_checkpoint(FULL)");
            } catch (Throwable e) {
                Log.w(TAG, "wal_checkpoint failed: " + e.getMessage());
            }
        }
    }

    /**
     * Overloaded method for backward compatibility (default to non-force refresh)
     *
     * @param paths Array of root paths to scan for BMS files
     */
    public void updateSongDatas(String[] paths) {
        updateSongDatas(paths, false);
    }

    /**
     * 多线程版本：收集所有BMS文件路径，然后并行解码，最后批量写入数据库
     * 适用于大量文件的场景
     */
    private void updateSongDatasParallel(String[] paths, boolean forceRefresh) {
        long startTime = System.currentTimeMillis();
        Log.i(TAG, "updateSongDatasParallel: Starting with " + paths.length + " root paths, forceRefresh=" + forceRefresh);

        // 阶段1: 收集所有BMS文件路径（单线程遍历）
        long collectStart = System.currentTimeMillis();
        List<FileHandle> allFiles = new ArrayList<>();
        BMSDecoder decoder = new BMSDecoder(BMSModel.LNTYPE_LONGNOTE);

        for (String pathToScan : paths) {
            if (pathToScan == null || pathToScan.trim().isEmpty()) continue;
            FileHandle scanDir = Gdx.files.absolute(pathToScan.trim());
            if (scanDir.exists()) {
                collectBmsFiles(scanDir, allFiles);
            }
        }
        long collectElapsed = System.currentTimeMillis() - collectStart;
        Log.i(TAG, "updateSongDatasParallel: Phase1 collected " + allFiles.size() + " BMS files in " + collectElapsed + "ms");

        if (allFiles.isEmpty()) {
            Log.i(TAG, "updateSongDatasParallel: No BMS files found, skipping");
            return;
        }

        // 阶段2: 并行解码（多线程）
        long decodeStart = System.currentTimeMillis();
        ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_THREAD_COUNT);
        List<Future<DecodeResult>> futures = new ArrayList<>();

        for (final FileHandle file : allFiles) {
            futures.add(executor.submit(() -> processBmsFileParallel(file, decoder, forceRefresh)));
        }

        List<DecodeResult> results = new ArrayList<>();
        int successCount = 0;
        for (Future<DecodeResult> f : futures) {
            try {
                DecodeResult result = f.get(30, TimeUnit.SECONDS);
                if (result != null && result.songData != null) {
                    results.add(result);
                    successCount++;
                }
            } catch (Exception e) {
                Log.w(TAG, "updateSongDatasParallel: Decode failed", e);
            }
        }

        executor.shutdown();
        try {
            executor.awaitTermination(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Log.e(TAG, "updateSongDatasParallel: Executor interrupted", e);
        }
        long decodeElapsed = System.currentTimeMillis() - decodeStart;
        Log.i(TAG, "updateSongDatasParallel: Phase2 decoded " + successCount + "/" + allFiles.size() + " files in " + decodeElapsed + "ms with " + PARALLEL_THREAD_COUNT + " threads");

        if (results.isEmpty()) {
            Log.i(TAG, "updateSongDatasParallel: No valid BMS files after decoding");
            return;
        }

        // 阶段3: 批量写入数据库（单线程事务）
        long writeStart = System.currentTimeMillis();
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            for (DecodeResult result : results) {
                insertSongData(result.songData, db);
            }
            db.setTransactionSuccessful();
        } catch (Throwable t) {
            Log.e(TAG, "updateSongDatasParallel: Batch insert failed", t);
        } finally {
            db.endTransaction();
            // 强制 checkpoint 将 WAL 写入主数据库文件，确保后续读取可见
            try {
                db.execSQL("PRAGMA wal_checkpoint(FULL)");
            } catch (Throwable e) {
                Log.w(TAG, "wal_checkpoint failed: " + e.getMessage());
            }
        }
        long writeElapsed = System.currentTimeMillis() - writeStart;
        long totalElapsed = System.currentTimeMillis() - startTime;
        Log.i(TAG, "updateSongDatasParallel: Phase3 wrote " + results.size() + " songs in " + writeElapsed + "ms, total: " + totalElapsed + "ms");
    }

    /**
     * 收集目录下所有BMS文件路径（递归）
     */
    private void collectBmsFiles(FileHandle folder, List<FileHandle> result) {
        try {
            if (!folder.exists() || !folder.isDirectory()) return;

            FileHandle[] children = folder.list();
            if (children == null) return;

            for (FileHandle child : children) {
                if (child.isDirectory()) {
                    collectBmsFiles(child, result);
                } else {
                    String name = child.name().toLowerCase();
                    if (isBmsFile(name)) {
                        result.add(child);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "collectBmsFiles: Failed to scan " + folder.path(), e);
        }
    }

    /**
     * 并行解码时的结果包装类
     */
    private static class DecodeResult {
        SongData songData;
        FileHandle file;
        int lastModified;

        DecodeResult(SongData songData, FileHandle file, int lastModified) {
            this.songData = songData;
            this.file = file;
            this.lastModified = lastModified;
        }
    }

    /**
     * 并行解码单个BMS文件（不写库，只返回SongData）
     */
    private DecodeResult processBmsFileParallel(FileHandle file, BMSDecoder decoder, boolean forceRefresh) {
        try {
            if (file == null || !file.exists()) return null;

            String pathName = file.path().replace('\\', '/');
            int lastModifiedTime = (int) (file.lastModified() / 1000);

            // 增量更新检查
            if (!forceRefresh) {
                SQLiteDatabase db = helper.getReadableDatabase();
                Cursor cursor = db.rawQuery("SELECT date FROM song WHERE path = ?", new String[]{pathName});
                if (cursor.moveToFirst()) {
                    int recordDate = cursor.getInt(0);
                    if (recordDate == lastModifiedTime) {
                        cursor.close();
                        return null; // 未修改，跳过
                    }
                }
                cursor.close();
            }

            BMSModel model = decoder.decode(file);
            if (model == null) return null;

            SongData songData = new SongData(model, false);
            songData.setPath(pathName);
            songData.setDate(lastModifiedTime);
            songData.setAdddate((int) (System.currentTimeMillis() / 1000));

            // CRC计算
            String matchingRoot = findMatchingRoot(pathName);
            if (file.parent() != null) {
                String parentPath = file.parent().path().replace('\\', '/');
                songData.setFolder(bms.player.beatoraja.song.SongUtils.crc32(parentPath, bmsroot, matchingRoot));
                if (file.parent().parent() != null) {
                    String grandParentPath = file.parent().parent().path().replace('\\', '/');
                    songData.setParent(bms.player.beatoraja.song.SongUtils.crc32(grandParentPath, bmsroot, matchingRoot));
                }
            }

            return new DecodeResult(songData, file, lastModifiedTime);
        } catch (Exception e) {
            Log.w(TAG, "processBmsFileParallel: Failed " + (file != null ? file.path() : "null"), e);
            return null;
        }
    }

    /**
     * 将SongData插入数据库
     */
    private void insertSongData(SongData songData, SQLiteDatabase db) {
        ContentValues cv = new ContentValues();
        cv.put("md5", songData.getMd5());
        cv.put("sha256", songData.getSha256());
        cv.put("title", songData.getTitle());
        cv.put("subtitle", songData.getSubtitle());
        cv.put("genre", songData.getGenre());
        cv.put("artist", songData.getArtist());
        cv.put("subartist", songData.getSubartist());
        cv.put("tag", songData.getTag());
        cv.put("path", songData.getPath());
        cv.put("folder", songData.getFolder());
        cv.put("stagefile", songData.getStagefile());
        cv.put("banner", songData.getBanner());
        cv.put("backbmp", songData.getBackbmp());
        cv.put("preview", songData.getPreview());
        cv.put("parent", songData.getParent());
        cv.put("level", songData.getLevel());
        cv.put("difficulty", songData.getDifficulty());
        cv.put("maxbpm", songData.getMaxbpm());
        cv.put("minbpm", songData.getMinbpm());
        cv.put("length", songData.getLength());
        cv.put("mode", songData.getMode());
        cv.put("judge", songData.getJudge());
        cv.put("feature", songData.getFeature());
        cv.put("content", songData.getContent());
        cv.put("date", songData.getDate());
        cv.put("favorite", songData.getFavorite());
        cv.put("adddate", songData.getAdddate());
        cv.put("notes", songData.getNotes());
        cv.put("charthash", songData.getCharthash());
        db.insertWithOnConflict("song", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /**
     * Recursively scan a folder for BMS files using LibGDX FileHandle API.
     * Also inserts folder information into folder table for UI navigation when BMS files are found.
     *
     * @param folder The folder to scan
     * @param decoder BMS decoder instance
     * @param db Database connection
     * @param fileCount Counter for processed files
     * @param forceRefresh Whether to force a full re-scan (ignores existing records and timestamps)
     * @return true if this folder or any subfolder contains at least one BMS file
     */
    private boolean scanFolderRecursively(FileHandle folder, BMSDecoder decoder, SQLiteDatabase db, AtomicInteger fileCount, boolean forceRefresh) {
        // 【诊断点 2】拦截文件遍历的静默异常
        try {
            if (!folder.exists()) {
                Log.w(TAG, "[ScanFolder] Folder does not exist: " + folder.path());
                return false;
            }

            // 尝试列出目录内容
            FileHandle[] children = null;
            try {
                children = folder.list();
                Log.d(TAG, "[ScanFolder] list() returned for: " + folder.path() + " -> " + (children == null ? "NULL" : children.length + " items"));
            } catch (Exception listException) {
                // 【关键修复】捕获 list() 的异常，这在 Android 上处理外置存储时经常发生
                Log.e(TAG, "================================================================================");
                Log.e(TAG, "[ScannerError] Failed to list directory: " + folder.path());
                Log.e(TAG, "[ScannerError] Exception type: " + listException.getClass().getName());
                Log.e(TAG, "[ScannerError] Message: " + listException.getMessage());
                Log.e(TAG, "[ScannerError] Full stack trace:");
                Log.e(TAG, "================================================================================", listException);
                return false;
            }

            if (children == null) {
                Log.w(TAG, "[ScanFolder] list() returned NULL (directory may be empty or inaccessible): " + folder.path());
                return false;
            }

            boolean containsBms = false;

            // First process BMS files in this folder
            for (FileHandle child : children) {
                if (!child.isDirectory()) {
                    String fileName = child.name().toLowerCase();
                    if (isBmsFile(fileName)) {
                        processBmsFile(child, decoder, db, fileCount, forceRefresh);
                        containsBms = true;
                    }
                }
            }

            // Recurse into subfolders - if any subfolder contains BMS, mark this folder as containing BMS
            for (FileHandle child : children) {
                if (child.isDirectory()) {
                    boolean subfolderContainsBms = scanFolderRecursively(child, decoder, db, fileCount, forceRefresh);
                    if (subfolderContainsBms) {
                        containsBms = true;
                    }
                }
            }

            // Always insert this folder into folder table for UI navigation,
            // not just when it contains BMS files directly.
            // This ensures the root folder has correct CRC for parent lookup.
            insertFolder(folder, db);

            return containsBms;
        } catch (Exception e) {
            // 【关键修复】捕获所有未预期的异常，防止扫描过程静默失败
            Log.e(TAG, "================================================================================");
            Log.e(TAG, "[ScannerError] Unexpected error during folder scan: " + folder.path());
            Log.e(TAG, "[ScannerError] Exception type: " + e.getClass().getName());
            Log.e(TAG, "[ScannerError] Message: " + e.getMessage());
            Log.e(TAG, "[ScannerError] Full stack trace:");
            Log.e(TAG, "================================================================================", e);
            return false;
        }
    }

    /**
     * Insert a folder into the folder table for UI navigation.
     */
    private void insertFolder(FileHandle folder, SQLiteDatabase db) {
        try {
            String path = folder.path().replace('\\', '/');
            if (!path.endsWith("/")) path += "/";
            String title = folder.name();
            FileHandle parentHandle = folder.parent();
            String parentCrc = "";
            if (parentHandle != null && parentHandle.path() != null) {
                String parentPath = parentHandle.path().replace('\\', '/');
                // 找到该文件夹所属的根目录，用于计算 Parent CRC
                String matchingRoot = findMatchingRoot(path);
                parentCrc = bms.player.beatoraja.song.SongUtils.crc32(
                        parentPath, bmsroot, matchingRoot);
            }
            long lastModified = folder.lastModified() / 1000;
            long currentTime = System.currentTimeMillis() / 1000;

            // 检查文件夹是否需要更新
            boolean needsUpdate = true;
            Cursor cursor = db.rawQuery("SELECT date FROM folder WHERE path = ?", new String[]{path});
            if (cursor.moveToFirst()) {
                int recordDate = cursor.getInt(0);
                if (recordDate == lastModified) {
                    needsUpdate = false;
                    Log.d(TAG, "Skipping unmodified folder: " + path);
                } else {
                    Log.i(TAG, "Folder modified, need to update: " + path
                            + " (DB date: " + recordDate + ", Folder date: " + lastModified + ")");
                }
            } else {
                Log.i(TAG, "New folder, need to add: " + path);
            }
            cursor.close();

            if (needsUpdate) {
                ContentValues cv = new ContentValues();
                cv.put("title", title);
                cv.put("path", path);
                cv.put("parent", parentCrc);
                cv.put("date", (int) lastModified);
                cv.put("adddate", (int) currentTime);

                // Insert with replace - update if already exists, insert if new
                db.insertWithOnConflict("folder", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                Log.d(TAG, "Inserted folder into DB: " + path);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to insert folder: " + folder.path(), e);
        }
    }

    /**
     * Check if file extension matches BMS file types: .bms, .bme, .bml, .pms, .bmson.
     * Case-insensitive matching. Only allows the exact file extensions listed above.
     * All other files (.wav, .ogg, .mp3, etc.) are strictly skipped.
     *
     * @param fileName Name of the file to check (already lowercased)
     * @return true if it's a BMS file
     */
    private boolean isBmsFile(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot == -1) {
            return false; // No file extension, skip
        }
        String ext = fileName.substring(lastDot);
        switch (ext) {
            case ".bms":
            case ".bme":
            case ".bml":
            case ".pms":
            case ".bmson":
                return true;
            default:
                return false;
        }
    }

    /**
     * Process a single BMS file: parse it, map to SongData, insert into database.
     *
     * @param file The BMS file to process
     * @param decoder BMS decoder instance
     * @param db Database
     * @param fileCount Counter for processed files
     * @param forceRefresh Whether to force re-processing (ignores existing records and timestamps)
     */
    private void processBmsFile(FileHandle file, BMSDecoder decoder, SQLiteDatabase db, AtomicInteger fileCount, boolean forceRefresh) {
        try {
            // Guard against null file
            if (file == null || !file.exists()) {
                Log.w(TAG, "[ProcessBmsFile] BMS file does not exist: " + (file != null ? file.path() : "null"));
                return;
            }

            String pathName = file.path().replace('\\', '/');
            long lastModifiedTime = file.lastModified() / 1000;

            // 检查是否需要增量更新
            if (!forceRefresh) {
                Cursor cursor = db.rawQuery("SELECT date FROM song WHERE path = ?", new String[]{pathName});
                if (cursor.moveToFirst()) {
                    int recordDate = cursor.getInt(0);
                    if (recordDate == lastModifiedTime) {
                        Log.d(TAG, "[ProcessBmsFile] Skipping unmodified file: " + pathName);
                        cursor.close();
                        return; // 文件未修改，跳过处理
                    } else {
                        Log.i(TAG, "[ProcessBmsFile] File modified, need to update: " + pathName
                                + " (DB date: " + recordDate + ", File date: " + lastModifiedTime + ")");
                    }
                } else {
                    Log.i(TAG, "[ProcessBmsFile] New file, need to add: " + pathName);
                }
                cursor.close();
            } else {
                Log.i(TAG, "[ProcessBmsFile] Force refreshing file: " + pathName);
            }

            // 解析 BMS 文件
            Log.d(TAG, "[ProcessBmsFile] Decoding: " + pathName);
            long decodeStart = System.currentTimeMillis();
            BMSModel model = decoder.decode(file);
            long decodeElapsed = System.currentTimeMillis() - decodeStart;

            if (model == null) {
                Log.w(TAG, "[ProcessBmsFile] Failed to parse BMS file (decode returned null): " + file.path());
                return;
            }

            Log.d(TAG, "[ProcessBmsFile] Decoded successfully in " + decodeElapsed + "ms: " + pathName);

            SongData songData = new SongData(model, false);
            songData.setPath(pathName);

            // 自动寻找匹配的根目录，确保多根目录下的 CRC 计算正确
            String matchingRoot = findMatchingRoot(pathName);

            // Get parent folder path CRC with null checks
            if (file.parent() != null) {
                String parentPath = file.parent().path().replace('\\', '/');
                songData.setFolder(bms.player.beatoraja.song.SongUtils.crc32(parentPath, bmsroot, matchingRoot));
                if (file.parent().parent() != null) {
                    String grandParentPath = file.parent().parent().path().replace('\\', '/');
                    songData.setParent(bms.player.beatoraja.song.SongUtils.crc32(grandParentPath, bmsroot, matchingRoot));
                } else {
                    songData.setParent("");
                }
            } else {
                songData.setFolder("");
                songData.setParent("");
            }

            songData.setDate((int) lastModifiedTime);
            long currentTime = System.currentTimeMillis() / 1000;
            songData.setAdddate((int) currentTime);

            ContentValues cv = new ContentValues();
            cv.put("md5", songData.getMd5());
            cv.put("sha256", songData.getSha256());
            cv.put("title", songData.getTitle());
            cv.put("subtitle", songData.getSubtitle());
            cv.put("genre", songData.getGenre());
            cv.put("artist", songData.getArtist());
            cv.put("subartist", songData.getSubartist());
            cv.put("tag", songData.getTag());
            cv.put("path", songData.getPath());
            cv.put("folder", songData.getFolder());
            cv.put("stagefile", songData.getStagefile());
            cv.put("banner", songData.getBanner());
            cv.put("backbmp", songData.getBackbmp());
            cv.put("preview", songData.getPreview());
            cv.put("parent", songData.getParent());
            cv.put("level", songData.getLevel());
            cv.put("difficulty", songData.getDifficulty());
            cv.put("maxbpm", songData.getMaxbpm());
            cv.put("minbpm", songData.getMinbpm());
            cv.put("length", songData.getLength());
            cv.put("mode", songData.getMode());
            cv.put("judge", songData.getJudge());
            cv.put("feature", songData.getFeature());
            cv.put("content", songData.getContent());
            cv.put("date", songData.getDate());
            cv.put("favorite", songData.getFavorite());
            cv.put("adddate", songData.getAdddate());
            cv.put("notes", songData.getNotes());
            cv.put("charthash", songData.getCharthash());

            try {
                long result = db.insertWithOnConflict("song", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                if (result == -1) {
                    Log.e(TAG, "[ProcessBmsFile] INSERT failed for: " + file.path() + " (result=-1)");
                } else {
                    fileCount.incrementAndGet();
                    Log.d(TAG, "[ProcessBmsFile] Successfully inserted/updated: " + songData.getTitle() + " (" + pathName + ")");
                }
            } catch (android.database.SQLException e) {
                Log.e(TAG, "================================================================================");
                Log.e(TAG, "[ProcessBmsFile] SQL INSERT failed for BMS file: " + file.path());
                Log.e(TAG, "[ProcessBmsFile] Error: " + e.getMessage());
                Log.e(TAG, "Song data: path=" + songData.getPath() + ", md5=" + songData.getMd5() + ", sha256=" + songData.getSha256());
                Log.e(TAG, "Full stack trace:", e);
                Log.e(TAG, "================================================================================");
                throw e;
            }

            if (fileCount.get() % 100 == 0) {
                Log.i(TAG, "Processed " + fileCount.get() + " BMS files...");
            }
        } catch (Exception e) {
            Log.e(TAG, "================================================================================");
            Log.e(TAG, "Error processing BMS file " + file.path(), e);
            Log.e(TAG, "================================================================================");
        }
    }

    private SongData cursorToSongData(Cursor c) {
        SongData sd = new SongData();
        try {
            sd.setMd5(getStringSafe(c, "md5"));
            sd.setSha256(getStringSafe(c, "sha256"));
            sd.setTitle(getStringSafe(c, "title"));
            sd.setSubtitle(getStringSafe(c, "subtitle"));
            sd.setGenre(getStringSafe(c, "genre"));
            sd.setArtist(getStringSafe(c, "artist"));
            sd.setSubartist(getStringSafe(c, "subartist"));
            sd.setTag(getStringSafe(c, "tag"));
            sd.setPath(getStringSafe(c, "path"));
            sd.setFolder(getStringSafe(c, "folder"));
            sd.setStagefile(getStringSafe(c, "stagefile"));
            sd.setBanner(getStringSafe(c, "banner"));
            sd.setBackbmp(getStringSafe(c, "backbmp"));
            sd.setPreview(getStringSafe(c, "preview"));
            sd.setParent(getStringSafe(c, "parent"));
            sd.setLevel(getIntSafe(c, "level"));
            sd.setDifficulty(getIntSafe(c, "difficulty"));
            sd.setMaxbpm(getIntSafe(c, "maxbpm"));
            sd.setMinbpm(getIntSafe(c, "minbpm"));
            sd.setLength(getIntSafe(c, "length"));
            sd.setMode(getIntSafe(c, "mode"));
            sd.setJudge(getIntSafe(c, "judge"));
            sd.setFeature(getIntSafe(c, "feature"));
            sd.setContent(getIntSafe(c, "content"));
            sd.setDate(getIntSafe(c, "date"));
            sd.setFavorite(getIntSafe(c, "favorite"));
            sd.setAdddate(getIntSafe(c, "adddate"));
            sd.setNotes(getIntSafe(c, "notes"));
            sd.setCharthash(getStringSafe(c, "charthash"));
        } catch (Throwable t) {
            Log.w(TAG, "cursorToSongData error", t);
        }
        return sd;
    }

    private static String getStringSafe(Cursor c, String col) {
        int idx = c.getColumnIndex(col);
        if (idx == -1) return "";
        String v = c.getString(idx);
        return v == null ? "" : v;
    }

    private static int getIntSafe(Cursor c, String col) {
        int idx = c.getColumnIndex(col);
        if (idx == -1) return 0;
        return c.getInt(idx);
    }

    private static class DBHelper extends SQLiteOpenHelper {
        private final String path;
        private static final int DATABASE_VERSION = 2; // Incremented to trigger migration for playcount column

        DBHelper(Context ctx, String path) {
            super(ctx, path, null, DATABASE_VERSION);
            this.path = path;
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            Log.i(TAG, "DBHelper onCreate: Creating all core tables...");
            // Create all core tables required by beatoraja
            // song table - stores BMS song information
            db.execSQL("CREATE TABLE IF NOT EXISTS song ("
                    + "md5 TEXT, "
                    + "sha256 TEXT, "
                    + "title TEXT, "
                    + "subtitle TEXT, "
                    + "genre TEXT, "
                    + "artist TEXT, "
                    + "subartist TEXT, "
                    + "tag TEXT, "
                    + "path TEXT, "
                    + "folder TEXT, "
                    + "stagefile TEXT, "
                    + "banner TEXT, "
                    + "backbmp TEXT, "
                    + "preview TEXT, "
                    + "parent TEXT, "
                    + "level INTEGER, "
                    + "difficulty INTEGER, "
                    + "maxbpm INTEGER, "
                    + "minbpm INTEGER, "
                    + "length INTEGER, "
                    + "mode INTEGER, "
                    + "judge INTEGER, "
                    + "feature INTEGER, "
                    + "content INTEGER, "
                    + "date INTEGER, "
                    + "favorite INTEGER, "
                    + "adddate INTEGER, "
                    + "notes INTEGER, "
                    + "playcount INTEGER, "
                    + "charthash TEXT"
                    + ", PRIMARY KEY (sha256))");
            System.out.println("Table Created: song");
            Log.i(TAG, "Table Created: song");

            // folder table - stores folder information
            db.execSQL("CREATE TABLE IF NOT EXISTS folder ("
                    + "title TEXT, "
                    + "subtitle TEXT, "
                    + "command TEXT, "
                    + "path TEXT, "
                    + "banner TEXT, "
                    + "parent TEXT, "
                    + "type INTEGER, "
                    + "date INTEGER, "
                    + "adddate INTEGER, "
                    + "max INTEGER"
                    + ", PRIMARY KEY (path))");
            System.out.println("Table Created: folder");
            Log.i(TAG, "Table Created: folder");

            // information table - stores clear status information
            db.execSQL("CREATE TABLE IF NOT EXISTS information ("
                    + "sha256 TEXT, "
                    + "mode INTEGER, "
                    + "level INTEGER, "
                    + "clear INTEGER, "
                    + "epclear INTEGER, "
                    + "bpclear INTEGER, "
                    + "noplay INTEGER, "
                    + "failed INTEGER, "
                    + "assist INTEGER, "
                    + "easy INTEGER, "
                    + "normal INTEGER, "
                    + "hard INTEGER, "
                    + "exhard INTEGER, "
                    + "fc INTEGER, "
                    + "perfect INTEGER"
                    + ", PRIMARY KEY (sha256))");
            System.out.println("Table Created: information");
            Log.i(TAG, "Table Created: information");

            // score table - stores best score information
            db.execSQL("CREATE TABLE IF NOT EXISTS score ("
                    + "sha256 TEXT, "
                    + "playcount INTEGER, "
                    + "clear INTEGER, "
                    + "score INTEGER, "
                    + "exscore INTEGER, "
                    + "maxcombo INTEGER, "
                    + "minbp INTEGER, "
                    + "perfect INTEGER, "
                    + "great INTEGER, "
                    + "good INTEGER, "
                    + "bad INTEGER, "
                    + "poor INTEGER, "
                    + "totalnotes INTEGER, "
                    + "fast INTEGER, "
                    + "slow INTEGER, "
                    + "date INTEGER, "
                    + "log TEXT, "
                    + "hash TEXT)");
            System.out.println("Table Created: score");
            Log.i(TAG, "Table Created: score");

            // scorelog table - stores score history
            db.execSQL("CREATE TABLE IF NOT EXISTS scorelog ("
                    + "sha256 TEXT, "
                    + "date INTEGER, "
                    + "clear INTEGER, "
                    + "score INTEGER, "
                    + "exscore INTEGER, "
                    + "maxcombo INTEGER, "
                    + "minbp INTEGER, "
                    + "perfect INTEGER, "
                    + "great INTEGER, "
                    + "good INTEGER, "
                    + "bad INTEGER, "
                    + "poor INTEGER, "
                    + "totalnotes INTEGER, "
                    + "fast INTEGER, "
                    + "slow INTEGER, "
                    + "option INTEGER, "
                    + "option2 INTEGER)");
            System.out.println("Table Created: scorelog");
            Log.i(TAG, "Table Created: scorelog");

            Log.i(TAG, "DBHelper onCreate: All tables created successfully");
        }
        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.i(TAG, "Upgrading database from version " + oldVersion + " to " + newVersion);

            // Migrate from version 1 to version 2: Add playcount column to song table
            if (oldVersion < 2) {
                try {
                    // Check if playcount column already exists
                    Cursor cursor = db.rawQuery("PRAGMA table_info(song)", null);
                    boolean hasPlaycount = false;
                    while (cursor.moveToNext()) {
                        String columnName = cursor.getString(cursor.getColumnIndex("name"));
                        if ("playcount".equals(columnName)) {
                            hasPlaycount = true;
                            break;
                        }
                    }
                    cursor.close();

                    if (!hasPlaycount) {
                        Log.i(TAG, "Adding playcount column to song table");
                        db.execSQL("ALTER TABLE song ADD COLUMN playcount INTEGER DEFAULT 0");
                        Log.i(TAG, "playcount column added successfully");
                    } else {
                        Log.i(TAG, "playcount column already exists, skipping");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to add playcount column: " + e.getMessage(), e);
                }
            }
        }
        @Override
        public void onOpen(SQLiteDatabase db) {
            super.onOpen(db);
            // 启用 WAL 模式：允许读写并发，多个读者不阻塞写者，彻底消除 SQLITE_BUSY。
            // Android SQLiteDatabase 在 enableWriteAheadLogging() 后，
            // 所有后续连接（包括 JDBC/SQLDroid 打开的 score.db）都运行在 WAL 模式下。
            try {
                if (!db.isReadOnly()) {
                    db.execSQL("PRAGMA busy_timeout = 15000");
                    db.execSQL("PRAGMA journal_mode = WAL");
                    Log.i(TAG, "WAL mode enabled for: " + db.getPath());
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to enable WAL on " + db.getPath() + ": " + t.getMessage());
            }
        }
    }
}

