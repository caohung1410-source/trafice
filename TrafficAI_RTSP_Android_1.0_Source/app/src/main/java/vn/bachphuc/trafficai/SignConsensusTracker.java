package vn.bachphuc.trafficai;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SignConsensusTracker {
    private static final long WINDOW_MS = 3_200;
    private static final long LOST_MS = 4_200;

    private final Map<Integer, Deque<Sample>> samples = new HashMap<>();
    private Stable stable;

    public synchronized Stable update(List<Detection> observations, long nowMs) {
        for (Detection detection : observations) {
            if (detection.confidence < 0.25f) continue;
            Deque<Sample> queue = samples.get(detection.classId);
            if (queue == null) {
                queue = new ArrayDeque<>();
                samples.put(detection.classId, queue);
            }
            Sample previous = queue.peekLast();
            if (previous != null && nowMs - previous.at < 1_500L
                    && spatialAffinity(previous.detection, detection) < 0.22f) {
                // Cùng loại biển nhưng ở hai vị trí khác nhau không được cộng phiếu.
                continue;
            }
            queue.addLast(new Sample(detection, nowMs));
        }

        Stable best = null;
        for (Map.Entry<Integer, Deque<Sample>> entry : samples.entrySet()) {
            Deque<Sample> queue = entry.getValue();
            while (!queue.isEmpty() && nowMs - queue.peekFirst().at > WINDOW_MS) queue.removeFirst();
            if (queue.size() < 2) continue;
            float confidence = 0f;
            Detection last = null;
            for (Sample sample : queue) {
                confidence += sample.detection.confidence;
                last = sample.detection;
            }
            confidence /= queue.size();
            float temporal = Math.min(1f, queue.size() / 3f);
            float fused = confidence * 0.82f + temporal * 0.18f;
            if (fused >= 0.48f && (best == null || fused > best.confidence)) {
                best = new Stable(last, fused, nowMs);
            }
        }
        samples.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        if (best != null) stable = best;
        if (stable != null && nowMs - stable.lastSeenAt > LOST_MS) stable = null;
        return stable;
    }

    public synchronized void reset() {
        samples.clear();
        stable = null;
    }

    private static float spatialAffinity(Detection first, Detection second) {
        float left = Math.max(first.box.left, second.box.left);
        float top = Math.max(first.box.top, second.box.top);
        float right = Math.min(first.box.right, second.box.right);
        float bottom = Math.min(first.box.bottom, second.box.bottom);
        float intersection = Math.max(0f, right - left) * Math.max(0f, bottom - top);
        float union = first.box.width() * first.box.height()
                + second.box.width() * second.box.height() - intersection;
        float iou = intersection / Math.max(1e-5f, union);
        float dx = first.box.centerX() - second.box.centerX();
        float dy = first.box.centerY() - second.box.centerY();
        float diagonal = (float) Math.hypot(
                Math.max(first.box.width(), second.box.width()),
                Math.max(first.box.height(), second.box.height()));
        float proximity = Math.max(0f, 1f - (float) Math.hypot(dx, dy)
                / Math.max(10f, diagonal * 3f));
        return Math.min(1f, iou * 0.58f + proximity * 0.42f);
    }

    private static final class Sample {
        final Detection detection;
        final long at;

        Sample(Detection detection, long at) {
            this.detection = detection;
            this.at = at;
        }
    }

    public static final class Stable {
        public final Detection detection;
        public final float confidence;
        public final long lastSeenAt;

        Stable(Detection detection, float confidence, long lastSeenAt) {
            this.detection = detection;
            this.confidence = confidence;
            this.lastSeenAt = lastSeenAt;
        }
    }
}
