package vn.bachphuc.trafficai;

/**
 * Ngưỡng hỗ trợ lái xe cho điều kiện đường khô, thẳng và tầm nhìn bình thường.
 * Các mốc 60/80/100/120 km/h bám theo Điều 11 Thông tư 38/2024/TT-BGTVT.
 */
public final class DistanceWarningPolicy {
    private DistanceWarningPolicy() {
    }

    /** Trả 0 dưới 60 km/h vì bảng pháp lý yêu cầu theo biển/cự ly phù hợp ở dải này. */
    public static int vietnamDryMinimumMeters(int speedKmh) {
        if (speedKmh < 60) return 0;
        if (speedKmh == 60) return 35;
        if (speedKmh <= 80) return 55;
        if (speedKmh <= 100) return 70;
        return 100;
    }

    public static DistanceWarningState evaluate(
            double distanceMeters,
            double ttcSeconds,
            int speedKmh,
            float confidence,
            int confirmations) {
        if (!Double.isFinite(distanceMeters) || distanceMeters <= 0d) {
            return DistanceWarningState.SEARCHING;
        }
        if (confirmations < 3 || confidence < .50f) {
            return DistanceWarningState.TRACKING;
        }

        boolean moving = speedKmh >= 20;
        boolean validTtc = Double.isFinite(ttcSeconds) && ttcSeconds > 0d;
        int legalTarget = vietnamDryMinimumMeters(speedKmh);
        if (moving && (validTtc && ttcSeconds <= 2.2d
                || legalTarget > 0 && distanceMeters < legalTarget * .55d)) {
            return DistanceWarningState.DANGER;
        }
        if (moving && (validTtc && ttcSeconds <= 4.0d
                || legalTarget > 0 && distanceMeters < legalTarget)) {
            return DistanceWarningState.CAUTION;
        }
        return DistanceWarningState.SAFE;
    }
}
