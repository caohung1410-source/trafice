package vn.bachphuc.trafficai;

import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.SystemClock;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class AiCoordinator implements AutoCloseable {
    private static final String[] COCO_LABELS = {
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
            "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird",
            "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe",
            "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard",
            "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard",
            "tennis racket", "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl",
            "banana", "apple", "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza",
            "donut", "cake", "chair", "couch", "potted plant", "bed", "dining table", "toilet",
            "tv", "laptop", "mouse", "remote", "keyboard", "cell phone", "microwave", "oven",
            "toaster", "sink", "refrigerator", "book", "clock", "vase", "scissors", "teddy bear",
            "hair drier", "toothbrush"
    };

    private final YoloDetector lightDetector;
    private final YoloDetector signDetector;
    private final TrafficLightAnalyzer lightAnalyzer = new TrafficLightAnalyzer();
    private final SevenSegmentReader sevenSegmentReader = new SevenSegmentReader();
    private final CountdownTracker countdownTracker = new CountdownTracker();
    private final SignConsensusTracker signTracker = new SignConsensusTracker();
    private final Set<Integer> trafficLightClass = new HashSet<>();

    private List<Detection> lastSigns = Collections.emptyList();
    private int frameCounter;
    private int signTile;
    private int errors;

    public AiCoordinator(File lightModel, File signModel, String[] signLabels) throws Exception {
        trafficLightClass.add(9);
        lightDetector = new YoloDetector(
                lightModel, COCO_LABELS, Detection.Kind.TRAFFIC_LIGHT);
        signDetector = new YoloDetector(
                signModel, signLabels, Detection.Kind.TRAFFIC_SIGN);
    }

    public synchronized AiResult analyze(Bitmap frame, long nowMs) {
        long started = SystemClock.elapsedRealtime();
        List<Detection> overlay = new ArrayList<>();
        TrafficState observedState = TrafficState.UNKNOWN;
        float observedStateConfidence = 0f;
        Integer observedCountdown = null;
        float countdownConfidence = 0f;

        try {
            List<Detection> rawLights = lightDetector.detect(frame, 0.24f, trafficLightClass, 12);
            LightCandidate target = chooseTargetLight(frame, rawLights);
            if (target != null) {
                observedState = target.color.state;
                observedStateConfidence = clamp(
                        target.detection.confidence * 0.62f + target.color.confidence * 0.38f,
                        0f, 1f);
                String label = "ĐÈN " + observedState.vi;
                overlay.add(new Detection(
                        target.detection.box,
                        target.detection.classId,
                        label,
                        observedStateConfidence,
                        Detection.Kind.TRAFFIC_LIGHT));

                SevenSegmentReader.Result digit = sevenSegmentReader.read(
                        frame, target.detection.box, observedState);
                observedCountdown = digit.value;
                countdownConfidence = digit.confidence;
                if (digit.box != null && digit.value != null) {
                    overlay.add(new Detection(
                            digit.box, digit.value, digit.value + " giây",
                            digit.confidence, Detection.Kind.COUNTDOWN));
                }
            }
        } catch (Throwable error) {
            errors++;
        }

        CountdownTracker.Result countdown = countdownTracker.update(
                observedState,
                observedStateConfidence,
                observedCountdown,
                countdownConfidence,
                nowMs);

        frameCounter++;
        if (frameCounter % 3 == 1) {
            try {
                List<Detection> signs = detectSignsPrecision(frame);
                lastSigns = signs;
                signTracker.update(signs, nowMs);
            } catch (Throwable error) {
                errors++;
                signTracker.update(Collections.emptyList(), nowMs);
            }
        }
        overlay.addAll(lastSigns);
        SignConsensusTracker.Stable stableSign = signTracker.update(Collections.emptyList(), nowMs);
        String signText = stableSign == null ? "" : stableSign.detection.label;
        float signConfidence = stableSign == null ? 0f : stableSign.confidence;

        long elapsed = SystemClock.elapsedRealtime() - started;
        String status = "AI đèn + biển + LED • " + elapsed + " ms"
                + (errors > 0 ? " • lỗi frame " + errors : "");
        return new AiResult(
                overlay,
                countdown.state,
                observedStateConfidence,
                countdown.visibleNumber,
                signText,
                signConfidence,
                elapsed,
                status);
    }

    private List<Detection> detectSignsPrecision(Bitmap frame) throws Exception {
        RectF tile = nextTile(frame);
        List<Detection> firstPass = signDetector.detect(frame, tile, 0.34f, null, 18);
        if (firstPass.isEmpty()) return Collections.emptyList();

        Detection best = firstPass.get(0);
        for (Detection detection : firstPass) {
            if (detection.confidence > best.confidence) best = detection;
        }
        RectF confirmRegion = expand(best.box, frame.getWidth(), frame.getHeight(), 1.7f);
        Set<Integer> sameClass = new HashSet<>();
        sameClass.add(best.classId);
        List<Detection> secondPass = signDetector.detect(
                frame, confirmRegion, 0.28f, sameClass, 5);

        List<Detection> confirmed = new ArrayList<>();
        for (Detection candidate : firstPass) {
            boolean isConfirmed = candidate.confidence >= 0.72f;
            if (candidate.classId == best.classId) {
                for (Detection second : secondPass) {
                    if (second.classId == candidate.classId && iou(candidate.box, second.box) > 0.05f) {
                        float fused = clamp(candidate.confidence * 0.58f + second.confidence * 0.42f, 0f, 1f);
                        confirmed.add(new Detection(
                                candidate.box, candidate.classId, candidate.label,
                                fused, Detection.Kind.TRAFFIC_SIGN));
                        isConfirmed = true;
                        break;
                    }
                }
            }
            if (isConfirmed && !containsClass(confirmed, candidate.classId)) confirmed.add(candidate);
        }
        return YoloDetector.mergeNms(confirmed, 0.38f, 10);
    }

    private RectF nextTile(Bitmap frame) {
        int index = signTile++ % 4;
        float width = frame.getWidth();
        float height = frame.getHeight();
        if (index == 0) return new RectF(0, 0, width, height);
        if (index == 1) return new RectF(0, 0, width * 0.62f, height * 0.82f);
        if (index == 2) return new RectF(width * 0.19f, 0, width * 0.81f, height * 0.82f);
        return new RectF(width * 0.38f, 0, width, height * 0.82f);
    }

    private LightCandidate chooseTargetLight(Bitmap frame, List<Detection> detections) {
        LightCandidate best = null;
        float bestScore = -1f;
        for (Detection detection : detections) {
            float centerX = detection.box.centerX() / frame.getWidth();
            float centerY = detection.box.centerY() / frame.getHeight();
            if (centerY > 0.88f) continue;
            TrafficLightAnalyzer.Result color = lightAnalyzer.analyze(frame, detection.box);
            if (color.state == TrafficState.UNKNOWN) continue;
            float centerEvidence = clamp(1f - Math.abs(centerX - 0.5f) / 0.5f, 0f, 1f);
            float area = detection.box.width() * detection.box.height()
                    / (frame.getWidth() * (float) frame.getHeight());
            float sizeEvidence = clamp(area / 0.008f, 0f, 1f);
            float score = detection.confidence * 0.48f
                    + color.confidence * 0.30f
                    + centerEvidence * 0.15f
                    + sizeEvidence * 0.07f;
            if (score > bestScore) {
                bestScore = score;
                best = new LightCandidate(detection, color);
            }
        }
        return best;
    }

    public synchronized void reset() {
        countdownTracker.reset();
        signTracker.reset();
        lastSigns = Collections.emptyList();
        frameCounter = 0;
        signTile = 0;
        errors = 0;
    }

    @Override
    public synchronized void close() throws Exception {
        lightDetector.close();
        signDetector.close();
    }

    private static boolean containsClass(List<Detection> detections, int classId) {
        for (Detection detection : detections) {
            if (detection.classId == classId) return true;
        }
        return false;
    }

    private static RectF expand(RectF box, int width, int height, float factor) {
        float cx = box.centerX();
        float cy = box.centerY();
        float halfW = box.width() * factor / 2f;
        float halfH = box.height() * factor / 2f;
        return new RectF(
                Math.max(0, cx - halfW),
                Math.max(0, cy - halfH),
                Math.min(width, cx + halfW),
                Math.min(height, cy + halfH));
    }

    private static float iou(RectF a, RectF b) {
        float left = Math.max(a.left, b.left);
        float top = Math.max(a.top, b.top);
        float right = Math.min(a.right, b.right);
        float bottom = Math.min(a.bottom, b.bottom);
        float intersection = Math.max(0, right - left) * Math.max(0, bottom - top);
        float union = a.width() * a.height() + b.width() * b.height() - intersection;
        return intersection / Math.max(1e-6f, union);
    }

    private static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }

    private static final class LightCandidate {
        final Detection detection;
        final TrafficLightAnalyzer.Result color;

        LightCandidate(Detection detection, TrafficLightAnalyzer.Result color) {
            this.detection = detection;
            this.color = color;
        }
    }
}
