package bms.player.beatoraja.rating;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Estimates a player's skill (theta) from their clear-lamp distribution using
 * a Graded Response Model (Samejima 1969).  Ported from walkure-offline
 * src/domain/player-rating.js with additional robustness for the Android
 * environment where observations can be numerically degenerate.
 */
public class PlayerRatingEstimator {

    // Match walkure-offline player-rating.js THETA_MIN/MAX. The previous
    // ±5 band silently clamped players with only failed plays (or only
    // very high clears) to ±5.0, producing misleading star ratings instead
    // of surfacing the estimation failure.
    private static final double THETA_MIN = -20.0;
    private static final double THETA_MAX = 20.0;
    private static final double BISECTION_EPSILON = 1.0e-6;

    public static class Observation {
        public int lampOrdinal;
        public double discrimination;
        public double[] categoryThresholds;
    }

    public static class RatingResult {
        public double theta;
        public double playerStarRating;
        public int observationCount;
    }

    /**
     * Estimate player skill from matched scores.
     *
     * Returns {@code null} when the MLE cannot be located — either because
     * no observations are available, or because the log-likelihood derivative
     * has no sign change inside [THETA_MIN, THETA_MAX] (the player's observed
     * lamp distribution pushes the MLE outside the range). The latter case
     * matches walkure-offline player-rating.js:18-26, which throws
     * RATING_ESTIMATION_FAILED for the same condition. Callers should treat
     * {@code null} as a hard failure and surface an error to the user.
     */
    public static RatingResult estimate(List<MatchedScore> scores, double[] starRatingMapping) {
        List<Observation> observations = buildObservations(scores);

        if (observations.isEmpty()) {
            return null;
        }

        // walkure-offline player-rating.js:18-26 — when the derivative at the
        // upper bound is still positive (or at the lower bound still negative),
        // the MLE lies outside the range and bisection would converge to a
        // boundary rather than the true likelihood peak. Treat as failure.
        if (logLikelihoodDerivative(THETA_MAX, observations) > 0.0
                || logLikelihoodDerivative(THETA_MIN, observations) < 0.0) {
            return null;
        }

        RatingResult result = new RatingResult();
        result.theta = IrtMath.findZeroByBisection(
            (candidate) -> logLikelihoodDerivative(candidate, observations),
            THETA_MIN, THETA_MAX, BISECTION_EPSILON);
        result.playerStarRating = Math.round(
            interpolateStarRating(result.theta, starRatingMapping) * 100.0) / 100.0;
        result.observationCount = observations.size();
        return result;
    }

    private static List<Observation> buildObservations(List<MatchedScore> scores) {
        List<Observation> obs = new ArrayList<>();
        for (MatchedScore score : scores) {
            Observation o = new Observation();
            o.lampOrdinal = score.getLampOrdinal();
            o.discrimination = score.modelEntry.chartDiscrimination;

            Map<String, Double> cd = score.modelEntry.clearDifficulty;
            // categoryThresholds: [-Inf, easy, normal, hard, fullCombo, +Inf]
            o.categoryThresholds = new double[6];
            o.categoryThresholds[0] = -Double.MAX_VALUE;
            o.categoryThresholds[5] = Double.MAX_VALUE;
            int[] lampOrds = ClearLampMapper.DIFFICULTY_LAMP_ORDINALS;
            for (int i = 0; i < lampOrds.length; i++) {
                String lampName = ClearLampMapper.LAMP_NAMES[lampOrds[i]];
                Double val = cd.get(lampName);
                o.categoryThresholds[i + 1] = val != null ? val : 0.0;
            }
            obs.add(o);
        }
        return obs;
    }

    private static double interpolateStarRating(double theta, double[] starRatingMapping) {
        double[] starPoints = new double[25];
        for (int i = 0; i < 25; i++) starPoints[i] = i + 1;
        return IrtMath.interpolatePiecewiseLinear(theta, starRatingMapping, starPoints);
    }

    /**
     * d/dtheta log P_k(theta) for the Graded Response Model (Samejima 1969).
     * Observations whose category probability has numerically collapsed to
     * zero are skipped to avoid NaN.
     */
    private static double logLikelihoodDerivative(double theta, List<Observation> observations) {
        double total = 0.0;
        for (Observation resp : observations) {
            double[] thresholds = resp.categoryThresholds;
            double upperThreshold = thresholds[resp.lampOrdinal];
            double lowerThreshold = thresholds[resp.lampOrdinal - 1];
            double a = resp.discrimination;
            double upperCumulativeProb = IrtMath.sigmoid(a * (theta - upperThreshold));
            double lowerCumulativeProb = IrtMath.sigmoid(a * (theta - lowerThreshold));
            double categoryProb = lowerCumulativeProb - upperCumulativeProb;

            if (categoryProb <= 0.0) continue;

            double upperSlope = a * upperCumulativeProb * (1.0 - upperCumulativeProb);
            double lowerSlope = a * lowerCumulativeProb * (1.0 - lowerCumulativeProb);
            total += (lowerSlope - upperSlope) / categoryProb;
        }
        return total;
    }
}
