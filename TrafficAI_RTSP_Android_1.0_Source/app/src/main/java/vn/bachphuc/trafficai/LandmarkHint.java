package vn.bachphuc.trafficai;

/** Gợi ý vùng ảnh khi xe quay lại gần một biển báo hoặc cụm đèn đã học. */
public final class LandmarkHint {
    public static final String TYPE_LIGHT = "LIGHT";
    public static final String TYPE_SIGN = "SIGN";
    public static final String TYPE_ALERT = "ALERT";
    public static final LandmarkHint NONE = new LandmarkHint(
            -1L, "", "", .75f, .38f, Double.POSITIVE_INFINITY, 0);

    public final long id;
    public final String type;
    public final String label;
    public final float imageX;
    public final float imageY;
    public final double distanceMeters;
    public final int confirmations;

    public LandmarkHint(
            long id,
            String type,
            String label,
            float imageX,
            float imageY,
            double distanceMeters,
            int confirmations) {
        this.id = id;
        this.type = type == null ? "" : type;
        this.label = label == null ? "" : label;
        this.imageX = clamp(imageX, 0f, 1f);
        this.imageY = clamp(imageY, 0f, 1f);
        this.distanceMeters = distanceMeters;
        this.confirmations = Math.max(0, confirmations);
    }

    public boolean isActive() {
        return id >= 0L && distanceMeters <= 160d;
    }

    public boolean expectsLight() {
        return isActive() && TYPE_LIGHT.equals(type);
    }

    public boolean expectsSign() {
        return isActive() && TYPE_SIGN.equals(type);
    }

    public boolean isMapAlert() {
        return isActive() && TYPE_ALERT.equals(type);
    }

    private static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }
}
