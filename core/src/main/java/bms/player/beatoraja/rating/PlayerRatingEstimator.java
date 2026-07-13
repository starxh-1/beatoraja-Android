package bms.player.beatoraja.rating;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PlayerRatingEstimator {

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

    public static RatingResult estimate(List<MatchedScore> scores, double[] starRatingMapping) {
        List<Observation> observations = buildObservations(scores);

        double dMax = logLikelihoodDerivative(THETA_MAX, observations);
        double dMin = logLikelihoodDerivative(THETA_MIN, observations);

        if (dMax > 0.0 || dMin < 0.0) {
            return null;
        }

        double theta = IrtMath.findZeroByBisection(
            (candidate) -> logLikelihoodDerivative(candidate, observations),
            THETA_MIN, THETA_MAX, BISECTION_EPSILON);

        RatingResult result = new RatingResult();
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
            double upperSlope = a * upperCumulativeProb * (1.0 - upperCumulativeProb);
            double lowerSlope = a * lowerCumulativeProb * (1.0 - lowerCumulativeProb);

            total += (lowerSlope - upperSlope) / categoryProb;
        }
        return total;
    }
}
