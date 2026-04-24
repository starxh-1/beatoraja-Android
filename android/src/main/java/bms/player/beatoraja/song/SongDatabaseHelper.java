package bms.player.beatoraja.song;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

/**
 * Android native SQLite helper for song database
 */
public class SongDatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "songdata.db";
    private static final int DATABASE_VERSION = 1;

    public SongDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // Create tables if needed, but assume they are created by the main accessor
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Handle upgrades if needed
    }

    public List<SongData> getSongDatas(String key, String value) {
        List<SongData> songs = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.query("song", null, key + " = ?", new String[]{value}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    SongData song = new SongData();
                    // Populate SongData from cursor
                    song.setMd5(cursor.getString(cursor.getColumnIndex("md5")));
                    song.setSha256(cursor.getString(cursor.getColumnIndex("sha256")));
                    song.setTitle(cursor.getString(cursor.getColumnIndex("title")));
                    song.setSubtitle(cursor.getString(cursor.getColumnIndex("subtitle")));
                    song.setGenre(cursor.getString(cursor.getColumnIndex("genre")));
                    song.setArtist(cursor.getString(cursor.getColumnIndex("artist")));
                    song.setSubartist(cursor.getString(cursor.getColumnIndex("subartist")));
                    song.setPath(cursor.getString(cursor.getColumnIndex("path")));
                    song.setFolder(cursor.getString(cursor.getColumnIndex("folder")));
                    song.setLevel(cursor.getInt(cursor.getColumnIndex("level")));
                    song.setDifficulty(cursor.getInt(cursor.getColumnIndex("difficulty")));
                    song.setMode(cursor.getInt(cursor.getColumnIndex("mode")));
                    song.setNotes(cursor.getInt(cursor.getColumnIndex("notes")));
                    // Add other fields as needed
                    songs.add(song);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (cursor != null) cursor.close();
            db.close();
        }
        return songs;
    }

    // Add other methods as needed, like getSongDatas(String[] hashes), etc.
}
