package vn.bachphuc.trafficai;

import java.util.Collections;
import java.util.List;

/**
 * Ước lượng đơn mắt theo giao điểm đáy hộp xe với mặt đường đã hiệu chuẩn.
 * Kết quả là hỗ trợ lái xe thử nghiệm, không phải phép đo radar và không dùng để phanh tự động.
 */
public final class LeadVehicleDistanceEstimator {
    private static final long HOLD_MS = 2_400L;
    private static final double MIN_DISTANCE_METERS = 4d;
    private static final double MAX_DISTANCE_METERS = 180d;

    private Calibration calibration = Calibration.defaults();
    private boolean enabled = true;
    private Observation tracked;
    private double smoothedDistance = Double.NaN;
    private double smoothedClosingMps;
    private long lastUpdateAt;
    private int confirmations;
    private Result last = Result.searching();

    public synchronized void configure(boolean enabled, Calibration calibration) {
        this.enabled = enabled;
        this.calibration = calibration == null ? Calibration.defaults() : calibration.sanitized();
        reset();
    }

    public synchronized Result update(
            List<Observation> observations, int speedKmh, long nowMs) {
        if (!enabled) {
            last = Result.disabled();
            return last;
        }
        List<Observation> safe = observations == null
                ? Collections.emptyList() : observations;
        Observation selected = chooseLead(safe);
        double rawDistance = selected == null
                ? Double.NaN : estimateDistanceMeters(selected.bottom);
        if (selected == null || !Double.isFinite(rawDistance)) {
            if (lastUpdateAt > 0L && nowMs - lastUpdateAt <= HOLD_MS) return last;
            clearTrack();
            last = Result.searching();
            return last;
        }

        boolean continues = tracked != null && affinity(tracked, selected) >= .24f;
        if (!continues) {
            tracked = selected;
            smoothedDistance = rawDistance;
            smoothedClosingMps = 0d;
            confirmations = 1;
            lastUpdateAt = nowMs;
        } else {
            double seconds = Math.max(.10d, (nowMs - lastUpdateAt) / 1_000d);
            double previous = smoothedDistance;
            // Hạn chế bước nhảy do ổ gà/rung camera nhưng vẫn theo kịp xe đang áp sát.
            double maxChange = Math.max(5d, seconds * 22d);
            double bounded = clamp(rawDistance, previous - maxChange, previous + maxChange);
            double alpha = seconds >= 1.2d ? .46d : .34d;
            smoothedDistance = previous * (1d - alpha) + bounded * alpha;
            double closingMps = clamp((previous - smoothedDistance) / seconds, -12d, 25d);
            smoothedClosingMps = smoothedClosingMps * .58d + closingMps * .42d;
            confirmations = Math.min(20, confirmations + 1);
            tracked = selected;
            lastUpdateAt = nowMs;
        }

        double speedMps = Math.max(0d, speedKmh / 3.6d);
        double headway = speedMps >= 1.5d ? smoothedDistance / speedMps : Double.NaN;
        double ttc = smoothedClosingMps >= .55d
                ? smoothedDistance / smoothedClosingMps : Double.NaN;
        float confidence = confidence(selected, rawDistance, continues);
        DistanceWarningState state = DistanceWarningPolicy.evaluate(
                smoothedDistance, ttc, speedKmh, confidence, confirmations);
        last = new Result(
                true,
                state,
                smoothedDistance,
                DistanceWarningPolicy.vietnamDryMinimumMeters(speedKmh),
                headway,
                smoothedClosingMps * 3.6d,
                ttc,
                confidence,
                confirmations,
                vehicleLabel(selected.classId),
                selected);
        return last;
    }

    public synchronized Result current(long nowMs, int speedKmh) {
        if (!enabled) return Result.disabled();
        if (lastUpdateAt == 0L || nowMs - lastUpdateAt > HOLD_MS) {
            clearTrack();
            last = Result.searching();
            return last;
        }
        DistanceWarningState refreshed = DistanceWarningPolicy.evaluate(
                last.distanceMeters, last.ttcSeconds, speedKmh,
                last.confidence, last.confirmations);
        last = last.withStateAndTarget(
                refreshed, DistanceWarningPolicy.vietnamDryMinimumMeters(speedKmh));
        return last;
    }

    public synchronized void reset() {
        clearTrack();
        last = enabled ? Result.searching() : Result.disabled();
    }

    private void clearTrack() {
        tracked = null;
        smoothedDistance = Double.NaN;
        smoothedClosingMps = 0d;
        lastUpdateAt = 0L;
        confirmations = 0;
    }

    private Observation chooseLead(List<Observation> observations) {
        Observation best = null;
        double bestScore = -Double.MAX_VALUE;
        for (Observation value : observations) {
            if (value == null || !isRoadVehicle(value.classId) || value.confidence < .17f) continue;
            double distance = estimateDistanceMeters(value.bottom);
            if (!Double.isFinite(distance)) continue;
            float corridor = corridorEvidence(value.centerX(), value.bottom);
            double continuity = tracked == null ? 0d : affinity(tracked, value);
            // Khóa ban đầu vẫn nghiêm theo tâm làn. Khi đã có track ổn định, cho phép
            // xe trước dịch nhẹ sang trái/phải ở đoạn cong mà không nhảy sang xe bên cạnh.
            float minimumCorridor = continuity >= .30d ? .28f : .42f;
            if (corridor < minimumCorridor) continue;
            double nearEvidence = 1d - clamp(distance / MAX_DISTANCE_METERS, 0d, 1d);
            double bottomWidth = clamp(value.width() / Math.max(.05d, value.bottom - calibration.horizonRatio), 0d, 1d);
            double score = value.confidence * .26d
                    + corridor * .30d
                    + nearEvidence * .15d
                    + bottomWidth * .07d
                    + continuity * .22d;
            if (tracked != null && continuity < .08d) score -= .10d;
            if (score > bestScore) {
                bestScore = score;
                best = value;
            }
        }
        return best;
    }

    private double estimateDistanceMeters(float bottomRatio) {
        double belowHorizon = bottomRatio - calibration.horizonRatio;
        if (belowHorizon <= .006d) return Double.NaN;
        double focalHeight = .5d / Math.tan(Math.toRadians(calibration.verticalFovDegrees) / 2d);
        double meters = calibration.cameraHeightMeters * focalHeight / belowHorizon;
        return meters >= MIN_DISTANCE_METERS && meters <= MAX_DISTANCE_METERS
                ? meters : Double.NaN;
    }

    private float confidence(Observation observation, double rawDistance, boolean continues) {
        float corridor = corridorEvidence(observation.centerX(), observation.bottom);
        float geometry = (float) clamp((observation.bottom - calibration.horizonRatio) / .10d, .25d, 1d);
        float range = (float) clamp(1.12d - rawDistance / 260d, .45d, 1d);
        float confirmation = Math.min(1f, confirmations / 4f);
        return (float) clamp(observation.confidence * .34d
                + corridor * .25d
                + geometry * .12d
                + range * .11d
                + confirmation * .12d
                + (continues ? .06d : 0d), 0d, 1d);
    }

    static float corridorEvidence(float centerX, float bottomY) {
        float y = clamp(bottomY, 0f, 1f);
        float halfWidth = .075f + Math.max(0f, y - .32f) * .48f;
        return clamp(1f - Math.abs(centerX - .50f) / Math.max(.075f, halfWidth), 0f, 1f);
    }

    static float affinity(Observation first, Observation second) {
        float centerDistance = (float) Math.hypot(
                first.centerX() - second.centerX(),
                first.bottom - second.bottom);
        float sizeDelta = Math.abs(first.width() - second.width())
                + Math.abs(first.height() - second.height());
        return clamp(1f - centerDistance / .28f - sizeDelta / .75f, 0f, 1f);
    }

    private static boolean isRoadVehicle(int classId) {
        return classId == 2 || classId == 5 || classId == 7;
    }

    private static String vehicleLabel(int classId) {
        if (classId == 5) return "XE BUÝT";
        if (classId == 7) return "XE TẢI";
        return "Ô TÔ";
    }

    private static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    public static final class Calibration {
        public final double cameraHeightMeters;
        public final double horizonRatio;
        public final double verticalFovDegrees;

        public Calibration(
                double cameraHeightMeters, double horizonRatio, double verticalFovDegrees) {
            this.cameraHeightMeters = cameraHeightMeters;
            this.horizonRatio = horizonRatio;
            this.verticalFovDegrees = verticalFovDegrees;
        }

        public static Calibration defaults() {
            return new Calibration(1.25d, .44d, 52d);
        }

        Calibration sanitized() {
            return new Calibration(
                    clamp(cameraHeightMeters, .70d, 2.50d),
                    clamp(horizonRatio, .25d, .65d),
                    clamp(verticalFovDegrees, 30d, 90d));
        }
    }

    public static final class Observation {
        public final float left;
        public final float top;
        public final float right;
        public final float bottom;
        public final int classId;
        public final float confidence;

        public Observation(
                float left, float top, float right, float bottom,
                int classId, float confidence) {
            this.left = clamp(left, 0f, 1f);
            this.top = clamp(top, 0f, 1f);
            this.right = clamp(right, this.left, 1f);
            this.bottom = clamp(bottom, this.top, 1f);
            this.classId = classId;
            this.confidence = clamp(confidence, 0f, 1f);
        }

        public float centerX() {
            return (left + right) * .5f;
        }

        public float width() {
            return right - left;
        }

        public float height() {
            return bottom - top;
        }
    }

    public static final class Result {
        public final boolean hasLeadVehicle;
        public final DistanceWarningState state;
        public final double distanceMeters;
        public final int requiredDistanceMeters;
        public final double headwaySeconds;
        public final double closingSpeedKmh;
        public final double ttcSeconds;
        public final float confidence;
        public final int confirmations;
        public final String vehicleLabel;
        public final Observation observation;

        Result(
                boolean hasLeadVehicle,
                DistanceWarningState state,
                double distanceMeters,
                int requiredDistanceMeters,
                double headwaySeconds,
                double closingSpeedKmh,
                double ttcSeconds,
                float confidence,
                int confirmations,
                String vehicleLabel,
                Observation observation) {
            this.hasLeadVehicle = hasLeadVehicle;
            this.state = state;
            this.distanceMeters = distanceMeters;
            this.requiredDistanceMeters = requiredDistanceMeters;
            this.headwaySeconds = headwaySeconds;
            this.closingSpeedKmh = closingSpeedKmh;
            this.ttcSeconds = ttcSeconds;
            this.confidence = confidence;
            this.confirmations = confirmations;
            this.vehicleLabel = vehicleLabel == null ? "" : vehicleLabel;
            this.observation = observation;
        }

        static Result searching() {
            return new Result(false, DistanceWarningState.SEARCHING,
                    Double.NaN, 0, Double.NaN, 0d, Double.NaN,
                    0f, 0, "", null);
        }

        static Result disabled() {
            return new Result(false, DistanceWarningState.SEARCHING,
                    Double.NaN, 0, Double.NaN, 0d, Double.NaN,
                    0f, 0, "TẮT", null);
        }

        Result withStateAndTarget(DistanceWarningState nextState, int nextTarget) {
            return new Result(hasLeadVehicle, nextState, distanceMeters, nextTarget,
                    headwaySeconds, closingSpeedKmh, ttcSeconds, confidence,
                    confirmations, vehicleLabel, observation);
        }
    }
}
