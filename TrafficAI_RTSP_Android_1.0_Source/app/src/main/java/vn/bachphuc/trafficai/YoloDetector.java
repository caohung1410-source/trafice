package vn.bachphuc.trafficai;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/** Parser for common Ultralytics YOLOv8/YOLO11 ONNX outputs: [1,C,N] or [1,N,C]. */
public final class YoloDetector implements AutoCloseable {
    private static final int INPUT = 640;
    private static final int MAX_RAW_CANDIDATES = 1_000;

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String inputName;
    private final String[] labels;
    private final Detection.Kind kind;
    private final Bitmap letterbox = Bitmap.createBitmap(INPUT, INPUT, Bitmap.Config.ARGB_8888);
    private final Canvas letterboxCanvas = new Canvas(letterbox);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final int[] pixels = new int[INPUT * INPUT];
    private final FloatBuffer inputBuffer = ByteBuffer
            .allocateDirect(INPUT * INPUT * 3 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer();

    public YoloDetector(File model, String[] labels, Detection.Kind kind) throws OrtException {
        this.environment = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        options.setIntraOpNumThreads(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() - 1)));
        this.session = environment.createSession(model.getAbsolutePath(), options);
        this.inputName = session.getInputNames().iterator().next();
        this.labels = labels.clone();
        this.kind = kind;
    }

    public synchronized List<Detection> detect(
            Bitmap source,
            RectF requestedRegion,
            float threshold,
            Set<Integer> onlyClasses,
            int maxResults) throws OrtException {
        if (source == null || source.isRecycled()) return Collections.emptyList();
        RectF region = normalizeRegion(source, requestedRegion);
        Transform transform = prepare(source, region);

        long[] inputShape = new long[]{1, 3, INPUT, INPUT};
        try (OnnxTensor input = OnnxTensor.createTensor(environment, inputBuffer, inputShape);
             OrtSession.Result result = session.run(Collections.singletonMap(inputName, input))) {
            if (result.size() < 1) return Collections.emptyList();
            OnnxValue value = result.get(0);
            if (!(value instanceof OnnxTensor)) return Collections.emptyList();
            OnnxTensor output = (OnnxTensor) value;
            long[] shape = output.getInfo().getShape();
            if (shape.length != 3) {
                throw new OrtException("YOLO output shape không hỗ trợ");
            }
            FloatBuffer data = output.getFloatBuffer();
            List<Detection> raw = parseOutput(data, shape, transform, threshold, onlyClasses);
            return nms(raw, 0.43f, maxResults);
        }
    }

    public List<Detection> detect(
            Bitmap source,
            float threshold,
            Set<Integer> onlyClasses,
            int maxResults) throws OrtException {
        return detect(source, null, threshold, onlyClasses, maxResults);
    }

    private Transform prepare(Bitmap source, RectF region) {
        float scale = Math.min(INPUT / region.width(), INPUT / region.height());
        float drawW = region.width() * scale;
        float drawH = region.height() * scale;
        float padX = (INPUT - drawW) / 2f;
        float padY = (INPUT - drawH) / 2f;

        letterboxCanvas.drawColor(Color.BLACK);
        RectF dst = new RectF(padX, padY, padX + drawW, padY + drawH);
        Rect src = new Rect(
                Math.max(0, (int) Math.floor(region.left)),
                Math.max(0, (int) Math.floor(region.top)),
                Math.min(source.getWidth(), (int) Math.ceil(region.right)),
                Math.min(source.getHeight(), (int) Math.ceil(region.bottom)));
        letterboxCanvas.drawBitmap(source, src, dst, bitmapPaint);
        letterbox.getPixels(pixels, 0, INPUT, 0, 0, INPUT, INPUT);

        inputBuffer.clear();
        for (int pixel : pixels) inputBuffer.put(Color.red(pixel) / 255f);
        for (int pixel : pixels) inputBuffer.put(Color.green(pixel) / 255f);
        for (int pixel : pixels) inputBuffer.put(Color.blue(pixel) / 255f);
        inputBuffer.rewind();
        return new Transform(region, scale, padX, padY, source.getWidth(), source.getHeight());
    }

    private List<Detection> parseOutput(
            FloatBuffer data,
            long[] shape,
            Transform t,
            float threshold,
            Set<Integer> onlyClasses) {
        int second = Math.toIntExact(shape[1]);
        int third = Math.toIntExact(shape[2]);
        final boolean channelsFirst = second < third;
        final int channels = channelsFirst ? second : third;
        final int candidates = channelsFirst ? third : second;

        int classOffset = 4;
        int objectnessOffset = -1;
        if (channels == labels.length + 5) {
            objectnessOffset = 4;
            classOffset = 5;
        }
        int classCount = Math.min(labels.length, channels - classOffset);
        if (classCount <= 0) return Collections.emptyList();

        List<Detection> detections = new ArrayList<>();
        for (int n = 0; n < candidates; n++) {
            int bestClass = -1;
            float bestScore = -Float.MAX_VALUE;
            for (int c = 0; c < classCount; c++) {
                float score = get(data, channelsFirst, channels, candidates, classOffset + c, n);
                if (score > bestScore) {
                    bestScore = score;
                    bestClass = c;
                }
            }
            if (objectnessOffset >= 0) {
                bestScore *= get(data, channelsFirst, channels, candidates, objectnessOffset, n);
            }
            if (bestScore < threshold) continue;
            if (onlyClasses != null && !onlyClasses.isEmpty() && !onlyClasses.contains(bestClass)) continue;

            float cx = get(data, channelsFirst, channels, candidates, 0, n);
            float cy = get(data, channelsFirst, channels, candidates, 1, n);
            float width = get(data, channelsFirst, channels, candidates, 2, n);
            float height = get(data, channelsFirst, channels, candidates, 3, n);
            if (Math.max(Math.max(cx, cy), Math.max(width, height)) <= 2.5f) {
                cx *= INPUT;
                cy *= INPUT;
                width *= INPUT;
                height *= INPUT;
            }

            float left = (cx - width / 2f - t.padX) / t.scale + t.region.left;
            float top = (cy - height / 2f - t.padY) / t.scale + t.region.top;
            float right = (cx + width / 2f - t.padX) / t.scale + t.region.left;
            float bottom = (cy + height / 2f - t.padY) / t.scale + t.region.top;
            RectF box = clamp(new RectF(left, top, right, bottom), t.sourceWidth, t.sourceHeight);
            if (box.width() < 4 || box.height() < 4) continue;

            String label = bestClass >= 0 && bestClass < labels.length
                    ? labels[bestClass] : "#" + bestClass;
            detections.add(new Detection(box, bestClass, label, bestScore, kind));
            if (detections.size() >= MAX_RAW_CANDIDATES) break;
        }
        return detections;
    }

    private float get(
            FloatBuffer data,
            boolean channelsFirst,
            int channels,
            int candidates,
            int channel,
            int candidate) {
        int index = channelsFirst
                ? channel * candidates + candidate
                : candidate * channels + channel;
        return data.get(index);
    }

    private List<Detection> nms(List<Detection> input, float iouThreshold, int maxResults) {
        input.sort(Comparator.comparingDouble((Detection d) -> d.confidence).reversed());
        List<Detection> kept = new ArrayList<>();
        Set<Integer> suppressed = new HashSet<>();
        for (int i = 0; i < input.size(); i++) {
            if (suppressed.contains(i)) continue;
            Detection candidate = input.get(i);
            kept.add(candidate);
            if (kept.size() >= maxResults) break;
            for (int j = i + 1; j < input.size(); j++) {
                if (suppressed.contains(j)) continue;
                Detection other = input.get(j);
                if (candidate.classId == other.classId && iou(candidate.box, other.box) > iouThreshold) {
                    suppressed.add(j);
                }
            }
        }
        return kept;
    }

    public static List<Detection> mergeNms(List<Detection> input, float iouThreshold, int maxResults) {
        List<Detection> sorted = new ArrayList<>(input);
        sorted.sort(Comparator.comparingDouble((Detection d) -> d.confidence).reversed());
        List<Detection> kept = new ArrayList<>();
        for (Detection candidate : sorted) {
            boolean overlap = false;
            for (Detection previous : kept) {
                if (candidate.classId == previous.classId && iou(candidate.box, previous.box) > iouThreshold) {
                    overlap = true;
                    break;
                }
            }
            if (!overlap) kept.add(candidate);
            if (kept.size() >= maxResults) break;
        }
        return kept;
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

    private static RectF normalizeRegion(Bitmap bitmap, RectF requested) {
        if (requested == null) return new RectF(0, 0, bitmap.getWidth(), bitmap.getHeight());
        return clamp(new RectF(requested), bitmap.getWidth(), bitmap.getHeight());
    }

    private static RectF clamp(RectF box, int width, int height) {
        box.left = Math.max(0, Math.min(width - 1, box.left));
        box.top = Math.max(0, Math.min(height - 1, box.top));
        box.right = Math.max(box.left + 1, Math.min(width, box.right));
        box.bottom = Math.max(box.top + 1, Math.min(height, box.bottom));
        return box;
    }

    @Override
    public synchronized void close() throws OrtException {
        session.close();
        letterbox.recycle();
    }

    private static final class Transform {
        final RectF region;
        final float scale;
        final float padX;
        final float padY;
        final int sourceWidth;
        final int sourceHeight;

        Transform(RectF region, float scale, float padX, float padY, int sourceWidth, int sourceHeight) {
            this.region = new RectF(region);
            this.scale = scale;
            this.padX = padX;
            this.padY = padY;
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
        }
    }
}
