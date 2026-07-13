package bms.player.beatoraja.rating;

public class MatchedScore {
    public final String md5;
    public final int lampOrdinal;
    public final RecommendationModelData.ChartEntry modelEntry;

    public MatchedScore(String md5, int lampOrdinal, RecommendationModelData.ChartEntry modelEntry) {
        this.md5 = md5;
        this.lampOrdinal = lampOrdinal;
        this.modelEntry = modelEntry;
    }

    public int getLampOrdinal() {
        return lampOrdinal;
    }
}
