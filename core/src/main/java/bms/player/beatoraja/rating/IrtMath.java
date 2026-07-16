package bms.player.beatoraja.rating;

/** Port of walkure-offline src/domain/irt-math.js */
public class IrtMath {

    public static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    public static double findZeroByBisection(java.util.function.DoubleUnaryOperator f,
                                               double min, double max, double epsilon) {
        double fMin = f.applyAsDouble(min);
        double fMax = f.applyAsDouble(max);
        if (fMin == 0.0) return min;
        if (fMax == 0.0) return max;

        double mid = 0;
        while (max - min > epsilon) {
            mid = (min + max) * 0.5;
            double fMid = f.applyAsDouble(mid);
            if (fMid == 0.0) return mid;
            if (Math.signum(fMin) != Math.signum(fMid)) {
                max = mid;
                fMax = fMid;
            } else {
                min = mid;
                fMin = fMid;
            }
        }
        return mid;
    }

    public static double interpolatePiecewiseLinear(double x, double[] xPoints, double[] yPoints) {
        // 边界外推（线性），而不是 clamp。Walkure 的 star rating 允许低于 1.0 或高于 25.0。
        if (x <= xPoints[0]) {
            double slope = (yPoints[1] - yPoints[0]) / (xPoints[1] - xPoints[0]);
            return yPoints[0] + slope * (x - xPoints[0]);
        }
        if (x >= xPoints[xPoints.length - 1]) {
            int last = xPoints.length - 1;
            double slope = (yPoints[last] - yPoints[last - 1]) / (xPoints[last] - xPoints[last - 1]);
            return yPoints[last] + slope * (x - xPoints[last]);
        }

        for (int i = 0; i < xPoints.length - 1; i++) {
            if (x >= xPoints[i] && x < xPoints[i + 1]) {
                double t = (x - xPoints[i]) / (xPoints[i + 1] - xPoints[i]);
                return yPoints[i] + t * (yPoints[i + 1] - yPoints[i]);
            }
        }
        return yPoints[yPoints.length - 1];
    }
}
