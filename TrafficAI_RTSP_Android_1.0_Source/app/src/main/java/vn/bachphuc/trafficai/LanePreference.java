package vn.bachphuc.trafficai;

import java.util.Locale;

/** Làn xe do người lái chọn để ưu tiên cụm đèn/biển đúng hướng di chuyển. */
public enum LanePreference {
    LEFT("TRÁI", new int[]{-1, -1, 0, 1, 2}, .30f),
    CENTER("GIỮA", new int[]{0, 0, 1, -1, 2}, .50f),
    RIGHT("PHẢI", new int[]{1, 1, 0, -1, 2}, .70f);

    public final String vi;
    private final int[] scanOrder;
    private final float targetX;

    LanePreference(String vi, int[] scanOrder, float targetX) {
        this.vi = vi;
        this.scanOrder = scanOrder;
        this.targetX = targetX;
    }

    /** -1: trái, 0: giữa, 1: phải, 2: toàn cảnh. */
    public int scanSlot(int pass) {
        return scanOrder[Math.floorMod(pass, scanOrder.length)];
    }

    /** Điểm phù hợp của tâm cụm đèn với làn đang chọn, không loại cứng cụm đèn khác. */
    public float visualEvidence(float normalizedX) {
        float distance = Math.abs(normalizedX - targetX);
        return Math.max(0f, 1f - distance / .58f);
    }

    public static LanePreference fromStored(String value) {
        if (value == null) return CENTER;
        try {
            return valueOf(value.trim().toUpperCase(Locale.US));
        } catch (IllegalArgumentException error) {
            return CENTER;
        }
    }
}
