package bms.player.beatoraja.rating;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.JsonReader;

public class RecommendationModelLoader {

    public static RecommendationModelData load(InputStream entriesStream, InputStream mappingStream) throws Exception {
        JsonReader reader = new JsonReader();

        // Parse star rating mapping
        JsonValue mappingJson = reader.parse(new InputStreamReader(mappingStream, StandardCharsets.UTF_8));
        List<Double> mappingList = new ArrayList<>();
        for (JsonValue v = mappingJson.child; v != null; v = v.next) {
            mappingList.add(v.asDouble());
        }
        double[] starRatingMapping = new double[mappingList.size()];
        for (int i = 0; i < mappingList.size(); i++) {
            starRatingMapping[i] = mappingList.get(i);
        }

        // Parse entries
        JsonValue entriesJson = reader.parse(new InputStreamReader(entriesStream, StandardCharsets.UTF_8));
        Map<String, RecommendationModelData.ChartEntry> entriesByMd5 = new HashMap<>();

        for (JsonValue entry = entriesJson.child; entry != null; entry = entry.next) {
            RecommendationModelData.ChartEntry ce = new RecommendationModelData.ChartEntry();
            ce.entryType = entry.getString("entryType", "chart");
            ce.name = entry.getString("name", "");
            ce.md5 = entry.getString("md5", "").toLowerCase();

            // difficultyTableLevels
            JsonValue dtl = entry.get("difficultyTableLevels");
            if (dtl != null) {
                ce.difficultyTableLevels = new HashMap<>();
                for (JsonValue kv = dtl.child; kv != null; kv = kv.next) {
                    ce.difficultyTableLevels.put(kv.name, kv.asString());
                }
            }

            ce.chartDiscrimination = entry.getDouble("chartDiscrimination", 0.0);

            // clearDifficulty
            JsonValue cd = entry.get("clearDifficulty");
            if (cd != null) {
                ce.clearDifficulty = new HashMap<>();
                for (JsonValue kv = cd.child; kv != null; kv = kv.next) {
                    ce.clearDifficulty.put(kv.name, kv.asDouble());
                }
            }

            // clearDifficultyStarRatings
            JsonValue cdsr = entry.get("clearDifficultyStarRatings");
            if (cdsr != null) {
                ce.clearDifficultyStarRatings = new HashMap<>();
                for (JsonValue kv = cdsr.child; kv != null; kv = kv.next) {
                    ce.clearDifficultyStarRatings.put(kv.name, kv.isNull() ? null : kv.asDouble());
                }
            }

            entriesByMd5.put(ce.md5, ce);
        }

        return new RecommendationModelData(entriesByMd5, starRatingMapping);
    }
}
