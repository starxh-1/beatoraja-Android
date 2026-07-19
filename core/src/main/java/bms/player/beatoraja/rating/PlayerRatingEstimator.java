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

    private static final double THETA_MIN = -5.0;
    private static final double THETA_MAX = 5.0;
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
     * Returns {@code null} only when the observation list is empty after
     * building.  Boundary cases (MLE outside [THETA_MIN, THETA_MAX]) are
     * clamped to the nearest boundary instead of returning null, so the
     * caller always gets a star rating — the caller should clamp the final
     * star rating to [0, 25] as a safety net.
     */
    public static RatingResult estimate(List<MatchedScore> scores, double[] starRatingMapping) {
        List<Observation> observations = buildObservations(scores);

        if (observations.isEmpty()) {
            return null;
        }

        double dMin = logLikelihoodDerivative(THETA_MIN, observations);
        double dMax = logLikelihoodDerivative(THETA_MAX, observations);

        double theta;
        if (dMin > 0.0 && dMax < 0.0) {
            // Normal: the derivative crosses zero inside [THETA_MIN, THETA_MAX].
            theta = IrtMath.findZeroByBisection(
                (candidate) -> logLikelihoodDerivative(candidate, observations),
                THETA_MIN, THETA_MAX, BISECTION_EPSILON);
        } else {
            // No sign change: the MLE is outside the range.  Use the derivative
            // at the midpoint (theta=0) to determine which direction the
            // likelihood is sloping, then clamp to the corresponding boundary.
            double dMid = logLikelihoodDerivative(0.0, observations);
            theta = dMid > 0.0 ? THETA_MAX : THETA_MIN;
        }

        RatingResult result = new RatingResult();
        // Clamp theta to the natural model range. starRatingMapping covers
        // theta ∈ [-1.7, 4.0] (★1..★25); THETA_MIN/MAX is the bisection range
        // and already sits inside this, so this clamp is a no-op safety net.
        theta = Math.max(THETA_MIN, Math.min(THETA_MAX, theta));
        result.theta = theta;
        result.playerStarRating = Math.round(interpolateStarRating(theta, starRatingMapping) * 100.0) / 100.0;
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
