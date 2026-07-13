package bms.player.beatoraja.rating;

import java.util.Map;

public class RecommendationModelData {

    public static class ChartEntry {
        public String entryType;
        public String name;
        public String md5;
        public Map<String, String> difficultyTableLevels;
        public double chartDiscrimination;
        public Map<String, Double> clearDifficulty;
        public Map<String, Double> clearDifficultyStarRatings;
    }

    public Map<String, ChartEntry> entriesByMd5;
    public double[] starRatingMapping;

    public RecommendationModelData(Map<String, ChartEntry> entries, double[] starMapping) {
        this.entriesByMd5 = entries;
        this.starRatingMapping = starMapping;
    }
}
