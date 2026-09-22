package bms.player.beatoraja.rating;

/** Port of walkure-offline src/domain/irt-math.js */
public class IrtMath {

    public static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    public static double findZeroByBisection(java.util.function.DoubleUnaryOperator f,
                                               double min, double max, double epsilon) {
        // walkure-offline の irt-math.js と同一の「素の二分法」。f は単調減少で、
        // 呼び出し側が f(min) >= 0、f(max) <= 0 を保証する前提で、[min, max] を狭めて upper を返す。
        //
        // Java 移植時に `if (f(min) == 0.0) return min; if (f(max) == 0.0) return max;` の短絡と
        // `return mid;` が足されていたが、前者が ★40.55 という異常値の原因だった:
        //   θ=+20 では sigmoid(a*(20-閾値)) が double で厳密に 1.0 へ飽和し、
        //   GRM のカテゴリ確率が 0 に潰れて logLikelihoodDerivative がその観測を skip するため、
        //   f(+20) が厳密に 0.0 になる。これを「上端が根」と誤判定して θ=THETA_MAX=20 を返し、
        //   star はマッピング上限を超えて線形外挿されるので ★40.55 になる。
        //   本当の根(実例では θ≈1.52)は範囲内にあるので短絡してはいけない。
        //   「上端が既に根」の場合(f(x) = -x を [-5, 0] で解く等)は、短絡が無くても
        //   二分法が upper を 0 に収束させるので、upstream のテストと同じ 0.0 が返る。
        double fMin = f.applyAsDouble(min);

        while (max - min > epsilon) {
            double mid = (min + max) * 0.5;
            double fMid = f.applyAsDouble(mid);
            if (Math.signum(fMin) != Math.signum(fMid)) {
                max = mid;
            } else {
                min = mid;
                fMin = fMid;
            }
        }
        return max;
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
