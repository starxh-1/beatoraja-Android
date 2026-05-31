package bms.player.beatoraja;

import java.util.*;

import bms.player.beatoraja.song.SongData;

/**
 * Abstract score database accessor.
 * Replaces the JDBC-based implementation with a factory pattern
 * so the Android module can provide a native SQLite implementation.
 */
public abstract class ScoreDatabaseAccessor {

    // ---- Factory ----

    private static Factory factory;

    public interface Factory {
        ScoreDatabaseAccessor create(String path);
    }

    public static void setFactory(Factory f) {
        factory = f;
    }

    public static boolean hasFactory() {
        return factory != null;
    }

    public static ScoreDatabaseAccessor create(String path) {
        if (factory == null) {
            throw new IllegalStateException("ScoreDatabaseAccessor.Factory not set. Call setFactory() first.");
        }
        return factory.create(path);
    }

    // ---- Public API ----

    public abstract void createTable();

    public abstract PlayerInformation getInformation();

    public abstract void setInformation(PlayerInformation info);

    public abstract ScoreData getScoreData(String hash, int mode);

    public abstract void getScoreDatas(ScoreDataCollector collector, SongData[] songs, int mode);

    public abstract List<ScoreData> getScoreDatas(String sql);

    public void setScoreData(ScoreData score) {
        setScoreData(new ScoreData[]{score});
    }

    public abstract void setScoreData(ScoreData[] scores);

    public abstract void setScoreData(Map<String, Map<String, Object>> map);

    public abstract void deleteScoreData(String sha256, int mode);

    public abstract PlayerData getPlayerData();

    public abstract PlayerData[] getPlayerDatas(int count);

    public abstract void setPlayerData(PlayerData pd);

    // ---- Score log (merged from ScoreLogDatabaseAccessor) ----

    public abstract void setScoreLog(ScoreLog log);

    // ---- Score data log (merged from ScoreDataLogDatabaseAccessor) ----

    public void setScoreDataLog(ScoreData score) {
        setScoreDataLog(new ScoreData[]{score});
    }

    public abstract void setScoreDataLog(ScoreData[] scores);

    public void close() {}

    // ---- Inner types ----

    public interface ScoreDataCollector {
        void collect(SongData hash, ScoreData score);
    }

    /**
     * Score change log entry (moved from ScoreLogDatabaseAccessor).
     */
    public static class ScoreLog implements Validatable {

        private String sha256;
        private int mode;
        private int clear;
        private int oldclear;
        private int score;
        private int oldscore;
        private int combo;
        private int oldcombo;
        private int minbp;
        private int oldminbp;
        private long date;

        public String getSha256() { return sha256; }
        public void setSha256(String sha256) { this.sha256 = sha256; }
        public int getMode() { return mode; }
        public void setMode(int mode) { this.mode = mode; }
        public int getClear() { return clear; }
        public void setClear(int clear) { this.clear = clear; }
        public int getOldclear() { return oldclear; }
        public void setOldclear(int oldclear) { this.oldclear = oldclear; }
        public int getScore() { return score; }
        public void setScore(int score) { this.score = score; }
        public int getOldscore() { return oldscore; }
        public void setOldscore(int oldscore) { this.oldscore = oldscore; }
        public int getCombo() { return combo; }
        public void setCombo(int combo) { this.combo = combo; }
        public int getOldcombo() { return oldcombo; }
        public void setOldcombo(int oldcombo) { this.oldcombo = oldcombo; }
        public int getMinbp() { return minbp; }
        public void setMinbp(int minbp) { this.minbp = minbp; }
        public int getOldminbp() { return oldminbp; }
        public void setOldminbp(int oldminbp) { this.oldminbp = oldminbp; }
        public long getDate() { return date; }
        public void setDate(long date) { this.date = date; }

        @Override
        public boolean validate() {
            return mode >= 0 && clear >= 0 && clear <= ClearType.Max.id
                    && oldclear >= 0 && oldclear <= clear
                    && score >= 0 && oldscore <= score
                    && combo >= 0 && oldcombo <= combo
                    && minbp >= 0 && oldminbp >= minbp && date >= 0;
        }
    }
}
