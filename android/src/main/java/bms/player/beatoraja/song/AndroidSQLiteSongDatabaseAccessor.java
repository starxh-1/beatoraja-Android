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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import bms.model.BMSDecoder;
import bms.model.BMSModel;
import bms.model.BMSONDecoder;
import bms.player.beatoraja.song.SongData;
import bms.player.beatoraja.song.FolderData;
import bms.player.beatoraja.Validatable;

/**
 * Android 原生 SQLite 实现的 SongDatabaseAccessor
 * 对应原版 SQLiteSongDatabaseAccessor
 */
public class AndroidSQLiteSongDatabaseAccessor implements SongDatabaseAccessor {
    private static final String TAG = "AndroidSongDB";
    private final DBHelper helper;
    private final String[] bmsroot;
    // 多线程扫描：32位设备适当限制，平衡性能与内存安全
    private static final int PARALLEL_THREAD_COUNT = "true".equals(System.getProperty("beatoraja.32bit"))
            ? Math.max(1, Math.min(Runtime.getRuntime().availableProcessors() - 1, 4))
            : Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
    // 小于此数量则使用串行处理（避免线程创建开销）
    private static final int PARALLEL_THRESHOLD = 50;

    // 插件扩展机制
    private List<SongDatabaseAccessorPlugin> plugins = new ArrayList<>();

    // tag/favorite 保持用映射
    private java.util.Map<String, String> tags = new java.util.HashMap<>();
    private java.util.Map<String, Integer> favorites = new java.util.HashMap<>();

    // 增量 Deletion Sync：扫描前快照（DB 当时的全部 path）+ 本次扫描所见 path
    private volatile Set<String> preScanPathSnapshot = null;
    private final Set<String> seenThisScan = ConcurrentHashMap.newKeySet();

    // CRC计算用的 root 路径（对应原版的 Paths.get(".").toString()）
    private final String rootpath = "";

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
     * 添加插件
     */
    public void addPlugin(SongDatabaseAccessorPlugin plugin) {
        plugins.add(plugin);
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
        // Use rawQuery with try-with-resources to ensure cursor closure
        String sql = "SELECT * FROM song WHERE " + key + " = '" + value.replace("'", "''") + "'";
        try (Cursor c = db.rawQuery(sql, null)) {
            while (c.moveToNext()) {
                SongData sd = cursorToSongData(c);
                list.add(sd);
            }
        } catch (Throwable t) {
            Log.e(TAG, "getSongDatas failed: " + t.getMessage(), t);
        }
        return Validatable.removeInvalidElements(list.toArray(new SongData[0]));
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
        try (Cursor c = db.rawQuery(sql, null)) {
            while (c.moveToNext()) {
                list.add(cursorToSongData(c));
            }
        } catch (Throwable t) {
            Log.w(TAG, "getSongDatas by hashes failed", t);
        }
        SongData[] result = list.toArray(new SongData[0]);
        // 搜索排列顺序保持（对应原版逻辑）
        SongData[] sorted = new SongData[hashes.length];
        for (SongData sd : result) {
            for (int i = 0; i < hashes.length; i++) {
                if (hashes[i].equals(sd.getSha256()) || hashes[i].equals(sd.getMd5())) {
                    if (sorted[i] == null) sorted[i] = sd;
                    break;
                }
            }
        }
        List<SongData> finalList = new ArrayList<>();
        for (SongData sd : sorted) {
            if (sd != null) finalList.add(sd);
        }
        return Validatable.removeInvalidElements(finalList.toArray(new SongData[0]));
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

        try (SQLiteDatabase scoreDb = SQLiteDatabase.openDatabase(scorePath, null, SQLiteDatabase.OPEN_READONLY)) {
            // beatoraja score.db: 表名 "score"，列：sha256, clear, combo, minbp
            try (Cursor c = scoreDb.rawQuery("SELECT sha256, clear, combo, minbp FROM score", null)) {
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
            }
        } catch (Throwable t) {
            Log.w(TAG, "readScoreMapFromFile failed: " + scorePath + " - " + t.getMessage());
        }
        return map;
    }

    @Override
    public SongData[] getSongDatas(String sql, String score, String scorelog) {
        SQLiteDatabase db = helper.getReadableDatabase();
        List<SongData> list = new ArrayList<>();

        try {
            // Android SQLite doesn't support ATTACH DATABASE well in multi-threaded context
            // Always use in-memory fallback approach (original design)
            java.util.Map<String, ScoreData> scoreMap = new java.util.HashMap<>();
            if (score != null && !score.isEmpty() && new File(score).exists()) {
                scoreMap = readScoreDataFromFile(score);
                Log.i(TAG, "Loaded " + scoreMap.size() + " score records for filtering");
            }

            String baseSelect = "SELECT DISTINCT md5, sha256, title, subtitle, genre, artist, subartist, "
                    + "path, folder, stagefile, banner, backbmp, parent, level, difficulty, "
                    + "maxbpm, minbpm, mode, judge, feature, content, date, favorite, notes, "
                    + "adddate, preview, length, charthash FROM song";

            try (Cursor c = db.rawQuery(baseSelect + " WHERE " + sql, null)) {
                while (c.moveToNext()) {
                    try {
                        SongData sd = cursorToSongData(c);
                        list.add(sd);
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable sqlErr) {
                Log.w(TAG, "getSongDatas(sql) WHERE clause failed: " + sqlErr.getMessage());
                list.clear();
                try (Cursor c = db.rawQuery(baseSelect, null)) {
                    while (c.moveToNext()) {
                        try {
                            SongData sd = cursorToSongData(c);
                            list.add(sd);
                        } catch (Throwable ignored) {}
                    }
                }
                if (!scoreMap.isEmpty()) {
                    list = filterSongDataList(list, scoreMap, sql);
                }
            }

            Log.i(TAG, "getSongDatas(sql) completed: " + list.size() + " results");

        } catch (Throwable t) {
            Log.w(TAG, "getSongDatas(sql) failed", t);
        }
        return Validatable.removeInvalidElements(list.toArray(new SongData[0]));
    }

    private java.util.Map<String, ScoreData> readScoreDataFromFile(String scorePath) {
        java.util.Map<String, ScoreData> map = new java.util.HashMap<>();
        if (scorePath == null || scorePath.isEmpty()) return map;
        File f = new File(scorePath);
        if (!f.exists()) return map;

        try (SQLiteDatabase scoreDb = SQLiteDatabase.openDatabase(scorePath, null, SQLiteDatabase.OPEN_READONLY)) {
            java.util.Set<String> columns = new java.util.HashSet<>();
            try (Cursor cols = scoreDb.rawQuery("PRAGMA table_info(score)", null)) {
                while (cols.moveToNext()) {
                    columns.add(cols.getString(cols.getColumnIndex("name")));
                }
            }

            if (columns.isEmpty()) {
                Log.w(TAG, "score table has no columns in: " + scorePath);
                return map;
            }

            StringBuilder sql = new StringBuilder("SELECT ");
            sql.append("sha256, playcount, clear, ");
            if (columns.contains("exscore")) {
                sql.append("exscore, ");
            } else if (columns.contains("score")) {
                sql.append("score AS exscore, ");
            } else {
                sql.append("0 AS exscore, ");
            }
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

            try (Cursor c = scoreDb.rawQuery(sql.toString(), null)) {
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
            }
        } catch (Throwable t) {
            Log.w(TAG, "readScoreDataFromFile failed: " + scorePath + " - " + t.getMessage());
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
                cv.put("tail", sd.getTail());
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
    public void updateSongTail(String sha256, int tail) {
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("tail", tail);
        db.update("song", cv, "sha256 = ?", new String[]{sha256});
    }

    @Override
    public SongData[] getSongDatasByText(String text) {
        SQLiteDatabase db = helper.getReadableDatabase();
        List<SongData> list = new ArrayList<>();
        // 对应原版: rtrim(title||' '||subtitle||' '||artist||' '||subartist||' '||genre) LIKE ?
        String sql = "SELECT * FROM song WHERE rtrim(title||' '||subtitle||' '||artist||' '||subartist||' '||genre) LIKE ? GROUP BY sha256";
        try (Cursor c = db.rawQuery(sql, new String[]{"%" + text + "%"})) {
            while (c.moveToNext()) {
                list.add(cursorToSongData(c));
            }
        } catch (Throwable t) {
            Log.w(TAG, "getSongDatasByText failed", t);
        }
        return Validatable.removeInvalidElements(list.toArray(new SongData[0]));
    }

    @Override
    public FolderData[] getFolderDatas(String key, String value) {
        SQLiteDatabase db = helper.getReadableDatabase();
        List<FolderData> list = new ArrayList<>();
        try {
            // Use rawQuery with direct string instead of db.query() with parameters
            // This avoids SQLDroid issue where getParameterMetaData is not implemented
            String sql = "SELECT * FROM folder WHERE " + key + " = '" + value.replace("'", "''") + "'";
            try (Cursor c = db.rawQuery(sql, null)) {
                while (c.moveToNext()) {
                    FolderData fd = new FolderData();
                    fd.setTitle(getStringSafe(c, "title"));
                    fd.setPath(getStringSafe(c, "path"));
                    fd.setDate(getIntSafe(c, "date"));
                    list.add(fd);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "getFolderDatas failed: " + key + "=" + value + " - " + t.getMessage(), t);
        }
        return list.toArray(new FolderData[0]);
    }

    @Override
    public String[] getBmsRoot() {
        return bmsroot;
    }

    @Override
    public void updateSongDatas(String updatepath, String[] bmsroot, boolean updateAll) {
        updateSongDatas(updatepath, bmsroot, updateAll, (SongScanProgress) null);
    }

    @Override
    public void updateSongDatas(String updatepath, String[] bmsroot, boolean updateAll, SongScanProgress progress) {
        if (updatepath != null) {
            updateSongDatas(new String[]{updatepath}, updateAll, progress);
        } else {
            updateSongDatas(bmsroot, updateAll, progress);
        }
    }

    /**
     * Update song database by scanning the given paths recursively using LibGDX Gdx.files.absolute() API.
     * Uses LibGDX FileHandle API to bypass standard Android IO limitations and ensure cross-platform compatibility.
     * Implements incremental sync, deletion detection, and force refresh functionality.
     *
     * @param paths Array of root paths to scan for BMS files
     * @param forceRefresh Whether to force a full re-scan (ignores existing records and timestamps)
     * @param progress Progress callback (can be null)
     */
    public void updateSongDatas(String[] paths, boolean forceRefresh, SongScanProgress progress) {
        long startTime = System.currentTimeMillis();
        final AtomicInteger updatedCount = new AtomicInteger(0);
        final AtomicInteger scannedCount = new AtomicInteger(0);
        BMSDecoder decoder = new BMSDecoder(BMSModel.LNTYPE_LONGNOTE);
        SQLiteDatabase db = helper.getWritableDatabase();

        // 阶段 0: 预扫描计算总文件数，以便 UI 显示进度
        int totalFiles = 0;
        long preScanStart = System.currentTimeMillis();
        for (String path : paths) {
            if (path == null || path.trim().isEmpty()) continue;
            try {
                FileHandle rootDir = Gdx.files.absolute(path.trim());
                if (rootDir.exists()) {
                    totalFiles += countBmsFiles(rootDir);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Pre-scan failed for path: " + path, t);
            }
        }
        long preScanElapsed = System.currentTimeMillis() - preScanStart;
        Log.i(TAG, "updateSongDatas: Pre-scan found " + totalFiles + " BMS files in " + preScanElapsed + "ms");

        // 初始化进度
        if (progress != null) {
            progress.onFileScanned(0, totalFiles);
        }

        // 检查数据库中已有多少歌曲记录，用于统计
        int existingSongCount = 0;
        try (Cursor songCountCursor = db.rawQuery("SELECT COUNT(*) FROM song", null)) {
            if (songCountCursor.moveToFirst()) {
                existingSongCount = songCountCursor.getInt(0);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to get existing song count", t);
        }
        Log.i(TAG, "updateSongDatas: Existing songs in DB: " + existingSongCount + ", forceRefresh: " + forceRefresh);

        // 捕获 pre-scan path 快照 + 清空本次扫描所见集合
        preScanPathSnapshot = new HashSet<>();
        try (Cursor cursor = db.rawQuery("SELECT path FROM song", null)) {
            while (cursor.moveToNext()) {
                String p = getStringSafe(cursor, "path");
                if (p != null) preScanPathSnapshot.add(p);
            }
        }
        seenThisScan.clear();
        Log.i(TAG, "updateSongDatas: Pre-scan snapshot: " + preScanPathSnapshot.size() + " paths");

        // 保持 tag 和 favorite（在删除前读取）
        tags.clear();
        favorites.clear();
        try {
            SQLiteDatabase dbRead = helper.getReadableDatabase();
            try (Cursor cursor = dbRead.rawQuery("SELECT sha256, tag, favorite FROM song", null)) {
                while (cursor.moveToNext()) {
                    String sha256 = getStringSafe(cursor, "sha256");
                    String tag = getStringSafe(cursor, "tag");
                    int favorite = getIntSafe(cursor, "favorite");
                    if (tag != null && tag.length() > 0) {
                        tags.put(sha256, tag);
                    }
                    if (favorite > 0) {
                        favorites.put(sha256, favorite);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to preserve tags/favorites", t);
        }

        // Force create all tables if they don't exist BEFORE scanning
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

        Log.i(TAG, "updateSongDatas: All tables verified. Starting scan with " + paths.length + " root paths");

        // 在所有扫描根目录下创建 .nomedia 文件
        for (String path : paths) {
            if (path == null || path.trim().isEmpty()) continue;
            try {
                FileHandle rootDir = Gdx.files.absolute(path.trim());
                if (rootDir.exists() && rootDir.isDirectory()) {
                    FileHandle nomedia = rootDir.child(".nomedia");
                    if (!nomedia.exists()) {
                        nomedia.writeString("", false);
                        Log.i(TAG, "Created .nomedia in root path: " + rootDir.path());
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to create .nomedia in " + path);
            }
        }

        int deleteCount = 0;
        try {
            // Step 1: 扫描（forceRefresh 用并行，普通用串行）
            if (forceRefresh) {
                updateSongDatasParallel(paths, forceRefresh, progress);
            } else {
                for (String pathToScan : paths) {
                    if (pathToScan == null || pathToScan.trim().isEmpty()) continue;
                    FileHandle scanDir = Gdx.files.absolute(pathToScan.trim());
                    if (scanDir.exists()) {
                        Log.i(TAG, "Starting recursive scan of: " + scanDir.path());
                        // Each folder handles its own transaction
                        scanFolderRecursively(scanDir, decoder, db, scannedCount, updatedCount, totalFiles, forceRefresh, progress);
                    }
                }
            }

            // Step 2: 增量 Deletion Sync - 扫描后基于 (pre-scan 快照 - 本次扫描所见) 做差集检查
            if (preScanPathSnapshot != null) {
                Set<String> candidates = new HashSet<>(preScanPathSnapshot);
                candidates.removeAll(seenThisScan);
                Log.i(TAG, "Deletion Sync: " + candidates.size() + " candidates (not seen this scan)");

                if (!candidates.isEmpty()) {
                    long delCheckStart = System.currentTimeMillis();
                    Set<String> toDelete = Collections.newSetFromMap(new ConcurrentHashMap<>());
                    ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_THREAD_COUNT);
                    for (final String path : candidates) {
                        executor.submit(() -> {
                            if (!Gdx.files.absolute(path).exists()) {
                                toDelete.add(path);
                            }
                        });
                    }
                    executor.shutdown();
                    try {
                        executor.awaitTermination(120, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Log.w(TAG, "Deletion Sync: Executor interrupted", e);
                    }
                    long delCheckElapsed = System.currentTimeMillis() - delCheckStart;
                    Log.i(TAG, "Deletion Sync: Found " + toDelete.size() + " missing files in " + delCheckElapsed + "ms");

                    // 批量删除
                    if (!toDelete.isEmpty()) {
                        db.beginTransactionNonExclusive();
                        try {
                            for (String path : toDelete) {
                                db.delete("song", "path = ?", new String[]{path});
                                deleteCount++;
                            }
                            db.setTransactionSuccessful();
                        } finally {
                            db.endTransaction();
                        }
                        // 清除目录缓存后，基于当前文件系统完整重建 folder 表
                        // 避免删除歌曲后 folder 表为空导致 select 界面文件夹消失
                        rebuildFolderTable(db);
                        Log.i(TAG, "Deletion Sync: Deleted " + deleteCount + " records and rebuilt folder table");
                    }
                }
                preScanPathSnapshot = null; // 清空供下次扫描
            }

            long endTime = System.currentTimeMillis();
            int newSongCount = 0;
            try (Cursor newSongCountCursor = db.rawQuery("SELECT COUNT(*) FROM song", null)) {
                if (newSongCountCursor.moveToFirst()) {
                    newSongCount = newSongCountCursor.getInt(0);
                }
            } catch (Throwable ignored) {}

            Log.i(TAG, "updateSongDatas completed: " + scannedCount.get() + " files scanned, "
                    + updatedCount.get() + " files updated, "
                    + deleteCount + " files deleted, "
                    + "total songs now: " + newSongCount
                    + " in " + (endTime - startTime) + "ms");
        } catch (Throwable t) {
            Log.e(TAG, "updateSongDatas failed", t);
        } finally {
            try {
                db.execSQL("PRAGMA wal_checkpoint(FULL)");
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Overloaded method for backward compatibility (default to non-force refresh)
     *
     * @param paths Array of root paths to scan for BMS files
     */
    public void updateSongDatas(String[] paths) {
        updateSongDatas(paths, false, null);
    }

    /**
     * 多线程版本：收集所有BMS文件路径，然后并行解码，最后批量写入数据库
     * 适用于大量文件的场景
     */
    private void updateSongDatasParallel(String[] paths, boolean forceRefresh) {
        updateSongDatasParallel(paths, forceRefresh, null);
    }

    private void updateSongDatasParallel(String[] paths, boolean forceRefresh, SongScanProgress progress) {
        long startTime = System.currentTimeMillis();
        Log.i(TAG, "updateSongDatasParallel: Starting with " + paths.length + " root paths, forceRefresh=" + forceRefresh);

        // 阶段1: 收集所有BMS文件路径（单线程遍历）
        long collectStart = System.currentTimeMillis();
        List<FileHandle> allFiles = new ArrayList<>();

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

        // 阶段1.5: 预加载数据库中的文件日期，用于快速增量更新检查
        Map<String, Integer> dateCache = new HashMap<>();
        if (!forceRefresh) {
            long cacheStart = System.currentTimeMillis();
            SQLiteDatabase db = helper.getReadableDatabase();
            try (Cursor cursor = db.rawQuery("SELECT path, date FROM song", null)) {
                while (cursor.moveToNext()) {
                    dateCache.put(cursor.getString(0), cursor.getInt(1));
                }
            }
            Log.i(TAG, "updateSongDatasParallel: Phase1.5 cached " + dateCache.size() + " existing files in " + (System.currentTimeMillis() - cacheStart) + "ms");
        }

        // 阶段2: 过滤未修改的文件并多线程解码
        List<FileHandle> filesToScan = new ArrayList<>();
        if (forceRefresh) {
            filesToScan.addAll(allFiles);
        } else {
            for (FileHandle file : allFiles) {
                String pathName = file.path().replace('\\', '/');
                Integer cachedDate = dateCache.get(pathName);
                int lastModifiedTime = (int) (file.lastModified() / 1000);
                if (cachedDate == null || cachedDate != lastModifiedTime) {
                    filesToScan.add(file);
                }
            }
        }

        int totalToScan = filesToScan.size();
        Log.i(TAG, "updateSongDatasParallel: Phase2 filtering done, " + totalToScan + "/" + allFiles.size() + " files need scanning.");

        if (totalToScan == 0) {
            Log.i(TAG, "updateSongDatasParallel: No new or modified files found, scan completed.");
            if (progress != null) {
                progress.onFileScanned(allFiles.size(), allFiles.size());
            }
            return;
        }

        long decodeStart = System.currentTimeMillis();
        final AtomicInteger processedCount = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_THREAD_COUNT);
        List<Future<DecodeResult>> futures = new ArrayList<>();

        for (final FileHandle file : filesToScan) {
            futures.add(executor.submit(() -> {
                // 每个线程创建自己的解码器，确保线程安全
                BMSDecoder localDecoder = new BMSDecoder(BMSModel.LNTYPE_LONGNOTE);
                // 已经在外层过滤过，此处直接强制解码
                DecodeResult result = processBmsFileParallel(file, localDecoder, true);

                // 无论是否跳过，都上报进度（增加节流，每 10 个文件更新一次，避免主线程洪水）
                int current = processedCount.incrementAndGet();
                if (progress != null && (current % 2 == 0 || current == totalToScan)) {
                    progress.onFileScanned(current, totalToScan);
                }
                return result;
            }));
        }

        // 阶段3 & 4: 流式写入数据库（单线程事务），避免一次性缓存大量结果导致内存溢出
        long writeStart = System.currentTimeMillis();
        SQLiteDatabase db = helper.getWritableDatabase();
        int successCount = 0;
        int batchCount = 0;
        final int BATCH_SIZE = 100; // 每 100 条记录提交一次事务

        db.beginTransaction();
        try {
            for (int i = 0; i < futures.size(); i++) {
                Future<DecodeResult> f = futures.get(i);
                try {
                    // 设置较长的超时，防止低端设备卡死
                    DecodeResult result = f.get(60, TimeUnit.SECONDS);
                    if (result != null && result.songData != null) {
                        insertSongData(result.songData, db);
                        if (result.model != null) {
                            insertInformation(new bms.player.beatoraja.song.SongInformation(result.model), db);
                        }
                        successCount++;
                        batchCount++;

                        // 分批提交事务，降低 32 位系统内存压力
                        if (batchCount >= BATCH_SIZE) {
                            db.setTransactionSuccessful();
                            db.endTransaction();
                            db.beginTransaction();
                            batchCount = 0;
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "updateSongDatasParallel: Decode failed", e);
                } finally {
                    // 处理完一个结果后立即释放 Future 引用，允许 GC 回收庞大的 BMSModel
                    futures.set(i, null);
                }
            }
            db.setTransactionSuccessful();
        } catch (Throwable t) {
            Log.e(TAG, "updateSongDatasParallel: Database batch process failed", t);
        } finally {
            db.endTransaction();
            // 强制 checkpoint
            try {
                db.execSQL("PRAGMA wal_checkpoint(FULL)");
            } catch (Throwable e) {
                Log.w(TAG, "wal_checkpoint failed: " + e.getMessage());
            }
        }

        executor.shutdown();
        long totalElapsed = System.currentTimeMillis() - startTime;
        long decodeElapsed = System.currentTimeMillis() - decodeStart;
        long writeElapsed = System.currentTimeMillis() - writeStart;
        Log.i(TAG, "updateSongDatasParallel completed: wrote " + successCount + " songs (decode: " + decodeElapsed + "ms, write: " + writeElapsed + "ms), total: " + totalElapsed + "ms");
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
     * 计算目录下 BMS 文件总数（轻量预扫描）
     */
    private int countBmsFiles(FileHandle folder) {
        int count = 0;
        try {
            if (!folder.exists() || !folder.isDirectory()) return 0;
            FileHandle[] children = folder.list();
            if (children == null) return 0;

            for (FileHandle child : children) {
                if (child.isDirectory()) {
                    count += countBmsFiles(child);
                } else if (isBmsFile(child.name().toLowerCase())) {
                    count++;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "countBmsFiles: Failed to scan " + folder.path(), e);
        }
        return count;
    }

    /**
     * 并行解码时的结果包装类
     */
    private static class DecodeResult {
        SongData songData;
        FileHandle file;
        int lastModified;
        BMSModel model;

        DecodeResult(SongData songData, FileHandle file, int lastModified) {
            this.songData = songData;
            this.file = file;
            this.lastModified = lastModified;
        }

        DecodeResult(SongData songData, FileHandle file, int lastModified, BMSModel model) {
            this.songData = songData;
            this.file = file;
            this.lastModified = lastModified;
            this.model = model;
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
            // 标记为本次扫描所见（用于增量 Deletion Sync）
            seenThisScan.add(pathName);

            // 增量更新检查：如果传入 forceRefresh 为 false，则尝试从库中读取。
            // 优化：updateSongDatasParallel 已在前置阶段通过 dateCache 过滤，通常这里 forceRefresh 应为 true。
            if (!forceRefresh) {
                SQLiteDatabase db = helper.getReadableDatabase();
                try (Cursor cursor = db.rawQuery("SELECT date FROM song WHERE path = ?", new String[]{pathName})) {
                    if (cursor.moveToFirst()) {
                        int recordDate = cursor.getInt(0);
                        if (recordDate == lastModifiedTime) {
                            return null; // 未修改，跳过
                        }
                    }
                }
            }

            // 解析 BMS/BMSON 文件
            BMSModel model = null;
            if (pathName.toLowerCase().endsWith(".bmson")) {
                BMSONDecoder bmsonDecoder = new BMSONDecoder(BMSModel.LNTYPE_LONGNOTE);
                try {
                    model = bmsonDecoder.decode(file);
                } catch (Exception e) {
                    Log.w(TAG, "[processBmsFileParallel] bmson decode failed: " + pathName, e);
                }
            } else {
                model = decoder.decode(file);
            }
            if (model == null) return null;

            SongData songData = new SongData(model, false);
            songData.setPath(pathName);

            // 保持 tag/favorite
            String existingTag = tags.get(songData.getSha256());
            Integer existingFavorite = favorites.get(songData.getSha256());
            songData.setTag(existingTag != null ? existingTag : "");
            songData.setFavorite(existingFavorite != null ? existingFavorite : 0);

            songData.setDate(lastModifiedTime);
            songData.setAdddate((int) (System.currentTimeMillis() / 1000));

            // CRC计算 - 使用 rootpath 作为 bmspath（对应原版逻辑）
            String matchingRoot = findMatchingRoot(pathName);
            if (file.parent() != null) {
                String parentPath = file.parent().path().replace('\\', '/');
                songData.setFolder(bms.player.beatoraja.song.SongUtils.crc32(parentPath, bmsroot, rootpath));
                if (file.parent().parent() != null) {
                    String grandParentPath = file.parent().parent().path().replace('\\', '/');
                    songData.setParent(bms.player.beatoraja.song.SongUtils.crc32(grandParentPath, bmsroot, rootpath));
                }
            }

            return new DecodeResult(songData, file, lastModifiedTime, model);
        } catch (Exception e) {
            Log.w(TAG, "processBmsFileParallel: Failed " + (file != null ? file.path() : "null"), e);
            return null;
        }
    }

    private void insertInformation(bms.player.beatoraja.song.SongInformation info, SQLiteDatabase db) {
        ContentValues cv = new ContentValues();
        cv.put("sha256", info.getSha256());
        cv.put("n", info.getN());
        cv.put("ln", info.getLn());
        cv.put("s", info.getS());
        cv.put("ls", info.getLs());
        cv.put("total", info.getTotal());
        cv.put("density", info.getDensity());
        cv.put("peakdensity", info.getPeakdensity());
        cv.put("enddensity", info.getEnddensity());
        cv.put("mainbpm", info.getMainbpm());
        cv.put("distribution", info.getDistribution());
        cv.put("speedchange", info.getSpeedchange());
        cv.put("lanenotes", info.getLanenotes());
        db.insertWithOnConflict("information", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
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
     * @param scannedCount Counter for all found BMS files (for progress)
     * @param updatedCount Counter for actually updated files (for logging)
     * @param totalFiles Total files pre-calculated (for progress)
     * @param forceRefresh Whether to force a full re-scan (ignores existing records and timestamps)
     * @param progress Progress callback (can be null)
     * @return true if this folder or any subfolder contains at least one BMS file
     */
    private boolean scanFolderRecursively(FileHandle folder, BMSDecoder decoder, SQLiteDatabase db, AtomicInteger scannedCount, AtomicInteger updatedCount, int totalFiles, boolean forceRefresh, SongScanProgress progress) {
        if (!folder.exists() || !folder.isDirectory()) {
            return false;
        }

        FileHandle[] children;
        try {
            children = folder.list();
        } catch (Exception e) {
            Log.w(TAG, "Failed to list folder: " + folder.path());
            return false;
        }
        if (children == null) return false;

        boolean containsBms = false;
        boolean hasTxt = false; // 文件夹是否包含 .txt 文件
        String previewPath = null; // 预览文件路径
        List<FileHandle> bmsFiles = new ArrayList<>();
        List<FileHandle> subDirs = new ArrayList<>();
        for (FileHandle child : children) {
            if (child.isDirectory()) {
                subDirs.add(child);
            } else if (isBmsFile(child.name().toLowerCase())) {
                bmsFiles.add(child);
            } else {
                // 检测 .txt 文件
                String lowerName = child.name().toLowerCase();
                if (lowerName.endsWith(".txt")) {
                    hasTxt = true;
                }
                // 检测 preview 文件 (对应原版逻辑: preview.*.wav/.ogg/.mp3/.flac)
                if (previewPath == null && lowerName.startsWith("preview")) {
                    if (lowerName.endsWith(".wav") || lowerName.endsWith(".ogg") ||
                        lowerName.endsWith(".mp3") || lowerName.endsWith(".flac")) {
                        previewPath = child.file().getAbsolutePath();
                    }
                }
            }
        }

        // 1. 处理当前文件夹下的 BMS 文件
        if (!bmsFiles.isEmpty()) {
            containsBms = true;
            db.beginTransactionNonExclusive();
            try {
                for (FileHandle bmsFile : bmsFiles) {
                    processBmsFile(bmsFile, decoder, db, scannedCount, updatedCount, totalFiles, forceRefresh, hasTxt, previewPath, progress);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }

        // 2. 递归扫描子目录
        for (FileHandle subDir : subDirs) {
            if (scanFolderRecursively(subDir, decoder, db, scannedCount, updatedCount, totalFiles, forceRefresh, progress)) {
                containsBms = true;
            }
        }

        // 3. 如果本目录或其子目录包含 BMS，则插入目录记录到 folder 表
        // 这是确保 UI 层级能够逐层显示文件夹的关键
        if (containsBms) {
            db.beginTransactionNonExclusive();
            try {
                insertFolder(folder, db);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }

        return containsBms;
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
            try (Cursor cursor = db.rawQuery("SELECT date FROM folder WHERE path = ?", new String[]{path})) {
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
                    // Log.i(TAG, "New folder, need to add: " + path);
                }
            }

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
     * 完整重建 folder 表：先清空，再基于当前文件系统遍历所有包含 BMS 文件的目录并插入记录。
     * 使用 seenThisScan（本次扫描见过的 BMS 文件路径集合）快速判断目录是否包含 BMS 文件。
     * 在 Deletion Sync 删除歌曲后调用，确保 folder 层级结构完整。
     */
    private void rebuildFolderTable(SQLiteDatabase db) {
        long startTime = System.currentTimeMillis();
        db.delete("folder", null, null);
        Log.i(TAG, "rebuildFolderTable: Cleared folder table, rebuilding from filesystem...");

        if (bmsroot == null || bmsroot.length == 0) {
            Log.w(TAG, "rebuildFolderTable: No bmsroot configured, skip rebuild");
            return;
        }

        Set<String> insertedPaths = new HashSet<>();
        int count = 0;

        for (String root : bmsroot) {
            if (root == null || root.trim().isEmpty()) continue;
            try {
                FileHandle rootDir = Gdx.files.absolute(root.trim());
                if (rootDir.exists() && rootDir.isDirectory()) {
                    // 遍历 bmsroot 下的子目录，bmsroot 本身由根 FolderBar 的 CRC 标识，不插入 folder 表
                    FileHandle[] children = rootDir.list();
                    if (children != null) {
                        for (FileHandle child : children) {
                            if (child.isDirectory()) {
                                count += rebuildFolderTree(child, db, insertedPaths);
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "rebuildFolderTable: Error processing root: " + root, t);
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        Log.i(TAG, "rebuildFolderTable: Rebuilt " + count + " folder records in " + elapsed + "ms");
    }

    /**
     * 递归遍历目录树，对每个包含 BMS 文件的目录调用 insertFolder 插入 folder 记录。
     * 采用后序遍历（先子目录后自身），确保子目录先于父目录插入。
     *
     * @return 插入的 folder 记录数
     */
    private int rebuildFolderTree(FileHandle dir, SQLiteDatabase db, Set<String> insertedPaths) {
        int count = 0;
        try {
            // 先递归处理子目录
            FileHandle[] children = dir.list();
            if (children != null) {
                for (FileHandle child : children) {
                    if (child.isDirectory()) {
                        count += rebuildFolderTree(child, db, insertedPaths);
                    }
                }
            }

            // 检查本目录是否包含 BMS 文件（基于 seenThisScan）
            String dirPath = dir.path().replace('\\', '/');
            if (!dirPath.endsWith("/")) dirPath += "/";

            boolean hasBms = false;
            for (String bmsPath : seenThisScan) {
                if (bmsPath.startsWith(dirPath)) {
                    hasBms = true;
                    break;
                }
            }

            if (hasBms && !insertedPaths.contains(dirPath)) {
                insertFolder(dir, db);
                insertedPaths.add(dirPath);
                count++;
            }
        } catch (Throwable t) {
            Log.w(TAG, "rebuildFolderTree: Error processing directory: " + dir.path(), t);
        }
        return count;
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
     * Process a single BMS file: decode it and insert/update its data in the database.
     *
     * @param file The BMS/BMSON file to process
     * @param decoder BMS decoder instance
     * @param db Database connection
     * @param scannedCount Counter for all found BMS files (for progress)
     * @param updatedCount Counter for actually updated files (for logging)
     * @param totalFiles Total files pre-calculated (for progress)
     * @param forceRefresh Whether to force re-processing (ignores existing records and timestamps)
     * @param txt Whether the folder contains a .txt file
     * @param previewPath Preview file path if found in the folder
     * @param progress Progress callback (can be null)
     */
    private void processBmsFile(FileHandle file, BMSDecoder decoder, SQLiteDatabase db, AtomicInteger scannedCount, AtomicInteger updatedCount, int totalFiles, boolean forceRefresh, boolean txt, String previewPath, SongScanProgress progress) {
        try {
            // Guard against null file
            if (file == null || !file.exists()) {
                Log.w(TAG, "[ProcessBmsFile] BMS file does not exist: " + (file != null ? file.path() : "null"));
                return;
            }

            String pathName = file.path().replace('\\', '/');
            long lastModifiedTime = file.lastModified() / 1000;
            // 标记为本次扫描所见（用于增量 Deletion Sync）
            seenThisScan.add(pathName);

            // 无论是否跳过，都增加扫描计数并上报进度
            int currentScanned = scannedCount.incrementAndGet();
            if (progress != null) {
                progress.onFileScanned(currentScanned, totalFiles);
            }

            // 检查是否需要增量更新
            if (!forceRefresh) {
                try (Cursor cursor = db.rawQuery("SELECT date FROM song WHERE path = ?", new String[]{pathName})) {
                    if (cursor.moveToFirst()) {
                        int recordDate = cursor.getInt(0);
                        if (recordDate == lastModifiedTime) {
                            Log.d(TAG, "[ProcessBmsFile] Skipping unmodified file: " + pathName);
                            return; // 文件未修改，跳过处理
                        } else {
                            Log.i(TAG, "[ProcessBmsFile] File modified, need to update: " + pathName
                                    + " (DB date: " + recordDate + ", File date: " + lastModifiedTime + ")");
                        }
                    } else {
                        // Log.i(TAG, "[ProcessBmsFile] New file, need to add: " + pathName);
                    }
                }
            } else {
                Log.i(TAG, "[ProcessBmsFile] Force refreshing file: " + pathName);
            }

            // 解析 BMS/BMSON 文件
            // Log.d(TAG, "[ProcessBmsFile] Decoding: " + pathName);
            long decodeStart = System.currentTimeMillis();
            BMSModel model = null;
            if (pathName.toLowerCase().endsWith(".bmson")) {
                // bmson 文件
                BMSONDecoder bmsonDecoder = new BMSONDecoder(BMSModel.LNTYPE_LONGNOTE);
                try {
                    model = bmsonDecoder.decode(file);
                } catch (Exception e) {
                    Log.e(TAG, "[ProcessBmsFile] Error decoding bmson: " + pathName, e);
                }
            } else {
                // BMS/BME/BML/PMS 文件
                model = decoder.decode(file);
            }
            long decodeElapsed = System.currentTimeMillis() - decodeStart;

            if (model == null) {
                Log.w(TAG, "[ProcessBmsFile] Failed to parse BMS file (decode returned null): " + file.path());
                return;
            }

            Log.d(TAG, "[ProcessBmsFile] Decoded successfully in " + decodeElapsed + "ms: " + pathName);

            SongData songData = new SongData(model, txt);
            songData.setPath(pathName);

            // 如果没有 preview 且存在 preview 文件，设置为 preview
            if ((songData.getPreview() == null || songData.getPreview().length() == 0) && previewPath != null) {
                songData.setPreview(previewPath);
            }

            // 保持 tag/favorite
            String existingTag = tags.get(songData.getSha256());
            Integer existingFavorite = favorites.get(songData.getSha256());
            songData.setTag(existingTag != null ? existingTag : "");
            songData.setFavorite(existingFavorite != null ? existingFavorite : 0);

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

            // 自动难度检测（对应原版逻辑）
            if (songData.getDifficulty() == 0) {
                String fulltitle = (songData.getTitle() + songData.getSubtitle()).toLowerCase();
                String diffname = songData.getSubtitle().toLowerCase();
                if (diffname.contains("beginner")) {
                    songData.setDifficulty(1);
                } else if (diffname.contains("normal")) {
                    songData.setDifficulty(2);
                } else if (diffname.contains("hyper")) {
                    songData.setDifficulty(3);
                } else if (diffname.contains("another")) {
                    songData.setDifficulty(4);
                } else if (diffname.contains("insane") || diffname.contains("leggendaria")) {
                    songData.setDifficulty(5);
                } else {
                    if (fulltitle.contains("beginner")) {
                        songData.setDifficulty(1);
                    } else if (fulltitle.contains("normal")) {
                        songData.setDifficulty(2);
                    } else if (fulltitle.contains("hyper")) {
                        songData.setDifficulty(3);
                    } else if (fulltitle.contains("another")) {
                        songData.setDifficulty(4);
                    } else if (fulltitle.contains("insane") || fulltitle.contains("leggendaria")) {
                        songData.setDifficulty(5);
                    } else {
                        // 按 notes 数量推断难度
                        int notes = songData.getNotes();
                        if (notes < 250) {
                            songData.setDifficulty(1);
                        } else if (notes < 600) {
                            songData.setDifficulty(2);
                        } else if (notes < 1000) {
                            songData.setDifficulty(3);
                        } else if (notes < 2000) {
                            songData.setDifficulty(4);
                        } else {
                            songData.setDifficulty(5);
                        }
                    }
                }
            }

            // 插件处理
            for (SongDatabaseAccessorPlugin plugin : plugins) {
                plugin.update(model, songData);
            }

            // 更新 SongInformation
            insertInformation(new bms.player.beatoraja.song.SongInformation(model), db);

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
                    updatedCount.incrementAndGet();
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

            if (updatedCount.get() % 100 == 0) {
                Log.i(TAG, "Updated " + updatedCount.get() + " BMS files...");
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
            sd.setTail(getIntSafe(c, "tail"));
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
        private static final int DATABASE_VERSION = 4; // Updated for tail column

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
                    + "tail INTEGER DEFAULT -1, "
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

            // information table - stores BMS analysis (density, BPM, note distribution)
            db.execSQL("CREATE TABLE IF NOT EXISTS information ("
                    + "sha256 TEXT PRIMARY KEY, "
                    + "n INTEGER, "
                    + "ln INTEGER, "
                    + "s INTEGER, "
                    + "ls INTEGER, "
                    + "total REAL, "
                    + "density REAL, "
                    + "peakdensity REAL, "
                    + "enddensity REAL, "
                    + "mainbpm REAL, "
                    + "distribution TEXT, "
                    + "speedchange TEXT, "
                    + "lanenotes TEXT)");
            System.out.println("Table Created: information");
            Log.i(TAG, "Table Created: information");

            Log.i(TAG, "DBHelper onCreate: All tables created successfully");
        }
        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.i(TAG, "Upgrading database from version " + oldVersion + " to " + newVersion);

            // Migrate from version 1 to version 2: Add playcount column to song table
            if (oldVersion < 2) {
                try {
                    // Check if playcount column already exists
                    boolean hasPlaycount = false;
                    try (Cursor cursor = db.rawQuery("PRAGMA table_info(song)", null)) {
                        while (cursor.moveToNext()) {
                            String columnName = getStringSafe(cursor, "name");
                            if ("playcount".equals(columnName)) {
                                hasPlaycount = true;
                                break;
                            }
                        }
                    }

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

            // Version 3: Migrate to sha256 primary key (对应原版的 createTable 迁移逻辑)
            if (oldVersion < 3) {
                try {
                    // 检查 song 表是否有 sha256 列且是主键
                    boolean hasSha256Pk = false;
                    try (Cursor cursor = db.rawQuery("PRAGMA table_info(song)", null)) {
                        while (cursor.moveToNext()) {
                            String columnName = getStringSafe(cursor, "name");
                            int pk = cursor.getInt(cursor.getColumnIndex("pk"));
                            if ("sha256".equals(columnName) && pk == 1) {
                                hasSha256Pk = true;
                                break;
                            }
                        }
                    }

                    if (!hasSha256Pk) {
                        Log.i(TAG, "Migrating song table to sha256 primary key");
                        // 重命名旧表
                        db.execSQL("ALTER TABLE song RENAME TO old_song");
                        // 创建新表（sha256 为主键）
                        db.execSQL("CREATE TABLE song ("
                                + "md5 TEXT, "
                                + "sha256 TEXT PRIMARY KEY, "
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
                                + "charthash TEXT)");
                        // 迁移数据，按 path 分组保留 max(adddate) 的记录
                        db.execSQL("INSERT INTO song SELECT "
                                + "md5, sha256, title, subtitle, genre, artist, subartist, tag, path,"
                                + "folder, stagefile, banner, backbmp, preview, parent, level, difficulty,"
                                + "maxbpm, minbpm, length, mode, judge, feature, content,"
                                + "date, favorite, notes, adddate, playcount, charthash "
                                + "FROM old_song GROUP BY path HAVING MAX(adddate)");
                        // 删除旧表
                        db.execSQL("DROP TABLE old_song");
                        Log.i(TAG, "Migration to sha256 primary key completed");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to migrate to sha256 primary key: " + e.getMessage(), e);
                }
            }

            // Version 4: Add tail column
            if (oldVersion < 4) {
                try {
                    db.execSQL("ALTER TABLE song ADD COLUMN tail INTEGER DEFAULT -1");
                    Log.i(TAG, "Added tail column to song table");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to add tail column: " + e.getMessage(), e);
                }
            }
        }
        @Override
        public void onConfigure(SQLiteDatabase db) {
            super.onConfigure(db);
            // 启用 WAL 模式：允许读写并发，彻底消除 SQLITE_BUSY。
            db.enableWriteAheadLogging();
        }

        @Override
        public void onOpen(SQLiteDatabase db) {
            super.onOpen(db);
            try {
                if (!db.isReadOnly()) {
                    db.execSQL("PRAGMA busy_timeout = 15000");
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to set busy_timeout on " + db.getPath() + ": " + t.getMessage());
            }
        }
    }

    /**
     * 插件接口 - 对应原版 SongDatabaseAccessorPlugin
     * 允许在解析BMS模型后、自定义字段更新前拦截并修改SongData
     */
    public interface SongDatabaseAccessorPlugin {
        void update(BMSModel model, SongData song);
    }
}

