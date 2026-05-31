package bms.player.beatoraja.score;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

/**
 * Unified SQLiteOpenHelper for score.db.
 * Manages all score-related tables: info, player, score, scorelog, scoredatalog.
 */
public class ScoreDBOpenHelper extends SQLiteOpenHelper {

    private static final String TAG = "ScoreDBOpenHelper";
    private static final int DATABASE_VERSION = 1;

    public ScoreDBOpenHelper(Context context, String dbPath) {
        super(context, dbPath, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        Log.i(TAG, "Creating unified score database tables...");

        db.execSQL("CREATE TABLE IF NOT EXISTS info ("
                + "id TEXT NOT NULL PRIMARY KEY, "
                + "name TEXT NOT NULL, "
                + "rank TEXT)");

        db.execSQL("CREATE TABLE IF NOT EXISTS player ("
                + "date INTEGER NOT NULL PRIMARY KEY, "
                + "playcount INTEGER DEFAULT 0, "
                + "clear INTEGER DEFAULT 0, "
                + "epg INTEGER DEFAULT 0, lpg INTEGER DEFAULT 0, "
                + "egr INTEGER DEFAULT 0, lgr INTEGER DEFAULT 0, "
                + "egd INTEGER DEFAULT 0, lgd INTEGER DEFAULT 0, "
                + "ebd INTEGER DEFAULT 0, lbd INTEGER DEFAULT 0, "
                + "epr INTEGER DEFAULT 0, lpr INTEGER DEFAULT 0, "
                + "ems INTEGER DEFAULT 0, lms INTEGER DEFAULT 0, "
                + "playtime INTEGER DEFAULT 0, "
                + "maxcombo INTEGER DEFAULT 0)");

        db.execSQL("CREATE TABLE IF NOT EXISTS score ("
                + "sha256 TEXT NOT NULL, "
                + "mode INTEGER NOT NULL, "
                + "clear INTEGER DEFAULT 0, "
                + "epg INTEGER DEFAULT 0, lpg INTEGER DEFAULT 0, "
                + "egr INTEGER DEFAULT 0, lgr INTEGER DEFAULT 0, "
                + "egd INTEGER DEFAULT 0, lgd INTEGER DEFAULT 0, "
                + "ebd INTEGER DEFAULT 0, lbd INTEGER DEFAULT 0, "
                + "epr INTEGER DEFAULT 0, lpr INTEGER DEFAULT 0, "
                + "ems INTEGER DEFAULT 0, lms INTEGER DEFAULT 0, "
                + "notes INTEGER DEFAULT 0, "
                + "combo INTEGER DEFAULT 0, "
                + "minbp INTEGER DEFAULT 2147483647, "
                + "avgjudge INTEGER NOT NULL DEFAULT 2147483647, "
                + "playcount INTEGER DEFAULT 0, "
                + "clearcount INTEGER DEFAULT 0, "
                + "trophy TEXT, "
                + "ghost TEXT, "
                + "option INTEGER DEFAULT 0, "
                + "seed INTEGER DEFAULT -1, "
                + "random INTEGER DEFAULT 0, "
                + "date INTEGER DEFAULT 0, "
                + "state INTEGER DEFAULT 0, "
                + "scorehash TEXT, "
                + "PRIMARY KEY (sha256, mode))");

        db.execSQL("CREATE TABLE IF NOT EXISTS scorelog ("
                + "sha256 TEXT NOT NULL, "
                + "mode INTEGER DEFAULT 0, "
                + "clear INTEGER DEFAULT 0, "
                + "oldclear INTEGER DEFAULT 0, "
                + "score INTEGER DEFAULT 0, "
                + "oldscore INTEGER DEFAULT 0, "
                + "combo INTEGER DEFAULT 0, "
                + "oldcombo INTEGER DEFAULT 0, "
                + "minbp INTEGER DEFAULT 0, "
                + "oldminbp INTEGER DEFAULT 0, "
                + "date INTEGER DEFAULT 0)");

        db.execSQL("CREATE TABLE IF NOT EXISTS scoredatalog ("
                + "sha256 TEXT NOT NULL, "
                + "mode INTEGER NOT NULL, "
                + "clear INTEGER DEFAULT 0, "
                + "epg INTEGER DEFAULT 0, lpg INTEGER DEFAULT 0, "
                + "egr INTEGER DEFAULT 0, lgr INTEGER DEFAULT 0, "
                + "egd INTEGER DEFAULT 0, lgd INTEGER DEFAULT 0, "
                + "ebd INTEGER DEFAULT 0, lbd INTEGER DEFAULT 0, "
                + "epr INTEGER DEFAULT 0, lpr INTEGER DEFAULT 0, "
                + "ems INTEGER DEFAULT 0, lms INTEGER DEFAULT 0, "
                + "notes INTEGER DEFAULT 0, "
                + "combo INTEGER DEFAULT 0, "
                + "minbp INTEGER DEFAULT 2147483647, "
                + "avgjudge INTEGER NOT NULL DEFAULT 2147483647, "
                + "playcount INTEGER DEFAULT 0, "
                + "clearcount INTEGER DEFAULT 0, "
                + "trophy TEXT, "
                + "ghost TEXT, "
                + "option INTEGER DEFAULT 0, "
                + "seed INTEGER DEFAULT -1, "
                + "random INTEGER DEFAULT 0, "
                + "date INTEGER DEFAULT 0, "
                + "state INTEGER DEFAULT 0, "
                + "scorehash TEXT, "
                + "PRIMARY KEY (sha256, mode))");

        Log.i(TAG, "All unified score tables created successfully");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.i(TAG, "Upgrading score database from " + oldVersion + " to " + newVersion);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.enableWriteAheadLogging();
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        try {
            if (!db.isReadOnly()) {
                db.execSQL("PRAGMA busy_timeout = 5000");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to set busy_timeout: " + t.getMessage());
        }
    }

    // ---- Cursor helper methods ----

    static String getString(Cursor c, String col) {
        int idx = c.getColumnIndex(col);
        if (idx == -1) return "";
        String v = c.getString(idx);
        return v == null ? "" : v;
    }

    static int getInt(Cursor c, String col) {
        int idx = c.getColumnIndex(col);
        if (idx == -1) return 0;
        return c.getInt(idx);
    }

    static long getLong(Cursor c, String col) {
        int idx = c.getColumnIndex(col);
        if (idx == -1) return 0;
        return c.getLong(idx);
    }

    static int getInt(Cursor c, String col, int defaultVal) {
        int idx = c.getColumnIndex(col);
        if (idx == -1) return defaultVal;
        return c.getInt(idx);
    }

    static long getLong(Cursor c, String col, long defaultVal) {
        int idx = c.getColumnIndex(col);
        if (idx == -1) return defaultVal;
        return c.getLong(idx);
    }
}
