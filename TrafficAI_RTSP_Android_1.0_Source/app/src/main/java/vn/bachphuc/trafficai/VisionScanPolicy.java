package vn.bachphuc.trafficai;

/**
 * Chia thời gian suy luận giữa cảnh phía trước và model biển Việt Nam.
 * Một chu kỳ năm lượt giữ tối thiểu hai lượt cho mỗi model để đèn, biển và
 * khoảng cách đều tiếp tục được cập nhật khi một nhóm đang được ưu tiên.
 */
public final class VisionScanPolicy {
    private VisionScanPolicy() {
    }

    public static boolean useSceneDetector(
            int phase, boolean distancePriority, boolean expectsLight, boolean expectsSign) {
        int slot = Math.floorMod(phase, 5);
        if (distancePriority || expectsLight) {
            return slot == 0 || slot == 2 || slot == 4;
        }
        if (expectsSign) {
            return slot == 2 || slot == 4;
        }
        return slot == 1 || slot == 4;
    }

    /** Quét toàn cảnh định kỳ để ROI không làm mất xe, người hoặc đèn ngoài vùng nhớ. */
    public static boolean forceFullScene(int scenePass, boolean distancePriority) {
        int interval = distancePriority ? 3 : 4;
        return Math.floorMod(scenePass, interval) == interval - 1;
    }

    /** ROI trung tâm-phía dưới dành cho ô tô cùng làn trên đường trường/cao tốc. */
    public static float[] forwardRoadRegion() {
        return new float[]{.16f, .24f, .84f, 1f};
    }
}
