package vn.bachphuc.trafficai;

/**
 * Chính sách cảnh báo sớm vị trí đèn giao thông.
 *
 * <p>Dữ liệu bản đồ chỉ xác nhận có cụm đèn phía trước. Màu đỏ/vàng/xanh vẫn phải
 * được camera và bộ lọc nhiều khung hình xác nhận trước khi phát giọng đọc.</p>
 */
public final class EarlySignalAlertPolicy {
    public static final double PREFETCH_RADIUS_METERS = 220d;
    public static final double EARLY_ALERT_MIN_METERS = 105d;
    public static final double EARLY_ALERT_MAX_METERS = 180d;
    public static final int MIN_APPROACH_SPEED_KMH = 4;

    private EarlySignalAlertPolicy() {
    }

    public static boolean shouldPrefetch(double distanceMeters) {
        return Double.isFinite(distanceMeters)
                && distanceMeters >= 0d
                && distanceMeters <= PREFETCH_RADIUS_METERS;
    }

    public static boolean shouldAnnouncePresence(
            double distanceMeters, int approachSpeedKmh) {
        return distanceMeters >= EARLY_ALERT_MIN_METERS
                && distanceMeters <= EARLY_ALERT_MAX_METERS
                && approachSpeedKmh >= MIN_APPROACH_SPEED_KMH;
    }

    public static boolean shouldUseFarCameraScan(double distanceMeters) {
        return Double.isFinite(distanceMeters)
                && distanceMeters >= 100d
                && distanceMeters <= PREFETCH_RADIUS_METERS;
    }

    public static String presenceSpeech() {
        return "Phía trước khoảng một trăm năm mươi mét có tín hiệu đèn giao thông";
    }
}
