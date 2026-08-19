package vn.bachphuc.trafficai;

import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Phân tích thận trọng các đối tượng COCO nằm trong hành lang chạy phía trước. Không ước
 * lượng khoảng cách hay TTC; chỉ tạo cảnh báo hỗ trợ khi xe/người chiếm vùng ảnh đủ lớn.
 */
public final class ForwardHazardAnalyzer {
    private static final long HOLD_MS = 1_900L;

    private List<Detection> held = Collections.emptyList();
    private String text = "";
    private float confidence;
    private long lastSeenAt;
    private int consecutiveConfirmations;

    public synchronized Result update(
            List<Detection> observations,
            int frameWidth,
            int frameHeight,
            long nowMs,
            boolean fullScenePass) {
        if (!fullScenePass) return current(nowMs);
        List<Detection> candidates = new ArrayList<>();
        Detection best = null;
        float bestRisk = 0f;
        for (Detection raw : observations) {
            if (raw == null || raw.classId == 9 || raw.confidence < 0.20f) continue;
            RectF box = raw.box;
            float centerX = box.centerX() / Math.max(1f, frameWidth);
            float bottomY = box.bottom / Math.max(1f, frameHeight);
            float area = box.width() * box.height()
                    / Math.max(1f, frameWidth * (float) frameHeight);
            float corridor = corridorEvidence(centerX, bottomY);
            float size = clamp((area - 0.006f) / 0.115f, 0f, 1f);
            float nearBottom = clamp((bottomY - 0.42f) / 0.55f, 0f, 1f);
            float classWeight = raw.classId == 0 ? 1.08f
                    : raw.classId == 3 || raw.classId == 1 ? 1.03f : 1f;
            float risk = clamp(raw.confidence * 0.30f + corridor * 0.32f
                    + size * 0.26f + nearBottom * 0.12f, 0f, 1f) * classWeight;
            if (corridor < 0.42f || bottomY < 0.48f || risk < 0.53f) continue;
            String label = vietnameseLabel(raw.classId);
            Detection adjusted = new Detection(
                    box, raw.classId, label,
                    clamp(raw.confidence * 0.55f + risk * 0.45f, 0f, 1f),
                    Detection.Kind.ROAD_HAZARD);
            candidates.add(adjusted);
            if (risk > bestRisk) {
                bestRisk = risk;
                best = adjusted;
            }
        }

        if (best == null) {
            consecutiveConfirmations = Math.max(0, consecutiveConfirmations - 1);
            return current(nowMs);
        }
        consecutiveConfirmations++;
        lastSeenAt = nowMs;
        held = candidates;
        confidence = clamp(bestRisk * Math.min(1f, 0.72f + consecutiveConfirmations * 0.14f), 0f, 1f);
        text = confidence >= 0.78f
                ? best.label + " RẤT GẦN"
                : best.label + " PHÍA TRƯỚC";
        return new Result(held, text, confidence);
    }

    public synchronized Result current(long nowMs) {
        if (lastSeenAt == 0L || nowMs - lastSeenAt > HOLD_MS) {
            held = Collections.emptyList();
            text = "";
            confidence = 0f;
            consecutiveConfirmations = 0;
        }
        return new Result(held, text, confidence);
    }

    public synchronized void reset() {
        held = Collections.emptyList();
        text = "";
        confidence = 0f;
        lastSeenAt = 0L;
        consecutiveConfirmations = 0;
    }

    static float corridorEvidence(float centerX, float bottomY) {
        float y = clamp(bottomY, 0f, 1f);
        // Hành lang hình thang hội tụ tại điểm tụ gần giữa ảnh; rộng dần về đáy.
        float halfWidth = 0.08f + Math.max(0f, y - 0.32f) * 0.52f;
        float distance = Math.abs(centerX - 0.50f);
        return clamp(1f - distance / Math.max(0.08f, halfWidth), 0f, 1f);
    }

    private static String vietnameseLabel(int classId) {
        switch (classId) {
            case 0:
                return "NGƯỜI";
            case 1:
                return "XE ĐẠP";
            case 2:
                return "Ô TÔ";
            case 3:
                return "XE MÁY";
            case 5:
                return "XE BUÝT";
            case 7:
                return "XE TẢI";
            default:
                return "VẬT CẢN";
        }
    }

    private static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }

    public static final class Result {
        public final List<Detection> detections;
        public final String text;
        public final float confidence;

        Result(List<Detection> detections, String text, float confidence) {
            this.detections = Collections.unmodifiableList(new ArrayList<>(detections));
            this.text = text == null ? "" : text;
            this.confidence = confidence;
        }
    }
}
