package vn.bachphuc.trafficai;

/** Theo dõi bước rẽ kế tiếp, khoảng cách lệch tuyến và thời điểm cần phát TTS. */
public final class NavigationSession {
    public static final class Guidance {
        public final boolean active;
        public final String instruction;
        public final double distanceMeters;
        public final boolean shouldSpeak;
        public final boolean arrived;
        public final boolean offRoute;

        Guidance(
                boolean active, String instruction, double distanceMeters,
                boolean shouldSpeak, boolean arrived, boolean offRoute) {
            this.active = active;
            this.instruction = instruction;
            this.distanceMeters = distanceMeters;
            this.shouldSpeak = shouldSpeak;
            this.arrived = arrived;
            this.offRoute = offRoute;
        }
    }

    private RoutePlan plan;
    private int stepIndex;
    private int lastSpokenBucket = 4;

    public synchronized void setPlan(RoutePlan value) {
        plan = value;
        stepIndex = value != null && value.steps.size() > 1 ? 1 : 0;
        lastSpokenBucket = 4;
    }

    public synchronized RoutePlan getPlan() {
        return plan;
    }

    public synchronized void clear() {
        plan = null;
        stepIndex = 0;
        lastSpokenBucket = 4;
    }

    public synchronized Guidance update(double latitude, double longitude) {
        if (plan == null || plan.steps.isEmpty()) {
            return new Guidance(false, "", 0d, false, false, false);
        }
        RoutePlan.Step step = plan.steps.get(Math.min(stepIndex, plan.steps.size() - 1));
        double distance = GeoMath.distanceMeters(
                latitude, longitude, step.latitude, step.longitude);
        while (distance < 22d && stepIndex < plan.steps.size() - 1) {
            stepIndex++;
            lastSpokenBucket = 4;
            step = plan.steps.get(stepIndex);
            distance = GeoMath.distanceMeters(
                    latitude, longitude, step.latitude, step.longitude);
        }
        boolean lastStep = stepIndex == plan.steps.size() - 1;
        boolean arrived = lastStep
                && GeoMath.distanceMeters(latitude, longitude,
                plan.destinationLatitude, plan.destinationLongitude) < 32d;
        int bucket = distance <= 40d ? 0 : distance <= 100d ? 1 : distance <= 300d ? 2 : 3;
        boolean shouldSpeak = !arrived && bucket < lastSpokenBucket;
        if (shouldSpeak) lastSpokenBucket = bucket;
        return new Guidance(
                true,
                arrived ? "Đã đến " + plan.destinationName : step.instruction,
                arrived ? 0d : distance,
                shouldSpeak || arrived,
                arrived,
                distanceFromRoute(latitude, longitude) > 100d);
    }

    private double distanceFromRoute(double latitude, double longitude) {
        if (plan == null || plan.geometry.size() < 2) return 0d;
        double best = Double.POSITIVE_INFINITY;
        for (int index = 1; index < plan.geometry.size(); index++) {
            RoutePlan.Point first = plan.geometry.get(index - 1);
            RoutePlan.Point second = plan.geometry.get(index);
            best = Math.min(best, GeoMath.distanceToSegmentMeters(
                    latitude, longitude,
                    first.latitude, first.longitude,
                    second.latitude, second.longitude));
        }
        return best;
    }
}
