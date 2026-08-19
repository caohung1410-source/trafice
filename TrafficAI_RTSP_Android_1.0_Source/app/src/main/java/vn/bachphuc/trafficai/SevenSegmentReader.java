package vn.bachphuc.trafficai;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;

/** Fast local reader for one-to-three digit LED countdown panels. */
public final class SevenSegmentReader {
    private static final int MASK_W = 144;
    private static final int MASK_H = 88;
    private static final int[] DIGIT_MASKS = {
            0x3F, // 0: a b c d e f
            0x06, // 1: b c
            0x5B, // 2: a b d e g
            0x4F, // 3: a b c d g
            0x66, // 4: b c f g
            0x6D, // 5: a c d f g
            0x7D, // 6: a c d e f g
            0x07, // 7: a b c
            0x7F, // 8
            0x6F  // 9
    };

    public Result read(Bitmap frame, RectF light, TrafficState state) {
        if (frame == null || light == null || state == TrafficState.UNKNOWN) return Result.none();
        float h = Math.max(12f, light.height());
        List<RectF> candidates = new ArrayList<>();
        candidates.add(clamp(new RectF(
                light.right + h * 0.05f, light.top - h * 0.25f,
                light.right + h * 3.6f, light.bottom + h * 0.25f), frame));
        candidates.add(clamp(new RectF(
                light.left - h * 3.6f, light.top - h * 0.25f,
                light.left - h * 0.05f, light.bottom + h * 0.25f), frame));
        candidates.add(clamp(new RectF(
                light.left - h * 1.4f, light.bottom + h * 0.02f,
                light.right + h * 1.4f, light.bottom + h * 1.65f), frame));

        Result best = Result.none();
        for (RectF candidate : candidates) {
            if (candidate.width() < 8 || candidate.height() < 8) continue;
            Result current = readCandidate(frame, candidate, state);
            if (current.confidence > best.confidence) best = current;
        }
        return best.confidence >= 0.50f ? best : Result.none();
    }

    private Result readCandidate(Bitmap frame, RectF roi, TrafficState state) {
        boolean[] mask = new boolean[MASK_W * MASK_H];
        int lit = 0;
        for (int y = 0; y < MASK_H; y++) {
            int sy = Math.min(frame.getHeight() - 1,
                    Math.max(0, (int) (roi.top + (y + 0.5f) * roi.height() / MASK_H)));
            for (int x = 0; x < MASK_W; x++) {
                int sx = Math.min(frame.getWidth() - 1,
                        Math.max(0, (int) (roi.left + (x + 0.5f) * roi.width() / MASK_W)));
                boolean on = isLed(frame.getPixel(sx, sy), state);
                mask[y * MASK_W + x] = on;
                if (on) lit++;
            }
        }
        if (lit < 22 || lit > MASK_W * MASK_H * 0.45f) return Result.none();

        int[] bounds = litBounds(mask);
        if (bounds == null) return Result.none();
        int left = bounds[0];
        int top = bounds[1];
        int right = bounds[2];
        int bottom = bounds[3];
        int width = right - left + 1;
        int height = bottom - top + 1;
        if (height < 14 || width < 4) return Result.none();

        float aspect = width / (float) height;
        int digitCount = aspect > 1.42f ? 3 : aspect > 0.67f ? 2 : 1;
        int[] digits = new int[digitCount];
        float confidence = 1f;
        for (int i = 0; i < digitCount; i++) {
            int x0 = left + Math.round(i * width / (float) digitCount);
            int x1 = left + Math.round((i + 1) * width / (float) digitCount) - 1;
            Digit digit = classify(mask, x0, top, x1, bottom);
            if (digit.value < 0) return Result.none();
            digits[i] = digit.value;
            confidence = Math.min(confidence, digit.confidence);
        }

        int number = 0;
        for (int digit : digits) number = number * 10 + digit;
        if (number > 199) return Result.none();
        RectF sourceBox = new RectF(
                roi.left + left * roi.width() / MASK_W,
                roi.top + top * roi.height() / MASK_H,
                roi.left + (right + 1) * roi.width() / MASK_W,
                roi.top + (bottom + 1) * roi.height() / MASK_H);
        float occupancy = Math.min(1f, lit / Math.max(1f, width * height * 0.22f));
        confidence = confidence * 0.84f + occupancy * 0.16f;
        return new Result(number, confidence, sourceBox);
    }

    private Digit classify(boolean[] mask, int left, int top, int right, int bottom) {
        int width = Math.max(1, right - left + 1);
        int height = Math.max(1, bottom - top + 1);
        float[][] regions = {
                {.20f, .01f, .80f, .19f}, // a
                {.66f, .10f, .99f, .49f}, // b
                {.66f, .51f, .99f, .90f}, // c
                {.20f, .81f, .80f, .99f}, // d
                {.01f, .51f, .34f, .90f}, // e
                {.01f, .10f, .34f, .49f}, // f
                {.20f, .41f, .80f, .60f}  // g
        };
        float[] scores = new float[7];
        float maximum = 0f;
        for (int i = 0; i < regions.length; i++) {
            float[] r = regions[i];
            scores[i] = occupancy(mask,
                    left + Math.round(r[0] * width), top + Math.round(r[1] * height),
                    left + Math.round(r[2] * width), top + Math.round(r[3] * height));
            maximum = Math.max(maximum, scores[i]);
        }
        if (maximum < 0.10f) return new Digit(-1, 0f);
        float threshold = Math.max(0.09f, Math.min(0.28f, maximum * 0.42f));
        int observed = 0;
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] >= threshold) observed |= (1 << i);
        }

        int best = -1;
        int bestDistance = 8;
        for (int digit = 0; digit < DIGIT_MASKS.length; digit++) {
            int distance = Integer.bitCount(observed ^ DIGIT_MASKS[digit]);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = digit;
            }
        }
        if (bestDistance > 2) return new Digit(-1, 0f);
        float confidence = 1f - bestDistance / 4f;
        return new Digit(best, confidence);
    }

    private float occupancy(boolean[] mask, int left, int top, int right, int bottom) {
        left = Math.max(0, Math.min(MASK_W - 1, left));
        top = Math.max(0, Math.min(MASK_H - 1, top));
        right = Math.max(left + 1, Math.min(MASK_W, right));
        bottom = Math.max(top + 1, Math.min(MASK_H, bottom));
        int on = 0;
        int all = 0;
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                if (mask[y * MASK_W + x]) on++;
                all++;
            }
        }
        return on / Math.max(1f, all);
    }

    private int[] litBounds(boolean[] mask) {
        int left = MASK_W;
        int top = MASK_H;
        int right = -1;
        int bottom = -1;
        int[] column = new int[MASK_W];
        int[] row = new int[MASK_H];
        for (int y = 0; y < MASK_H; y++) {
            for (int x = 0; x < MASK_W; x++) {
                if (!mask[y * MASK_W + x]) continue;
                column[x]++;
                row[y]++;
            }
        }
        for (int x = 0; x < MASK_W; x++) {
            if (column[x] >= 2) {
                left = Math.min(left, x);
                right = Math.max(right, x);
            }
        }
        for (int y = 0; y < MASK_H; y++) {
            if (row[y] >= 2) {
                top = Math.min(top, y);
                bottom = Math.max(bottom, y);
            }
        }
        return right >= left && bottom >= top ? new int[]{left, top, right, bottom} : null;
    }

    private boolean isLed(int color, TrafficState state) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        if (max < 115 || max - min < 25) return false;
        if (state == TrafficState.RED) return r > 140 && r > g * 1.16f && r > b * 1.18f;
        if (state == TrafficState.GREEN) return g > 125 && g > r * 1.06f && g > b * 1.02f;
        if (state == TrafficState.YELLOW) return r > 130 && g > 105 && b < Math.min(r, g) * 0.82f;
        return max > 185 && max - min > 45;
    }

    private RectF clamp(RectF rect, Bitmap bitmap) {
        rect.left = Math.max(0, Math.min(bitmap.getWidth() - 1, rect.left));
        rect.top = Math.max(0, Math.min(bitmap.getHeight() - 1, rect.top));
        rect.right = Math.max(rect.left + 1, Math.min(bitmap.getWidth(), rect.right));
        rect.bottom = Math.max(rect.top + 1, Math.min(bitmap.getHeight(), rect.bottom));
        return rect;
    }

    private static final class Digit {
        final int value;
        final float confidence;

        Digit(int value, float confidence) {
            this.value = value;
            this.confidence = confidence;
        }
    }

    public static final class Result {
        public final Integer value;
        public final float confidence;
        public final RectF box;

        Result(Integer value, float confidence, RectF box) {
            this.value = value;
            this.confidence = confidence;
            this.box = box == null ? null : new RectF(box);
        }

        static Result none() {
            return new Result(null, 0f, null);
        }
    }
}
