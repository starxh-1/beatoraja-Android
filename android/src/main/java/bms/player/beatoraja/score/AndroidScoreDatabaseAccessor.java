package bms.player.beatoraja.score;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.logging.Logger;

import bms.player.beatoraja.*;
import bms.player.beatoraja.song.SongData;

/**
 * Android-native SQLite implementation of ScoreDatabaseAccessor.
 * Replaces the JDBC/SQLDroid approach, using a single score.db with all tables.
 */
public class AndroidScoreDatabaseAccessor extends ScoreDatabaseAccessor {

    private static final String TAG = "AndroidScoreDBAccessor";
    private final ScoreDBOpenHelper helper;
    private final String dbDir;

    public AndroidScoreDatabaseAccessor(Context context, String dbPath) {
        this.helper = new ScoreDBOpenHelper(context.getApplicationContext(), dbPath);
        String parent = new File(dbPath).getParent();
        this.dbDir = (parent != null) ? parent : "";
        migrateFromOldFiles();
    }

    // ---- Migration from old 3-file system ----

    private void migrateFromOldFiles() {
        if (dbDir.isEmpty()) return;
        File scoreLogOld = new File(dbDir, "scorelog.db");
        File scoreDataLogOld = new File(dbDir, "scoredatalog.db");

        if (!scoreLogOld.exists() && !scoreDataLogOld.exists()) return;

        Log.i(TAG, "Migrating old score database files in " + dbDir);
        SQLiteDatabase db = helper.getWritableDatabase();

        // Migrate scorelog.db -> scorelog table
        if (scoreLogOld.exists()) {
            try (SQLiteDatabase oldDb = SQLiteDatabase.openDatabase(
                    scoreLogOld.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY)) {
                try (Cursor c = oldDb.rawQuery("SELECT * FROM scorelog", null)) {
                    db.beginTransaction();
                    try {
                        while (c.moveToNext()) {
                            ContentValues cv = new ContentValues();
                            cv.put("sha256", ScoreDBOpenHelper.getString(c, "sha256"));
                            cv.put("mode", ScoreDBOpenHelper.getInt(c, "mode"));
                            cv.put("clear", ScoreDBOpenHelper.getInt(c, "clear"));
                            cv.put("oldclear", ScoreDBOpenHelper.getInt(c, "oldclear"));
                            cv.put("score", ScoreDBOpenHelper.getInt(c, "score"));
                            cv.put("oldscore", ScoreDBOpenHelper.getInt(c, "oldscore"));
                            cv.put("combo", ScoreDBOpenHelper.getInt(c, "combo"));
                            cv.put("oldcombo", ScoreDBOpenHelper.getInt(c, "oldcombo"));
                            cv.put("minbp", ScoreDBOpenHelper.getInt(c, "minbp"));
                            cv.put("oldminbp", ScoreDBOpenHelper.getInt(c, "oldminbp"));
                            cv.put("date", ScoreDBOpenHelper.getLong(c, "date"));
                            db.insertWithOnConflict("scorelog", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
                        }
                        db.setTransactionSuccessful();
                        Log.i(TAG, "Migrated " + c.getCount() + " rows from scorelog.db");
                    } finally {
                        db.endTransaction();
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to migrate scorelog.db: " + t.getMessage());
            }
            // Rename old file
            File bak = new File(dbDir, "scorelog.db.bak");
            bak.delete();
            scoreLogOld.renameTo(bak);
        }

        // Migrate scoredatalog.db -> scoredatalog table
        if (scoreDataLogOld.exists()) {
            try (SQLiteDatabase oldDb = SQLiteDatabase.openDatabase(
                    scoreDataLogOld.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY)) {
                try (Cursor c = oldDb.rawQuery("SELECT * FROM scoredatalog", null)) {
                    db.beginTransaction();
                    try {
                        while (c.moveToNext()) {
                            ContentValues cv = scoreDataToContentValues(c);
                            db.insertWithOnConflict("scoredatalog", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
                        }
                        db.setTransactionSuccessful();
                        Log.i(TAG, "Migrated rows from scoredatalog.db");
                    } finally {
                        db.endTransaction();
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to migrate scoredatalog.db: " + t.getMessage());
            }
            File bak = new File(dbDir, "scoredatalog.db.bak");
            bak.delete();
            scoreDataLogOld.renameTo(bak);
        }
    }

    // ---- ScoreDatabaseAccessor implementation ----

    @Override
    public void createTable() {
        // Tables are created automatically by SQLiteOpenHelper.
        // Ensure a default player row exists.
        SQLiteDatabase db = helper.getWritableDatabase();
        try {
            if (getPlayerDatas(1).length == 0) {
                setPlayerData(new PlayerData());
            }
        } catch (Exception e) {
            Logger.getGlobal().severe("Score DB init error: " + e.getMessage());
        }
    }

    @Override
    public PlayerInformation getInformation() {
        SQLiteDatabase db = helper.getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT * FROM info", null)) {
            if (c.moveToFirst()) {
                PlayerInformation info = new PlayerInformation();
                info.setId(ScoreDBOpenHelper.getString(c, "id"));
                info.setName(ScoreDBOpenHelper.getString(c, "name"));
                info.setRank(ScoreDBOpenHelper.getString(c, "rank"));
                return info;
            }
        }
        return null;
    }

    @Override
    public void setInformation(PlayerInformation info) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("info", null, null);
            ContentValues cv = new ContentValues();
            cv.put("id", info.getId());
            cv.put("name", info.getName());
            cv.put("rank", info.getRank());
            db.insertWithOnConflict("info", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public ScoreData getScoreData(String hash, int mode) {
        SQLiteDatabase db = helper.getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT * FROM score WHERE sha256 = ? AND mode = ?",
                new String[]{hash, String.valueOf(mode)})) {
            ScoreData best = null;
            while (c.moveToNext()) {
                ScoreData s = cursorToScoreData(c);
                if (s.validate() && (best == null || s.getClear() > best.getClear())) {
                    best = s;
                }
            }
            return best;
        } catch (Exception e) {
            Logger.getGlobal().severe("getScoreData error: " + e.getMessage());
        }
        return null;
    }

    @Override
    public void getScoreDatas(ScoreDataCollector collector, SongData[] songs, int mode) {
        // LN mode: collect for specified mode and mode 0 separately
        getScoreDatasInternal(collector, songs, mode, true);
        getScoreDatasInternal(collector, songs, 0, false);
    }

    private void getScoreDatasInternal(ScoreDataCollector collector, SongData[] songs, int mode, boolean hasln) {
        SQLiteDatabase db = helper.getReadableDatabase();
        List<String> matchingHashes = new ArrayList<>();
        for (SongData song : songs) {
            if ((hasln && song.hasUndefinedLongNote()) || (!hasln && !song.hasUndefinedLongNote())) {
                matchingHashes.add(song.getSha256());
            }
        }
        if (matchingHashes.isEmpty()) return;

        // Build parameterized IN clause
        StringBuilder placeholders = new StringBuilder();
        String[] args = new String[matchingHashes.size() + 1];
        for (int i = 0; i < matchingHashes.size(); i++) {
            if (i > 0) placeholders.append(',');
            placeholders.append('?');
            args[i] = matchingHashes.get(i);
        }
        args[matchingHashes.size()] = String.valueOf(mode);

        List<ScoreData> scores = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT * FROM score WHERE sha256 IN (" + placeholders + ") AND mode = ?",
                args)) {
            while (c.moveToNext()) {
                ScoreData s = cursorToScoreData(c);
                if (s.validate()) scores.add(s);
            }
        } catch (Exception e) {
            Logger.getGlobal().severe("getScoreDatas error: " + e.getMessage());
        }

        for (SongData song : songs) {
            if ((hasln && song.hasUndefinedLongNote()) || (!hasln && !song.hasUndefinedLongNote())) {
                boolean found = false;
                for (ScoreData score : scores) {
                    if (song.getSha256().equals(score.getSha256())) {
                        collector.collect(song, score);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    collector.collect(song, null);
                }
            }
        }
    }

    @Override
    public List<ScoreData> getScoreDatas(String sql) {
        // Use a fresh OPEN_READONLY connection to avoid stale WAL snapshots
        // from the cached helper connection. This ensures Walkure and other
        // readers always see the latest committed writes.
        String dbPath = helper.getDatabaseName();
        SQLiteDatabase db = SQLiteDatabase.openDatabase(dbPath, null,
                SQLiteDatabase.OPEN_READONLY);
        List<ScoreData> result = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT * FROM score WHERE " + sql, null)) {
            while (c.moveToNext()) {
                ScoreData s = cursorToScoreData(c);
                if (s.validate()) result.add(s);
            }
        } catch (Exception e) {
            Logger.getGlobal().severe("getScoreDatas(sql) error: " + e.getMessage());
        } finally {
            try { db.close(); } catch (Exception ignored) {}
        }
        return result;
    }

    @Override
    public void setScoreData(ScoreData score) {
        setScoreData(new ScoreData[]{score});
    }

    @Override
    public void setScoreData(ScoreData[] scores) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            for (ScoreData score : scores) {
                ContentValues cv = scoreDataToContentValues(score);
                db.insertWithOnConflict("score", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public void setScoreData(Map<String, Map<String, Object>> map) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            for (Map.Entry<String, Map<String, Object>> entry : map.entrySet()) {
                String hash = entry.getKey();
                ContentValues cv = new ContentValues();
                for (Map.Entry<String, Object> kv : entry.getValue().entrySet()) {
                    putValue(cv, kv.getKey(), kv.getValue());
                }
                db.update("score", cv, "sha256 = ?", new String[]{hash});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private void putValue(ContentValues cv, String key, Object value) {
        if (value instanceof Integer) cv.put(key, (Integer) value);
        else if (value instanceof Long) cv.put(key, (Long) value);
        else if (value instanceof String) cv.put(key, (String) value);
        else if (value instanceof Float) cv.put(key, (Float) value);
        else if (value instanceof Double) cv.put(key, (Double) value);
        else if (value instanceof Boolean) cv.put(key, (Boolean) value);
        else if (value != null) cv.put(key, value.toString());
    }

    @Override
    public void deleteScoreData(String sha256, int mode) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.delete("score", "sha256 = ? AND mode = ?",
                new String[]{sha256, String.valueOf(mode)});
    }

    @Override
    public PlayerData getPlayerData() {
        PlayerData[] pd = getPlayerDatas(1);
        return pd.length > 0 ? pd[0] : null;
    }

    @Override
    public PlayerData[] getPlayerDatas(int count) {
        SQLiteDatabase db = helper.getReadableDatabase();
        String limit = count > 0 ? " LIMIT " + count : "";
        List<PlayerData> list = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT * FROM player ORDER BY date DESC" + limit, null)) {
            while (c.moveToNext()) {
                PlayerData pd = cursorToPlayerData(c);
                if (pd.validate()) list.add(pd);
            }
        } catch (Exception e) {
            Logger.getGlobal().severe("getPlayerDatas error: " + e.getMessage());
        }
        return list.toArray(new PlayerData[0]);
    }

    @Override
    public void setPlayerData(PlayerData pd) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            Calendar cal = Calendar.getInstance(TimeZone.getDefault());
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            pd.setDate(cal.getTimeInMillis() / 1000L);
            ContentValues cv = playerDataToContentValues(pd);
            db.insertWithOnConflict("player", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ---- ScoreLog (merged from ScoreLogDatabaseAccessor) ----

    @Override
    public void setScoreLog(ScoreLog log) {
        if (log == null || !log.validate()) return;
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("sha256", log.getSha256());
        cv.put("mode", log.getMode());
        cv.put("clear", log.getClear());
        cv.put("oldclear", log.getOldclear());
        cv.put("score", log.getScore());
        cv.put("oldscore", log.getOldscore());
        cv.put("combo", log.getCombo());
        cv.put("oldcombo", log.getOldcombo());
        cv.put("minbp", log.getMinbp());
        cv.put("oldminbp", log.getOldminbp());
        cv.put("date", log.getDate());
        db.insertWithOnConflict("scorelog", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
    }

    // ---- ScoreDataLog (merged from ScoreDataLogDatabaseAccessor) ----

    @Override
    public void setScoreDataLog(ScoreData score) {
        setScoreDataLog(new ScoreData[]{score});
    }

    @Override
    public void setScoreDataLog(ScoreData[] scores) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            for (ScoreData score : scores) {
                ContentValues cv = scoreDataToContentValues(score);
                db.insertWithOnConflict("scoredatalog", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public void close() {
        helper.close();
    }

    // ---- Cursor to entity mapping ----

    private ScoreData cursorToScoreData(Cursor c) {
        ScoreData sd = new ScoreData();
        sd.setSha256(ScoreDBOpenHelper.getString(c, "sha256"));
        sd.setMode(ScoreDBOpenHelper.getInt(c, "mode"));
        sd.setClear(ScoreDBOpenHelper.getInt(c, "clear"));
        sd.setEpg(ScoreDBOpenHelper.getInt(c, "epg"));
        sd.setLpg(ScoreDBOpenHelper.getInt(c, "lpg"));
        sd.setEgr(ScoreDBOpenHelper.getInt(c, "egr"));
        sd.setLgr(ScoreDBOpenHelper.getInt(c, "lgr"));
        sd.setEgd(ScoreDBOpenHelper.getInt(c, "egd"));
        sd.setLgd(ScoreDBOpenHelper.getInt(c, "lgd"));
        sd.setEbd(ScoreDBOpenHelper.getInt(c, "ebd"));
        sd.setLbd(ScoreDBOpenHelper.getInt(c, "lbd"));
        sd.setEpr(ScoreDBOpenHelper.getInt(c, "epr"));
        sd.setLpr(ScoreDBOpenHelper.getInt(c, "lpr"));
        sd.setEms(ScoreDBOpenHelper.getInt(c, "ems"));
        sd.setLms(ScoreDBOpenHelper.getInt(c, "lms"));
        sd.setNotes(ScoreDBOpenHelper.getInt(c, "notes"));
        sd.setCombo(ScoreDBOpenHelper.getInt(c, "combo"));
        sd.setMinbp(ScoreDBOpenHelper.getInt(c, "minbp"));
        sd.setAvgjudge(ScoreDBOpenHelper.getLong(c, "avgjudge", Long.MAX_VALUE));
        sd.setPlaycount(ScoreDBOpenHelper.getInt(c, "playcount"));
        sd.setClearcount(ScoreDBOpenHelper.getInt(c, "clearcount"));
        sd.setTrophy(ScoreDBOpenHelper.getString(c, "trophy"));
        sd.setGhost(ScoreDBOpenHelper.getString(c, "ghost"));
        sd.setOption(ScoreDBOpenHelper.getInt(c, "option"));
        sd.setSeed(ScoreDBOpenHelper.getLong(c, "seed", -1));
        sd.setRandom(ScoreDBOpenHelper.getInt(c, "random"));
        sd.setDate(ScoreDBOpenHelper.getLong(c, "date"));
        sd.setState(ScoreDBOpenHelper.getInt(c, "state"));
        sd.setScorehash(ScoreDBOpenHelper.getString(c, "scorehash"));
        return sd;
    }

    private PlayerData cursorToPlayerData(Cursor c) {
        PlayerData pd = new PlayerData();
        pd.setDate(ScoreDBOpenHelper.getLong(c, "date"));
        pd.setPlaycount(ScoreDBOpenHelper.getLong(c, "playcount"));
        pd.setClear(ScoreDBOpenHelper.getLong(c, "clear"));
        pd.setEpg(ScoreDBOpenHelper.getLong(c, "epg"));
        pd.setLpg(ScoreDBOpenHelper.getLong(c, "lpg"));
        pd.setEgr(ScoreDBOpenHelper.getLong(c, "egr"));
        pd.setLgr(ScoreDBOpenHelper.getLong(c, "lgr"));
        pd.setEgd(ScoreDBOpenHelper.getLong(c, "egd"));
        pd.setLgd(ScoreDBOpenHelper.getLong(c, "lgd"));
        pd.setEbd(ScoreDBOpenHelper.getLong(c, "ebd"));
        pd.setLbd(ScoreDBOpenHelper.getLong(c, "lbd"));
        pd.setEpr(ScoreDBOpenHelper.getLong(c, "epr"));
        pd.setLpr(ScoreDBOpenHelper.getLong(c, "lpr"));
        pd.setEms(ScoreDBOpenHelper.getLong(c, "ems"));
        pd.setLms(ScoreDBOpenHelper.getLong(c, "lms"));
        pd.setPlaytime(ScoreDBOpenHelper.getLong(c, "playtime"));
        pd.setMaxcombo(ScoreDBOpenHelper.getLong(c, "maxcombo"));
        return pd;
    }

    // ---- ContentValues builders ----

    private ContentValues scoreDataToContentValues(ScoreData sd) {
        ContentValues cv = new ContentValues();
        cv.put("sha256", sd.getSha256());
        cv.put("mode", sd.getMode());
        cv.put("clear", sd.getClear());
        cv.put("epg", sd.getEpg());
        cv.put("lpg", sd.getLpg());
        cv.put("egr", sd.getEgr());
        cv.put("lgr", sd.getLgr());
        cv.put("egd", sd.getEgd());
        cv.put("lgd", sd.getLgd());
        cv.put("ebd", sd.getEbd());
        cv.put("lbd", sd.getLbd());
        cv.put("epr", sd.getEpr());
        cv.put("lpr", sd.getLpr());
        cv.put("ems", sd.getEms());
        cv.put("lms", sd.getLms());
        cv.put("notes", sd.getNotes());
        cv.put("combo", sd.getCombo());
        cv.put("minbp", sd.getMinbp());
        cv.put("avgjudge", sd.getAvgjudge());
        cv.put("playcount", sd.getPlaycount());
        cv.put("clearcount", sd.getClearcount());
        cv.put("trophy", sd.getTrophy());
        cv.put("ghost", sd.getGhost());
        cv.put("option", sd.getOption());
        cv.put("seed", sd.getSeed());
        cv.put("random", sd.getRandom());
        cv.put("date", sd.getDate());
        cv.put("state", sd.getState());
        cv.put("scorehash", sd.getScorehash());
        return cv;
    }

    private ContentValues playerDataToContentValues(PlayerData pd) {
        ContentValues cv = new ContentValues();
        cv.put("date", pd.getDate());
        cv.put("playcount", pd.getPlaycount());
        cv.put("clear", pd.getClear());
        cv.put("epg", pd.getEpg());
        cv.put("lpg", pd.getLpg());
        cv.put("egr", pd.getEgr());
        cv.put("lgr", pd.getLgr());
        cv.put("egd", pd.getEgd());
        cv.put("lgd", pd.getLgd());
        cv.put("ebd", pd.getEbd());
        cv.put("lbd", pd.getLbd());
        cv.put("epr", pd.getEpr());
        cv.put("lpr", pd.getLpr());
        cv.put("ems", pd.getEms());
        cv.put("lms", pd.getLms());
        cv.put("playtime", pd.getPlaytime());
        cv.put("maxcombo", pd.getMaxcombo());
        return cv;
    }

    private ContentValues scoreDataToContentValues(Cursor c) {
        ContentValues cv = new ContentValues();
        String[] columns = {"sha256", "mode", "clear", "epg", "lpg", "egr", "lgr",
                "egd", "lgd", "ebd", "lbd", "epr", "lpr", "ems", "lms",
                "notes", "combo", "minbp", "avgjudge", "playcount", "clearcount",
                "trophy", "ghost", "option", "seed", "random", "date", "state", "scorehash"};
        for (String col : columns) {
            int idx = c.getColumnIndex(col);
            if (idx == -1) continue;
            int type = c.getType(idx);
            switch (type) {
                case Cursor.FIELD_TYPE_INTEGER:
                    cv.put(col, c.getLong(idx));
                    break;
                case Cursor.FIELD_TYPE_FLOAT:
                    cv.put(col, c.getDouble(idx));
                    break;
                case Cursor.FIELD_TYPE_STRING:
                    cv.put(col, c.getString(idx));
                    break;
                case Cursor.FIELD_TYPE_NULL:
                    break;
                default:
                    cv.put(col, c.getString(idx));
                    break;
            }
        }
        return cv;
    }
}
