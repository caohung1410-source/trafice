package vn.bachphuc.trafficai;

/**
 * Ưu tiên hình học cho biển báo và đèn tín hiệu trên đường Việt Nam.
 *
 * <p>Các chiều cao dưới đây lấy từ QCVN 41:2024/BGTVT. Không thể đổi trực tiếp mét sang
 * pixel nếu chưa biết độ cao/góc nghiêng camera, tiêu cự và khoảng cách tới cột. Vì vậy bản
 * này dùng chúng như prior mềm: ưu tiên vùng trên-phải và vùng treo ngang, nhưng vẫn giữ
 * vùng giữa/trái để không bỏ sót đèn trên cần vươn hoặc biển nhắc lại.</p>
 */
public final class RoadGeometryPrior {
    public static final float SIGN_BOTTOM_OUTSIDE_URBAN_M = 1.8f;
    public static final float SIGN_BOTTOM_URBAN_M = 2.0f;
    public static final float SIGN_SPECIAL_MIN_M = 1.2f;
    public static final float SIGN_SPECIAL_MAX_M = 5.0f;

    public static final float SIGNAL_ROADSIDE_MIN_M = 1.7f;
    public static final float SIGNAL_ROADSIDE_MAX_M = 5.8f;
    public static final float SIGNAL_MAST_ARM_MIN_M = 5.2f;
    public static final float SIGNAL_MAST_ARM_MAX_M = 7.8f;
    public static final float SIGNAL_SUSPENDED_MIN_M = 5.0f;
    public static final float SIGNAL_SUSPENDED_MAX_M = 5.5f;

    private RoadGeometryPrior() {
    }

    /** Điểm 0..1 cho tâm hộp đèn đã chuẩn hóa theo chiều rộng/cao ảnh. */
    public static float trafficLightEvidence(float centerX, float centerY) {
        float x = clamp01(centerX);
        float y = clamp01(centerY);

        // Đèn đặt đứng: thường ở lề/dải phân cách, ưu tiên bên phải trước vạch dừng.
        float rightRoadside = range(x, 0.64f, 1.00f, 0.18f)
                * range(y, 0.04f, 0.72f, 0.16f);
        // Đèn trên cần vươn/treo: cao và có thể nằm gần giữa hoặc lệch trái/phải.
        float overhead = range(x, 0.08f, 0.94f, 0.10f)
                * range(y, 0.01f, 0.50f, 0.18f);
        // Vẫn giữ đèn đứng bổ sung bên trái, nhưng không ưu tiên bằng bên phải.
        float leftSupplement = 0.62f * range(x, 0.00f, 0.38f, 0.16f)
                * range(y, 0.05f, 0.68f, 0.16f);
        return clamp(Math.max(0.18f, Math.max(rightRoadside,
                Math.max(overhead, leftSupplement))), 0f, 1f);
    }

    /** Điểm 0..1 cho tâm hộp biển báo đã chuẩn hóa theo chiều rộng/cao ảnh. */
    public static float trafficSignEvidence(float centerX, float centerY) {
        float x = clamp01(centerX);
        float y = clamp01(centerY);

        // Biển bên đường: ưu tiên lề phải; dải y rộng để chứa biển thấp đặc biệt.
        float rightRoadside = range(x, 0.58f, 1.00f, 0.20f)
                * range(y, 0.06f, 0.82f, 0.13f);
        // Biển trên giá long môn/cần vươn ở phần trên của ảnh.
        float overhead = range(x, 0.08f, 0.94f, 0.10f)
                * range(y, 0.01f, 0.48f, 0.17f);
        // Một số vị trí có biển nhắc lại bên trái.
        float leftSupplement = 0.66f * range(x, 0.00f, 0.42f, 0.16f)
                * range(y, 0.05f, 0.72f, 0.15f);
        return clamp(Math.max(0.22f, Math.max(rightRoadside,
                Math.max(overhead, leftSupplement))), 0f, 1f);
    }

    /**
     * Giữ nguyên độ tin cậy ở vùng hợp lý và chỉ giảm nhẹ vùng kém hợp lý. Prior không được
     * tự nâng một phát hiện yếu thành phát hiện mạnh.
     */
    public static float adjustConfidence(float detectorConfidence, float geometryEvidence) {
        float detector = clamp(detectorConfidence, 0f, 1f);
        float geometry = clamp01(geometryEvidence);
        // Prior chỉ giảm tối đa khoảng 6,3% ở vùng kém thuận lợi. Nó dùng để xếp hạng,
        // không được xóa một biển thật ở giữa/trái ảnh.
        return clamp(detector * (0.92f + 0.08f * geometry), 0f, 1f);
    }

    /**
     * Mức phù hợp với hướng xe đang chạy, dùng để giảm nhầm đèn thấp của luồng giao cắt.
     * Đây vẫn là prior mềm vì chưa có hiệu chuẩn camera/vạch làn chính xác.
     */
    public static float travelDirectionEvidence(float centerX, float centerY) {
        float x = clamp01(centerX);
        float y = clamp01(centerY);
        float overheadCurrentLane = range(x, 0.22f, 0.82f, 0.20f)
                * range(y, 0.01f, 0.50f, 0.16f);
        float rightCurrentLane = 0.94f * range(x, 0.58f, 1.00f, 0.18f)
                * range(y, 0.06f, 0.68f, 0.16f);
        float leftRepeat = 0.58f * range(x, 0.00f, 0.36f, 0.14f)
                * range(y, 0.04f, 0.56f, 0.14f);
        return clamp(Math.max(0.20f, Math.max(overheadCurrentLane,
                Math.max(rightCurrentLane, leftRepeat))), 0f, 1f);
    }

    private static float range(float value, float low, float high, float feather) {
        if (value >= low && value <= high) return 1f;
        if (value < low) return clamp01((value - (low - feather)) / feather);
        return clamp01(((high + feather) - value) / feather);
    }

    private static float clamp01(float value) {
        return clamp(value, 0f, 1f);
    }

    private static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }
}
