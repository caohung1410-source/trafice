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
    private static final int SIGN_GREEN_LIGHT_CLASS = 54;
    private static final int SIGN_RED_LIGHT_CLASS = 55;
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
    private int signTile;
    private int lightTile;
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

        Detection targetLightBox = null;
        try {
            List<Detection> rawLights = detectLightsAtDistance(frame);
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
                targetLightBox = target.detection;
            }
        } catch (Throwable error) {
            errors++;
        }

        try {
            SignPass signPass = detectSignsAtDistance(frame);
            lastSigns = signPass.signs;
            signTracker.update(signPass.signs, nowMs);
            if (signPass.signal != null
                    && (observedState == TrafficState.UNKNOWN
                    || signPass.signal.confidence > observedStateConfidence + 0.08f)) {
                observedState = signPass.signal.state;
                observedStateConfidence = signPass.signal.confidence;
                targetLightBox = signPass.signal.detection;
                overlay.removeIf(item -> item.kind == Detection.Kind.TRAFFIC_LIGHT);
                overlay.add(new Detection(
                        signPass.signal.detection.box,
                        signPass.signal.detection.classId,
                        "ĐÈN " + observedState.vi,
                        observedStateConfidence,
                        Detection.Kind.TRAFFIC_LIGHT));
            }
        } catch (Throwable error) {
            errors++;
            lastSigns = Collections.emptyList();
            signTracker.update(Collections.emptyList(), nowMs);
        }

        if (targetLightBox != null && observedState != TrafficState.UNKNOWN) {
            SevenSegmentReader.Result digit = sevenSegmentReader.read(
                    frame, targetLightBox.box, observedState);
            observedCountdown = digit.value;
            countdownConfidence = digit.confidence;
            if (digit.box != null && digit.value != null) {
                overlay.add(new Detection(
                        digit.box, digit.value, digit.value + " giây",
                        digit.confidence, Detection.Kind.COUNTDOWN));
            }
        }

        CountdownTracker.Result countdown = countdownTracker.update(
                observedState,
                observedStateConfidence,
                observedCountdown,
                countdownConfidence,
                nowMs);

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

    private List<Detection> detectLightsAtDistance(Bitmap frame) throws Exception {
        List<Detection> combined = new ArrayList<>();
        combined.addAll(lightDetector.detect(frame, 0.16f, trafficLightClass, 18));
        combined.addAll(lightDetector.detect(
                frame, nextLightTile(frame), 0.11f, trafficLightClass, 12));
        return YoloDetector.mergeNms(combined, 0.36f, 20);
    }

    private SignPass detectSignsAtDistance(Bitmap frame) throws Exception {
        RectF tile = nextSignTile(frame);
        List<Detection> raw = signDetector.detect(frame, tile, 0.19f, null, 24);
        List<Detection> signs = new ArrayList<>();
        SignalCandidate signal = null;
        for (Detection detection : raw) {
            if (detection.box.width() < 5f || detection.box.height() < 5f) continue;
            float centerX = detection.box.centerX() / frame.getWidth();
            float centerY = detection.box.centerY() / frame.getHeight();
            float geometry = detection.classId == SIGN_GREEN_LIGHT_CLASS
                    || detection.classId == SIGN_RED_LIGHT_CLASS
                    ? RoadGeometryPrior.trafficLightEvidence(centerX, centerY)
                    : RoadGeometryPrior.trafficSignEvidence(centerX, centerY);
            float adjustedConfidence = RoadGeometryPrior.adjustConfidence(
                    detection.confidence, geometry);
            Detection adjusted = new Detection(
                    detection.box,
                    detection.classId,
                    detection.label,
                    adjustedConfidence,
                    detection.kind);
            if (detection.classId == SIGN_GREEN_LIGHT_CLASS
                    || detection.classId == SIGN_RED_LIGHT_CLASS) {
                TrafficState state = detection.classId == SIGN_GREEN_LIGHT_CLASS
                        ? TrafficState.GREEN : TrafficState.RED;
                TrafficLightAnalyzer.Result pixel = lightAnalyzer.analyze(frame, detection.box);
                boolean agrees = pixel.state == state;
                boolean noPixelAnswer = pixel.state == TrafficState.UNKNOWN;
                // Nếu màu pixel kết luận ngược model thì bỏ ứng viên để tránh đọc sai đèn.
                if (!agrees && !noPixelAnswer) continue;
                float confidence = agrees
                        ? clamp(adjustedConfidence * 0.70f + pixel.confidence * 0.30f, 0f, 1f)
                        : adjustedConfidence * 0.72f;
                boolean strongWithoutPixel = noPixelAnswer
                        && adjustedConfidence >= 0.62f && geometry >= 0.68f;
                if ((agrees && confidence >= 0.32f || strongWithoutPixel)
                        && (signal == null || confidence > signal.confidence)) {
                    signal = new SignalCandidate(adjusted, state, confidence);
                }
                continue;
            }
            signs.add(adjusted);
        }
        return new SignPass(YoloDetector.mergeNms(signs, 0.36f, 12), signal);
    }

    private RectF nextLightTile(Bitmap frame) {
        // Quét phải gấp đôi vì cột đứng thường nằm bên phải; full-frame ở lượt trên vẫn
        // giữ mọi vị trí. Thứ tự: phải, giữa, phải, trái.
        int index = lightTile++ % 4;
        float width = frame.getWidth();
        float height = frame.getHeight();
        if (index == 0 || index == 2) {
            return new RectF(width * 0.44f, 0, width, height * 0.84f);
        }
        if (index == 1) return new RectF(width * 0.22f, 0, width * 0.78f, height * 0.84f);
        return new RectF(0, 0, width * 0.56f, height * 0.84f);
    }

    private RectF nextSignTile(Bitmap frame) {
        // Mỗi vùng được giữ hai frame cho bộ đồng thuận. Thứ tự ưu tiên:
        // phải, giữa, phải, trái (bên phải chiếm 50% số lượt phóng đại).
        int index = (signTile++ / 2) % 4;
        float width = frame.getWidth();
        float height = frame.getHeight();
        if (index == 0 || index == 2) {
            return new RectF(width * 0.44f, 0, width, height * 0.88f);
        }
        if (index == 1) return new RectF(width * 0.22f, 0, width * 0.78f, height * 0.88f);
        return new RectF(0, 0, width * 0.56f, height * 0.88f);
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
            float geometry = RoadGeometryPrior.trafficLightEvidence(centerX, centerY);
            float adjustedConfidence = RoadGeometryPrior.adjustConfidence(
                    detection.confidence, geometry);
            float area = detection.box.width() * detection.box.height()
                    / (frame.getWidth() * (float) frame.getHeight());
            float sizeEvidence = clamp(area / 0.008f, 0f, 1f);
            float score = adjustedConfidence * 0.40f
                    + color.confidence * 0.32f
                    + geometry * 0.22f
                    + sizeEvidence * 0.06f;
            if (score > bestScore) {
                bestScore = score;
                Detection adjusted = new Detection(
                        detection.box,
                        detection.classId,
                        detection.label,
                        adjustedConfidence,
                        detection.kind);
                best = new LightCandidate(adjusted, color);
            }
        }
        return best;
    }

    public synchronized void reset() {
        countdownTracker.reset();
        signTracker.reset();
        lastSigns = Collections.emptyList();
        signTile = 0;
        lightTile = 0;
        errors = 0;
    }

    @Override
    public synchronized void close() throws Exception {
        lightDetector.close();
        signDetector.close();
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

    private static final class SignalCandidate {
        final Detection detection;
        final TrafficState state;
        final float confidence;

        SignalCandidate(Detection detection, TrafficState state, float confidence) {
            this.detection = detection;
            this.state = state;
            this.confidence = confidence;
        }
    }

    private static final class SignPass {
        final List<Detection> signs;
        final SignalCandidate signal;

        SignPass(List<Detection> signs, SignalCandidate signal) {
            this.signs = signs;
            this.signal = signal;
        }
    }
}
