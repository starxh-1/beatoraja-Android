package bms.player.beatoraja.rating;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.Gdx;

import bms.player.beatoraja.MainController;
import bms.player.beatoraja.ScoreDatabaseAccessor;
import bms.player.beatoraja.ScoreData;
import bms.player.beatoraja.song.SongData;
import bms.player.beatoraja.song.SongDatabaseAccessor;

public class PlayerRatingService {

    private RecommendationModelData model;
    private boolean loadAttempted = false;
    private static final double[] STAR_POINTS;
    static {
        STAR_POINTS = new double[25];
        for (int i = 0; i < 25; i++) STAR_POINTS[i] = i + 1;
    }

    public void loadModel() {
        if (loadAttempted) return;
        loadAttempted = true;
        try {
            InputStream entriesStream = Gdx.files.internal("walkure/recommendation-model-entries.json").read();
            InputStream mappingStream = Gdx.files.internal("walkure/recommendation-star-rating-mapping.json").read();
            model = RecommendationModelLoader.load(entriesStream, mappingStream);
            entriesStream.close();
            mappingStream.close();
            Gdx.app.log("PlayerRating", "Model loaded: " + model.entriesByMd5.size() + " entries");
        } catch (Exception e) {
            Gdx.app.log("PlayerRating", "Failed to load model: " + e.getMessage());
        }
    }

    public String computeRating(MainController controller) {
        loadModel();
        if (model == null) {
            return errorJson("Failed to load recommendation model data");
        }

        SongDatabaseAccessor songDb = controller.getSongDatabase();
        ScoreDatabaseAccessor scoreDb = null;
        try {
            // 1. Build sha256 -> md5 mapping from song database
            SongData[] allSongs = songDb.getSongDatas();
            Map<String, String> sha256ToMd5 = new HashMap<>();
            for (SongData song : allSongs) {
                String sha256 = song.getSha256();
                String md5 = song.getMd5();
                if (sha256 != null && !sha256.isEmpty() && md5 != null && !md5.isEmpty()) {
                    sha256ToMd5.put(sha256.toLowerCase(), md5.toLowerCase());
                }
            }
            Gdx.app.log("PlayerRating", "Song mapping: " + sha256ToMd5.size() + " entries");

            // 2. Read scores from score.db
            String playerName = controller.getConfig().getPlayername();
            String scoreDbPath = controller.getConfig().getPlayerpath()
                + java.io.File.separatorChar + playerName
                + java.io.File.separatorChar + "score.db";

            scoreDb = ScoreDatabaseAccessor.create(scoreDbPath);
            List<ScoreData> allScores = scoreDb.getScoreDatas("1=1");

            // 3. Build md5 -> best clear lamp mapping (from player's actual scores)
            Map<String, Integer> playerLampByMd5 = new HashMap<>();
            for (ScoreData sd : allScores) {
                String sha256 = sd.getSha256();
                String md5 = sha256ToMd5.get(sha256.toLowerCase());
                if (md5 == null) continue;
                int lampOrd = ClearLampMapper.beatorajaClearToOrdinal(sd.getClear());
                if (lampOrd <= 0) continue;
                Integer existing = playerLampByMd5.get(md5);
                if (existing == null || lampOrd > existing) {
                    playerLampByMd5.put(md5, lampOrd);
                }
            }
            Gdx.app.log("PlayerRating", "Scores matched to model md5s: " + playerLampByMd5.size());

            // 4. Build matched scores for rating estimation (only charts player has played)
            List<MatchedScore> matchedScores = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : playerLampByMd5.entrySet()) {
                RecommendationModelData.ChartEntry modelEntry = model.entriesByMd5.get(entry.getKey());
                if (modelEntry != null) {
                    matchedScores.add(new MatchedScore(entry.getKey(), entry.getValue(), modelEntry));
                }
            }
            Gdx.app.log("PlayerRating", "Model-matched: " + matchedScores.size());

            // 4b. Pass all matched scores to the MLE — including failed (lampOrdinal=1).
            // Upstream walkure-offline (player-rating.js:46-62) treats every score
            // as an ordinal-1..5 observation with categoryThresholds = [-Inf, easy,
            // normal, hard, fullCombo, +Inf], so failed plays naturally exert
            // downward pressure on theta. The previous filter dropped them, which
            // made star rating insensitive to failed charts.
            Gdx.app.log("PlayerRating", "Estimating from: " + matchedScores.size() + " matched scores");

            // 5. Estimate rating from player's actual scores
            double theta;
            double playerStarRating;
            int observationCount;
            if (matchedScores.isEmpty()) {
                theta = 0.0;
                playerStarRating = 0.0;
                observationCount = 0;
            } else {
                PlayerRatingEstimator.RatingResult result = PlayerRatingEstimator.estimate(matchedScores, model.starRatingMapping);
                if (result == null) {
                    // Estimation failed — numerical collapse fallback.
                    theta = -1.5;
                    playerStarRating = Math.round(IrtMath.interpolatePiecewiseLinear(theta, model.starRatingMapping, STAR_POINTS) * 100.0) / 100.0;
                    observationCount = matchedScores.size();
                } else {
                    theta = result.theta;
                    playerStarRating = result.playerStarRating;
                    observationCount = result.observationCount;
                }
            }

            // Safety: walkure rating scale 上界 ★25,下界不限制 — 弱鸡玩家会显示 ★-2.10 这类负值。
            // IrtMath 的外推在 theta < mapping[0] 时给出负数,这是合法显示值(index.html:843 注释确认)。
            playerStarRating = Math.min(playerStarRating, 25.0);

            // 5b. Low-confidence threshold: with <10 matched scores the MLE theta
            // is unstable, so we widen the recommendation gate from 0.2 → 0.05 to
            // give the UI something to render. The HTML surfaces this via a banner.
            boolean lowConfidence = observationCount > 0 && observationCount < 10;
            double recProbThreshold = lowConfidence ? 0.05 : 0.2;

            // 6. Build recommendation array — iterate ALL model entries
            StringBuilder recJson = new StringBuilder();
            recJson.append("[");
            boolean recFirst = true;
            List<RecEntry> recEntries = new ArrayList<>();
            for (RecommendationModelData.ChartEntry chartEntry : model.entriesByMd5.values()) {
                int currentOrd = playerLampByMd5.containsKey(chartEntry.md5) ? playerLampByMd5.get(chartEntry.md5) : 0;
                // Try targets from easy(2) through fullCombo(5), only those above current
                for (int t = Math.max(currentOrd + 1, 2); t <= 5; t++) {
                    String targetLamp = ClearLampMapper.LAMP_NAMES[t];
                    if (targetLamp == null || targetLamp.isEmpty()) continue;
                    Double cd = chartEntry.clearDifficulty != null ? chartEntry.clearDifficulty.get(targetLamp) : null;
                    if (cd == null) continue;
                    double prob = IrtMath.sigmoid(chartEntry.chartDiscrimination * (theta - cd));
                    prob = Math.round(prob * 1000.0) / 1000.0;
                    if (prob >= recProbThreshold) {
                        RecEntry re = new RecEntry();
                        re.name = chartEntry.name;
                        re.md5 = chartEntry.md5;
                        re.currentClearLamp = currentOrd > 0 ? ClearLampMapper.LAMP_NAMES[currentOrd] : "noplay";
                        re.targetClearLamp = targetLamp;
                        re.prob = prob;
                        re.discrimination = chartEntry.chartDiscrimination;
                        re.diffLevels = chartEntry.difficultyTableLevels;
                        re.entryType = chartEntry.entryType;
                        recEntries.add(re);
                    }
                }
            }
            recEntries.sort((a, b) -> Double.compare(b.prob, a.prob));
            Gdx.app.log("PlayerRating", "theta=" + theta + " threshold=" + recProbThreshold + " recEntries=" + recEntries.size() + " lowConfidence=" + lowConfidence);
            for (RecEntry re : recEntries) {
                if (!recFirst) recJson.append(",");
                recFirst = false;
                recJson.append("{");
                recJson.append("\"name\":\"").append(escapeJson(re.name)).append("\",");
                recJson.append("\"md5\":\"").append(escapeJson(re.md5)).append("\",");
                recJson.append("\"entryType\":\"").append(escapeJson(re.entryType)).append("\",");
                recJson.append("\"currentClearLamp\":\"").append(re.currentClearLamp).append("\",");
                recJson.append("\"targetClearLamp\":\"").append(re.targetClearLamp).append("\",");
                recJson.append("\"prob\":").append(re.prob);
                if (re.diffLevels != null && !re.diffLevels.isEmpty()) {
                    recJson.append(",\"difficultyTableLevels\":{");
                    boolean lvlFirst = true;
                    for (Map.Entry<String, String> le : re.diffLevels.entrySet()) {
                        if (!lvlFirst) recJson.append(",");
                        lvlFirst = false;
                        recJson.append("\"").append(escapeJson(le.getKey())).append("\":\"").append(escapeJson(le.getValue())).append("\"");
                    }
                    recJson.append("}");
                }
                recJson.append(",\"chartDiscrimination\":").append(re.discrimination);
                recJson.append("}");
            }
            recJson.append("]");

            // 7. Build reverse recommendation array — iterate ALL model entries
            StringBuilder revJson = new StringBuilder();
            revJson.append("[");
            boolean revFirst = true;
            List<RevEntry> revEntries = new ArrayList<>();
            for (RecommendationModelData.ChartEntry chartEntry : model.entriesByMd5.values()) {
                int currentOrd = playerLampByMd5.containsKey(chartEntry.md5) ? playerLampByMd5.get(chartEntry.md5) : 0;
                if (currentOrd < 2) continue; // need at least easy clear
                String lamp = ClearLampMapper.LAMP_NAMES[currentOrd];
                Double cd = chartEntry.clearDifficulty != null ? chartEntry.clearDifficulty.get(lamp) : null;
                if (cd == null) continue;
                double prob = IrtMath.sigmoid(chartEntry.chartDiscrimination * (theta - cd));
                prob = Math.round(prob * 1000.0) / 1000.0;
                if (prob < 0.5) {
                    RevEntry re = new RevEntry();
                    re.name = chartEntry.name;
                    re.md5 = chartEntry.md5;
                    re.clearLamp = lamp;
                    re.prob = prob;
                    re.discrimination = chartEntry.chartDiscrimination;
                    re.diffLevels = chartEntry.difficultyTableLevels;
                    re.entryType = chartEntry.entryType;
                    Double cdStar = chartEntry.clearDifficultyStarRatings != null ? chartEntry.clearDifficultyStarRatings.get(lamp) : null;
                    re.clearDifficultyStarRating = cdStar;
                    revEntries.add(re);
                }
            }
            revEntries.sort((a, b) -> Double.compare(a.prob, b.prob));
            for (RevEntry re : revEntries) {
                if (!revFirst) revJson.append(",");
                revFirst = false;
                revJson.append("{");
                revJson.append("\"name\":\"").append(escapeJson(re.name)).append("\",");
                revJson.append("\"md5\":\"").append(escapeJson(re.md5)).append("\",");
                revJson.append("\"entryType\":\"").append(escapeJson(re.entryType)).append("\",");
                revJson.append("\"clearLamp\":\"").append(re.clearLamp).append("\",");
                revJson.append("\"prob\":").append(re.prob);
                if (re.clearDifficultyStarRating != null) {
                    revJson.append(",\"clearDifficultyStarRating\":").append(re.clearDifficultyStarRating);
                }
                if (re.diffLevels != null && !re.diffLevels.isEmpty()) {
                    revJson.append(",\"difficultyTableLevels\":{");
                    boolean lvlFirst = true;
                    for (Map.Entry<String, String> le : re.diffLevels.entrySet()) {
                        if (!lvlFirst) revJson.append(",");
                        lvlFirst = false;
                        revJson.append("\"").append(escapeJson(le.getKey())).append("\":\"").append(escapeJson(le.getValue())).append("\"");
                    }
                    revJson.append("}");
                }
                revJson.append(",\"chartDiscrimination\":").append(re.discrimination);
                revJson.append("}");
            }
            revJson.append("]");

            // 8. Build chart entries JSON (for chart list tab)
            StringBuilder chartJson = new StringBuilder();
            chartJson.append("[");
            boolean chartFirst = true;
            for (RecommendationModelData.ChartEntry ce : model.entriesByMd5.values()) {
                if (!chartFirst) chartJson.append(",");
                chartFirst = false;
                chartJson.append("{");
                chartJson.append("\"entryType\":\"").append(escapeJson(ce.entryType)).append("\",");
                chartJson.append("\"name\":\"").append(escapeJson(ce.name)).append("\",");
                chartJson.append("\"md5\":\"").append(escapeJson(ce.md5)).append("\",");
                chartJson.append("\"chartDiscrimination\":").append(ce.chartDiscrimination);
                if (ce.difficultyTableLevels != null && !ce.difficultyTableLevels.isEmpty()) {
                    chartJson.append(",\"difficultyTableLevels\":{");
                    boolean lvlFirst = true;
                    for (Map.Entry<String, String> le : ce.difficultyTableLevels.entrySet()) {
                        if (!lvlFirst) chartJson.append(",");
                        lvlFirst = false;
                        chartJson.append("\"").append(escapeJson(le.getKey())).append("\":\"").append(escapeJson(le.getValue())).append("\"");
                    }
                    chartJson.append("}");
                }
                if (ce.clearDifficultyStarRatings != null && !ce.clearDifficultyStarRatings.isEmpty()) {
                    chartJson.append(",\"clearDifficultyStarRatings\":{");
                    boolean srFirst = true;
                    for (Map.Entry<String, Double> sr : ce.clearDifficultyStarRatings.entrySet()) {
                        if (!srFirst) chartJson.append(",");
                        srFirst = false;
                        chartJson.append("\"").append(escapeJson(sr.getKey())).append("\":");
                        if (sr.getValue() == null) chartJson.append("null");
                        else chartJson.append(sr.getValue());
                    }
                    chartJson.append("}");
                }
                chartJson.append("}");
            }
            chartJson.append("]");

            // 9. Build final JSON
            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"playerName\":\"").append(escapeJson(playerName)).append("\",");
            json.append("\"playerStarRating\":").append(playerStarRating).append(",");
            json.append("\"theta\":").append(theta).append(",");
            json.append("\"observationCount\":").append(observationCount).append(",");
            json.append("\"lowConfidence\":").append(lowConfidence).append(",");
            json.append("\"recommendation\":").append(recJson.toString()).append(",");
            json.append("\"reverseRecommendation\":").append(revJson.toString()).append(",");
            json.append("\"chartEntries\":").append(chartJson.toString());
            json.append("}");

            return json.toString();
        } catch (Exception e) {
            Gdx.app.log("PlayerRating", "Error computing rating: " + e.getMessage());
            return errorJson("Error: " + e.getMessage());
        } finally {
            if (scoreDb != null) {
                try { scoreDb.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static class RecEntry {
        String name, md5, currentClearLamp, targetClearLamp, entryType;
        double prob, discrimination;
        Map<String, String> diffLevels;
    }

    private static class RevEntry {
        String name, md5, clearLamp, entryType;
        double prob, discrimination;
        Double clearDifficultyStarRating;
        Map<String, String> diffLevels;
    }

    private static String errorJson(String message) {
        return "{\"error\":\"" + escapeJson(message) + "\"}";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
