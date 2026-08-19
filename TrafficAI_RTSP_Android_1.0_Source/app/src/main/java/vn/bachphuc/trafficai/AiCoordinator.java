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

/** Điều phối thị giác 2.3: tracker từng biển, bộ nhớ tọa độ và ưu tiên làn xe. */
public final class AiCoordinator implements AutoCloseable {
    private static final long SIGN_OVERLAY_CACHE_MS = 1_900L;
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

    private final YoloDetector sceneDetector;
    private final YoloDetector signDetector;
    private final TrafficLightAnalyzer lightAnalyzer = new TrafficLightAnalyzer();
    private final SevenSegmentReader sevenSegmentReader = new SevenSegmentReader();
    private final CountdownTracker countdownTracker = new CountdownTracker();
    private final SignConsensusTracker signTracker = new SignConsensusTracker();
    private final TemporalObjectTracker lightTracker = new TemporalObjectTracker();
    private final ForwardHazardAnalyzer hazardAnalyzer = new ForwardHazardAnalyzer();
    private final Set<Integer> sceneClasses = new HashSet<>();

    private List<Detection> lastSigns = Collections.emptyList();
    private long lastSignsAt;
    private int analysisPhase;
    private int scenePassCounter;
    private int signTile;
    private int lightTile;
    private int errors;
    private int lastSignRawCount;
    private String lastVisionMode = "QUÉT";
    private LandmarkHint landmarkHint = LandmarkHint.NONE;
    private LanePreference lanePreference = LanePreference.CENTER;

    public AiCoordinator(File lightModel, File signModel, String[] signLabels) throws Exception {
        // Tận dụng cùng một lượt COCO để thấy đèn và các đối tượng giao thông phía trước.
        Collections.addAll(sceneClasses, 0, 1, 2, 3, 5, 7, 9);
        sceneDetector = new YoloDetector(
                lightModel, COCO_LABELS, Detection.Kind.TRAFFIC_LIGHT);
        signDetector = new YoloDetector(
                signModel, signLabels, Detection.Kind.TRAFFIC_SIGN);
    }

    public synchronized AiResult analyze(Bitmap frame, long nowMs) {
        long started = SystemClock.elapsedRealtime();
        List<Detection> overlay = new ArrayList<>();
        int phase = analysisPhase++;
        LandmarkHint hint = landmarkHint;
        boolean scenePhase = hint.expectsLight()
                ? phase % 3 != 2
                : hint.expectsSign() ? phase % 3 == 0 : (phase & 1) == 0;
        SignalObservation observed = observeTrackedLight(frame, nowMs);
        SignConsensusTracker.Stable stableSign;
        ForwardHazardAnalyzer.Result hazard = hazardAnalyzer.current(nowMs);

        if (scenePhase) {
            try {
                ScenePass scene = detectScenePass(frame, nowMs, hint);
                hazard = hazardAnalyzer.update(scene.roadObjects,
                        frame.getWidth(), frame.getHeight(), nowMs, scene.fullScene);
                LightCandidate target = chooseTargetLight(frame, scene.lights, nowMs);
                if (target != null) {
                    Detection tracked = lightTracker.update(target.detection, nowMs,
                            frame.getWidth(), frame.getHeight());
                    observed = new SignalObservation(
                            tracked,
                            target.color.state,
                            clamp(tracked.confidence * 0.54f
                                    + target.color.confidence * 0.46f, 0f, 1f));
                } else {
                    lightTracker.miss(nowMs);
                }
            } catch (Throwable error) {
                errors++;
                lightTracker.miss(nowMs);
            }
            stableSign = signTracker.update(Collections.emptyList(), nowMs);
        } else {
            lastVisionMode = "BIỂN VN";
            try {
                SignPass signPass = detectSignsAtDistance(frame, hint);
                lastSignRawCount = signPass.signs.size();
                if (!signPass.signs.isEmpty()) {
                    lastSigns = signPass.signs;
                    lastSignsAt = nowMs;
                }
                stableSign = signTracker.update(signPass.signs, nowMs);
                if (signPass.signal != null
                        && (observed == null
                        || signPass.signal.confidence > observed.confidence + 0.08f)) {
                    Detection tracked = lightTracker.update(
                            signPass.signal.detection, nowMs,
                            frame.getWidth(), frame.getHeight());
                    observed = new SignalObservation(
                            tracked,
                            signPass.signal.state,
                            signPass.signal.confidence);
                }
            } catch (Throwable error) {
                errors++;
                stableSign = signTracker.update(Collections.emptyList(), nowMs);
            }
        }

        if (nowMs - lastSignsAt > SIGN_OVERLAY_CACHE_MS) {
            lastSigns = Collections.emptyList();
        }

        TrafficState observedState = observed == null
                ? TrafficState.UNKNOWN : observed.state;
        float observedConfidence = observed == null ? 0f : observed.confidence;
        Integer observedCountdown = null;
        float countdownConfidence = 0f;
        boolean targetLocked = lightTracker.isLocked(nowMs);

        if (observed != null && observed.state != TrafficState.UNKNOWN) {
            overlay.add(new Detection(
                    observed.detection.box,
                    observed.detection.classId,
                    (targetLocked ? "KHÓA " : "THEO DÕI ") + observed.state.vi,
                    observed.confidence,
                    Detection.Kind.TRAFFIC_LIGHT));
            if (targetLocked || observed.confidence >= 0.74f) {
                SevenSegmentReader.Result digit = sevenSegmentReader.read(
                        frame, observed.detection.box, observed.state);
                observedCountdown = digit.value;
                countdownConfidence = digit.confidence;
                if (digit.box != null && digit.value != null) {
                    overlay.add(new Detection(
                            digit.box, digit.value, digit.value + " giây",
                            digit.confidence, Detection.Kind.COUNTDOWN));
                }
            }
        }

        CountdownTracker.Result countdown = countdownTracker.update(
                observedState, observedConfidence,
                observedCountdown, countdownConfidence, nowMs);
        overlay.addAll(lastSigns);
        overlay.addAll(hazard.detections);

        String signText = stableSign == null ? "" : stableSign.detection.label;
        float signConfidence = stableSign == null ? 0f : stableSign.confidence;
        long signTrackId = stableSign == null ? -1L : stableSign.trackId;
        long elapsed = SystemClock.elapsedRealtime() - started;
        String status = lastVisionMode
                + " • LÀN " + lanePreference.vi
                + (targetLocked ? " • KHÓA MỤC TIÊU" : " • ĐANG TÌM")
                + (hint.isActive() ? " • ĐIỂM ĐÃ HỌC "
                + Math.round(hint.distanceMeters) + " m" : "")
                + " • " + elapsed + " ms"
                + " • BIỂN RAW " + lastSignRawCount
                + " TRACK " + signTracker.activeTrackCount()
                + (errors > 0 ? " • lỗi " + errors : "");
        return new AiResult(
                overlay,
                countdown.state,
                countdown.confidence,
                countdown.visibleNumber,
                signText,
                signConfidence,
                signTrackId,
                hazard.text,
                hazard.confidence,
                targetLocked,
                elapsed,
                status);
    }

    private ScenePass detectScenePass(
            Bitmap frame, long nowMs, LandmarkHint hint) throws Exception {
        int pass = scenePassCounter++;
        RectF region = null;
        boolean fullScene = false;
        RectF focus = lightTracker.focusRegion(
                nowMs, frame.getWidth(), frame.getHeight());
        if (focus != null && pass % 4 != 3) {
            region = focus;
            lastVisionMode = "NHÌN TẬP TRUNG";
        } else if (hint.expectsLight() && pass % 4 != 3) {
            region = learnedRegion(frame, hint, .40f, .55f);
            lastVisionMode = "NHỚ VỊ TRÍ ĐÈN";
        } else if (pass % 2 == 0 || focus != null) {
            fullScene = true;
            lastVisionMode = "QUÉT TOÀN CẢNH";
        } else {
            region = nextLightTile(frame);
            lastVisionMode = "QUÉT XA";
        }

        List<Detection> raw = sceneDetector.detect(
                frame, region, fullScene ? 0.16f : 0.12f, sceneClasses, 30);
        List<Detection> lights = new ArrayList<>();
        List<Detection> roadObjects = new ArrayList<>();
        for (Detection detection : raw) {
            if (detection.classId == 9) lights.add(detection);
            else roadObjects.add(detection);
        }
        return new ScenePass(lights, roadObjects, fullScene);
    }

    private SignalObservation observeTrackedLight(Bitmap frame, long nowMs) {
        Detection tracked = lightTracker.current(
                nowMs, frame.getWidth(), frame.getHeight());
        if (tracked == null || tracked.confidence < 0.16f) return null;
        TrafficLightAnalyzer.Result color = lightAnalyzer.analyze(frame, tracked.box);
        if (color.state == TrafficState.UNKNOWN) return null;
        float ageEvidence = lightTracker.isLocked(nowMs) ? 1f : 0.72f;
        float confidence = clamp(tracked.confidence * 0.48f
                + color.confidence * 0.42f + ageEvidence * 0.10f, 0f, 1f);
        return new SignalObservation(tracked, color.state, confidence);
    }

    private SignPass detectSignsAtDistance(Bitmap frame, LandmarkHint hint) throws Exception {
        RectF tile = hint.expectsSign()
                ? learnedRegion(frame, hint, .42f, .58f) : nextSignTile(frame);
        if (hint.expectsSign()) lastVisionMode = "NHỚ VỊ TRÍ BIỂN";
        else if (tile == null) lastVisionMode = "BIỂN TOÀN CẢNH";
        List<Detection> raw = signDetector.detect(
                frame, tile,
                hint.expectsSign() ? 0.14f : tile == null ? 0.17f : 0.16f,
                null, 30);
        List<Detection> signs = new ArrayList<>();
        SignalCandidate signal = null;
        for (Detection detection : raw) {
            if (detection.box.width() < 4f || detection.box.height() < 4f) continue;
            float centerX = detection.box.centerX() / frame.getWidth();
            float centerY = detection.box.centerY() / frame.getHeight();
            boolean signalClass = detection.classId == SIGN_GREEN_LIGHT_CLASS
                    || detection.classId == SIGN_RED_LIGHT_CLASS;
            float geometry = signalClass
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
            if (signalClass) {
                TrafficState state = detection.classId == SIGN_GREEN_LIGHT_CLASS
                        ? TrafficState.GREEN : TrafficState.RED;
                TrafficLightAnalyzer.Result pixel = lightAnalyzer.analyze(frame, detection.box);
                boolean agrees = pixel.state == state;
                boolean noPixelAnswer = pixel.state == TrafficState.UNKNOWN;
                if (!agrees && !noPixelAnswer) continue;
                float confidence = agrees
                        ? clamp(adjustedConfidence * 0.68f
                                + pixel.confidence * 0.32f, 0f, 1f)
                        : adjustedConfidence * 0.68f;
                boolean strongWithoutPixel = noPixelAnswer
                        && adjustedConfidence >= 0.66f && geometry >= 0.70f;
                if ((agrees && confidence >= 0.34f || strongWithoutPixel)
                        && (signal == null || confidence > signal.confidence)) {
                    Detection signalDetection = new Detection(
                            adjusted.box, adjusted.classId, "traffic light",
                            adjusted.confidence, Detection.Kind.TRAFFIC_LIGHT);
                    signal = new SignalCandidate(signalDetection, state, confidence);
                }
                continue;
            }
            signs.add(adjusted);
        }
        return new SignPass(YoloDetector.mergeNms(signs, 0.36f, 16), signal);
    }

    private RectF nextLightTile(Bitmap frame) {
        int slot = lanePreference.scanSlot(lightTile++);
        float width = frame.getWidth();
        float height = frame.getHeight();
        if (slot == 1) {
            return new RectF(width * 0.44f, 0, width, height * 0.84f);
        }
        if (slot == 0) {
            return new RectF(width * 0.22f, 0, width * 0.78f, height * 0.84f);
        }
        if (slot == -1) return new RectF(0, 0, width * 0.56f, height * 0.84f);
        return null;
    }

    private RectF nextSignTile(Bitmap frame) {
        int slot = lanePreference.scanSlot(signTile++);
        float width = frame.getWidth();
        float height = frame.getHeight();
        if (slot == 1) {
            return new RectF(width * 0.44f, 0, width, height * 0.88f);
        }
        if (slot == 0) {
            return new RectF(width * 0.22f, 0, width * 0.78f, height * 0.88f);
        }
        if (slot == -1) {
            return new RectF(0, 0, width * 0.56f, height * 0.88f);
        }
        return null;
    }

    private RectF learnedRegion(
            Bitmap frame, LandmarkHint hint, float normalizedWidth, float normalizedHeight) {
        float width = frame.getWidth();
        float height = frame.getHeight();
        float halfWidth = width * normalizedWidth * .5f;
        float halfHeight = height * normalizedHeight * .5f;
        float centerX = hint.imageX * width;
        float centerY = hint.imageY * height;
        return new RectF(
                Math.max(0f, centerX - halfWidth),
                Math.max(0f, centerY - halfHeight),
                Math.min(width, centerX + halfWidth),
                Math.min(height * .92f, centerY + halfHeight));
    }

    private LightCandidate chooseTargetLight(
            Bitmap frame, List<Detection> detections, long nowMs) {
        LightCandidate best = null;
        float bestScore = -1f;
        boolean locked = lightTracker.isLocked(nowMs);
        for (Detection detection : detections) {
            float centerX = detection.box.centerX() / frame.getWidth();
            float centerY = detection.box.centerY() / frame.getHeight();
            if (centerY > 0.88f) continue;
            TrafficLightAnalyzer.Result color = lightAnalyzer.analyze(frame, detection.box);
            if (color.state == TrafficState.UNKNOWN) continue;
            float geometry = RoadGeometryPrior.trafficLightEvidence(centerX, centerY);
            float direction = RoadGeometryPrior.travelDirectionEvidence(centerX, centerY);
            float laneEvidence = lanePreference.visualEvidence(centerX);
            float adjustedConfidence = RoadGeometryPrior.adjustConfidence(
                    detection.confidence, geometry);
            float area = detection.box.width() * detection.box.height()
                    / (frame.getWidth() * (float) frame.getHeight());
            float sizeEvidence = clamp(area / 0.008f, 0f, 1f);
            float continuity = lightTracker.affinity(
                    detection, nowMs, frame.getWidth(), frame.getHeight());
            float score = adjustedConfidence * 0.31f
                    + color.confidence * 0.29f
                    + geometry * 0.12f
                    + direction * 0.05f
                    + laneEvidence * 0.09f
                    + sizeEvidence * 0.05f
                    + continuity * 0.09f;
            if (locked && continuity < 0.10f) score -= 0.12f;
            if (score > bestScore) {
                bestScore = score;
                Detection adjusted = new Detection(
                        detection.box,
                        detection.classId,
                        detection.label,
                        adjustedConfidence,
                        Detection.Kind.TRAFFIC_LIGHT);
                best = new LightCandidate(adjusted, color);
            }
        }
        return best;
    }

    public synchronized void reset() {
        countdownTracker.reset();
        signTracker.reset();
        lightTracker.reset();
        hazardAnalyzer.reset();
        lastSigns = Collections.emptyList();
        lastSignsAt = 0L;
        analysisPhase = 0;
        scenePassCounter = 0;
        signTile = 0;
        lightTile = 0;
        errors = 0;
        lastSignRawCount = 0;
        lastVisionMode = "QUÉT";
    }

    public synchronized void setLandmarkHint(LandmarkHint hint) {
        landmarkHint = hint == null ? LandmarkHint.NONE : hint;
    }

    public synchronized void setLanePreference(LanePreference preference) {
        lanePreference = preference == null ? LanePreference.CENTER : preference;
    }

    @Override
    public synchronized void close() throws Exception {
        sceneDetector.close();
        signDetector.close();
    }

    private static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }

    private static final class ScenePass {
        final List<Detection> lights;
        final List<Detection> roadObjects;
        final boolean fullScene;

        ScenePass(List<Detection> lights, List<Detection> roadObjects, boolean fullScene) {
            this.lights = lights;
            this.roadObjects = roadObjects;
            this.fullScene = fullScene;
        }
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

    private static final class SignalObservation {
        final Detection detection;
        final TrafficState state;
        final float confidence;

        SignalObservation(Detection detection, TrafficState state, float confidence) {
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
