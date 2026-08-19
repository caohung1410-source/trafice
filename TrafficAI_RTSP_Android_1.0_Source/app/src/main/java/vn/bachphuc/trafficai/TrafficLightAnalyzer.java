package vn.bachphuc.trafficai;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;

public final class TrafficLightAnalyzer {
    public Result analyze(Bitmap frame, RectF detection) {
        RectF box = expandAndClamp(detection, frame.getWidth(), frame.getHeight(), 0.10f);
        int left = Math.max(0, Math.round(box.left));
        int top = Math.max(0, Math.round(box.top));
        int right = Math.min(frame.getWidth(), Math.round(box.right));
        int bottom = Math.min(frame.getHeight(), Math.round(box.bottom));
        int step = Math.max(1, Math.min(right - left, bottom - top) / 28);

        float red = 0f;
        float yellow = 0f;
        float green = 0f;
        int active = 0;
        int sampled = 0;
        for (int y = top; y < bottom; y += step) {
            float yn = (y - top) / Math.max(1f, bottom - top);
            for (int x = left; x < right; x += step) {
                sampled++;
                int color = frame.getPixel(x, y);
                int r = Color.red(color);
                int g = Color.green(color);
                int b = Color.blue(color);
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                float saturation = max == 0 ? 0f : (max - min) / (float) max;
                float brightness = max / 255f;
                if (brightness < 0.30f || saturation < 0.25f) continue;
                float weight = saturation * brightness;
                if (r > g * 1.22f && r > b * 1.28f) {
                    red += weight * (yn < 0.56f ? 1.12f : 0.92f);
                    active++;
                } else if (g > r * 1.10f && g > b * 1.06f) {
                    green += weight * (yn > 0.42f ? 1.10f : 0.94f);
                    active++;
                } else if (r > 125 && g > 105 && b < Math.min(r, g) * 0.78f) {
                    yellow += weight * (yn > 0.20f && yn < 0.80f ? 1.08f : 0.94f);
                    active++;
                }
            }
        }

        float total = red + yellow + green;
        if (active < Math.max(4, sampled / 120) || total < 0.6f) {
            return new Result(TrafficState.UNKNOWN, 0f);
        }
        TrafficState state = TrafficState.RED;
        float winner = red;
        if (yellow > winner) {
            winner = yellow;
            state = TrafficState.YELLOW;
        }
        if (green > winner) {
            winner = green;
            state = TrafficState.GREEN;
        }
        float dominance = winner / Math.max(0.001f, total);
        float coverage = Math.min(1f, active / Math.max(6f, sampled * 0.08f));
        float confidence = clamp(dominance * 0.78f + coverage * 0.22f, 0f, 1f);
        if (confidence < 0.50f) state = TrafficState.UNKNOWN;
        return new Result(state, confidence);
    }

    private RectF expandAndClamp(RectF source, int width, int height, float factor) {
        RectF result = new RectF(source);
        result.inset(-source.width() * factor, -source.height() * factor);
        result.left = clamp(result.left, 0, width - 1);
        result.top = clamp(result.top, 0, height - 1);
        result.right = clamp(result.right, result.left + 1, width);
        result.bottom = clamp(result.bottom, result.top + 1, height);
        return result;
    }

    private static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }

    public static final class Result {
        public final TrafficState state;
        public final float confidence;

        Result(TrafficState state, float confidence) {
            this.state = state;
            this.confidence = confidence;
        }
    }
}
