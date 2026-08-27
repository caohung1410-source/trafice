package vn.bachphuc.trafficai;

import android.graphics.RectF;

/**
 * Theo dõi một mục tiêu quan trọng qua nhiều khung hình bằng mô hình chuyển động vận tốc
 * không đổi, ghép IoU/khoảng cách và làm mượt hộp. Đây là lớp nhớ thị giác nhẹ chạy giữa
 * các lượt detector, giúp ứng dụng không đổi sang cột đèn khác chỉ vì một frame nhiễu.
 */
public final class TemporalObjectTracker {
    private static final long MAX_PREDICTION_MS = 2_200L;

    private Detection target;
    private long lastSeenAt;
    private long lastUpdateAt;
    private float velocityX;
    private float velocityY;
    private float velocityW;
    private float velocityH;
    private float confidence;
    private int consecutiveHits;
    private int totalHits;

    public synchronized Detection update(
            Detection observation, long nowMs, int frameWidth, int frameHeight) {
        if (observation == null) return current(nowMs, frameWidth, frameHeight);
        RectF observed = clamp(observation.box, frameWidth, frameHeight);
        if (target == null || nowMs - lastSeenAt > MAX_PREDICTION_MS
                || affinity(observation, nowMs, frameWidth, frameHeight) < 0.10f) {
            target = copy(observation, observed, observation.confidence);
            lastSeenAt = nowMs;
            lastUpdateAt = nowMs;
            velocityX = velocityY = velocityW = velocityH = 0f;
            confidence = observation.confidence;
            consecutiveHits = 1;
            totalHits = 1;
            return target;
        }

        RectF previous = predictBox(nowMs, frameWidth, frameHeight);
        float seconds = Math.max(0.05f, Math.min(1.5f, (nowMs - lastSeenAt) / 1_000f));
        float measuredVx = (observed.centerX() - target.box.centerX()) / seconds;
        float measuredVy = (observed.centerY() - target.box.centerY()) / seconds;
        float measuredVw = (observed.width() - target.box.width()) / seconds;
        float measuredVh = (observed.height() - target.box.height()) / seconds;
        velocityX = velocityX * 0.68f + measuredVx * 0.32f;
        velocityY = velocityY * 0.68f + measuredVy * 0.32f;
        velocityW = velocityW * 0.74f + measuredVw * 0.26f;
        velocityH = velocityH * 0.74f + measuredVh * 0.26f;

        float alpha = clamp(0.48f + observation.confidence * 0.30f, 0.50f, 0.78f);
        RectF smoothed = new RectF(
                lerp(previous.left, observed.left, alpha),
                lerp(previous.top, observed.top, alpha),
                lerp(previous.right, observed.right, alpha),
                lerp(previous.bottom, observed.bottom, alpha));
        smoothed = clamp(smoothed, frameWidth, frameHeight);
        confidence = clamp(confidence * 0.38f + observation.confidence * 0.62f, 0f, 1f);
        target = copy(observation, smoothed, confidence);
        lastSeenAt = nowMs;
        lastUpdateAt = nowMs;
        consecutiveHits++;
        totalHits++;
        return target;
    }

    /** Mục tiêu dự đoán tại thời điểm hiện tại; độ tin cậy giảm dần khi detector mất dấu. */
    public synchronized Detection current(long nowMs, int frameWidth, int frameHeight) {
        if (target == null || nowMs - lastSeenAt > MAX_PREDICTION_MS) {
            reset();
            return null;
        }
        RectF predicted = predictBox(nowMs, frameWidth, frameHeight);
        float freshness = clamp(1f - (nowMs - lastSeenAt) / (float) MAX_PREDICTION_MS, 0f, 1f);
        float predictedConfidence = confidence * (0.42f + freshness * 0.58f);
        return copy(target, predicted, predictedConfidence);
    }

    /** Điểm liên tục 0..1 giữa ứng viên và mục tiêu đang giữ. */
    public synchronized float affinity(
            Detection candidate, long nowMs, int frameWidth, int frameHeight) {
        if (candidate == null || target == null || nowMs - lastSeenAt > MAX_PREDICTION_MS) {
            return 0f;
        }
        RectF predicted = predictBox(nowMs, frameWidth, frameHeight);
        float iou = iou(predicted, candidate.box);
        float dx = predicted.centerX() - candidate.box.centerX();
        float dy = predicted.centerY() - candidate.box.centerY();
        float diagonal = (float) Math.hypot(
                Math.max(predicted.width(), candidate.box.width()),
                Math.max(predicted.height(), candidate.box.height()));
        float distance = (float) Math.hypot(dx, dy) / Math.max(12f, diagonal * 3.2f);
        float proximity = clamp(1f - distance, 0f, 1f);
        float sizeRatio = Math.min(predicted.width(), candidate.box.width())
                / Math.max(1f, Math.max(predicted.width(), candidate.box.width()));
        sizeRatio *= Math.min(predicted.height(), candidate.box.height())
                / Math.max(1f, Math.max(predicted.height(), candidate.box.height()));
        return clamp(iou * 0.52f + proximity * 0.34f + sizeRatio * 0.14f, 0f, 1f);
    }

    /** Vùng nhìn tập trung quanh mục tiêu, có phần dự phòng cho chuyển động của xe/camera. */
    public synchronized RectF focusRegion(long nowMs, int frameWidth, int frameHeight) {
        Detection current = current(nowMs, frameWidth, frameHeight);
        if (current == null || !isLocked(nowMs)) return null;
        RectF box = current.box;
        float wantedWidth = Math.max(frameWidth * 0.28f, box.width() * 9f);
        float wantedHeight = Math.max(frameHeight * 0.42f, box.height() * 7f);
        float centerX = box.centerX() + velocityX * 0.12f;
        float centerY = box.centerY() + velocityY * 0.12f;
        return clamp(new RectF(
                centerX - wantedWidth / 2f,
                centerY - wantedHeight / 2f,
                centerX + wantedWidth / 2f,
                centerY + wantedHeight / 2f), frameWidth, frameHeight);
    }

    public synchronized boolean isLocked(long nowMs) {
        return target != null && consecutiveHits >= 2 && totalHits >= 2
                && nowMs - lastSeenAt <= 1_600L && confidence >= 0.28f;
    }

    public synchronized void miss(long nowMs) {
        if (target == null) return;
        consecutiveHits = Math.max(0, consecutiveHits - 1);
        if (nowMs - lastSeenAt > MAX_PREDICTION_MS) reset();
    }

    public synchronized void reset() {
        target = null;
        lastSeenAt = 0L;
        lastUpdateAt = 0L;
        velocityX = velocityY = velocityW = velocityH = 0f;
        confidence = 0f;
        consecutiveHits = 0;
        totalHits = 0;
    }

    private RectF predictBox(long nowMs, int frameWidth, int frameHeight) {
        float seconds = Math.max(0f, Math.min(0.65f, (nowMs - lastUpdateAt) / 1_000f));
        float centerX = target.box.centerX() + velocityX * seconds;
        float centerY = target.box.centerY() + velocityY * seconds;
        float width = Math.max(4f, target.box.width() + velocityW * seconds);
        float height = Math.max(4f, target.box.height() + velocityH * seconds);
        return clamp(new RectF(
                centerX - width / 2f, centerY - height / 2f,
                centerX + width / 2f, centerY + height / 2f), frameWidth, frameHeight);
    }

    private static Detection copy(Detection source, RectF box, float confidence) {
        return new Detection(box, source.classId, source.label,
                clamp(confidence, 0f, 1f), source.kind);
    }

    private static RectF clamp(RectF source, int width, int height) {
        RectF box = new RectF(source);
        box.left = clamp(box.left, 0f, Math.max(0f, width - 1f));
        box.top = clamp(box.top, 0f, Math.max(0f, height - 1f));
        box.right = clamp(box.right, box.left + 1f, Math.max(box.left + 1f, width));
        box.bottom = clamp(box.bottom, box.top + 1f, Math.max(box.top + 1f, height));
        return box;
    }

    private static float iou(RectF a, RectF b) {
        float left = Math.max(a.left, b.left);
        float top = Math.max(a.top, b.top);
        float right = Math.min(a.right, b.right);
        float bottom = Math.min(a.bottom, b.bottom);
        float intersection = Math.max(0f, right - left) * Math.max(0f, bottom - top);
        float union = a.width() * a.height() + b.width() * b.height() - intersection;
        return intersection / Math.max(1e-5f, union);
    }

    private static float lerp(float start, float end, float amount) {
        return start + (end - start) * amount;
    }

    private static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }
}
