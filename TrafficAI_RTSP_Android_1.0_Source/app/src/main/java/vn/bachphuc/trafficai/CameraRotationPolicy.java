package vn.bachphuc.trafficai;

/** Quy tắc xoay preview Camera2, tách khỏi Android để kiểm thử trên JVM. */
public final class CameraRotationPolicy {
    private CameraRotationPolicy() {
    }

    /** Surface.ROTATION_0/90/180/270 lần lượt có giá trị 0/1/2/3. */
    public static float previewRotationDegrees(int displayRotation) {
        if (displayRotation == 1) return -90f;
        if (displayRotation == 2) return 180f;
        if (displayRotation == 3) return 90f;
        return 0f;
    }

    public static boolean swapsBufferDimensions(int displayRotation) {
        return displayRotation == 1 || displayRotation == 3;
    }

    public static boolean isQuarterTurnDegrees(int degrees) {
        int normalized = normalizeDegrees(degrees);
        return normalized == 90 || normalized == 270;
    }

    public static int normalizeDegrees(int value) {
        int normalized = value % 360;
        return normalized < 0 ? normalized + 360 : normalized;
    }

    public static int normalizeManualDegrees(int value) {
        int normalized = normalizeDegrees(value);
        return (normalized / 90) * 90;
    }
}
