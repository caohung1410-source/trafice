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
        float redX = 0f;
        float redY = 0f;
        float yellowX = 0f;
        float yellowY = 0f;
        float greenX = 0f;
        float greenY = 0f;
        int active = 0;
        int sampled = 0;
        for (int y = top; y < bottom; y += step) {
            float yn = (y - top) / Math.max(1f, bottom - top);
            for (int x = left; x < right; x += step) {
                float xn = (x - left) / Math.max(1f, right - left);
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
                    float weighted = weight * (yn < 0.56f ? 1.12f : 0.92f);
                    red += weighted;
                    redX += xn * weighted;
                    redY += yn * weighted;
                    active++;
                } else if (g > 95 && g > r * 1.16f && b < g * 1.28f) {
                    // Đèn xanh LED có thể ngả cyan nên không bắt buộc G phải lớn hơn B.
                    float weighted = weight * (yn > 0.42f ? 1.10f : 0.94f);
                    green += weighted;
                    greenX += xn * weighted;
                    greenY += yn * weighted;
                    active++;
                } else if (r > 125 && g > 105 && b < Math.min(r, g) * 0.78f) {
                    float weighted = weight * (yn > 0.20f && yn < 0.80f ? 1.08f : 0.94f);
                    yellow += weighted;
                    yellowX += xn * weighted;
                    yellowY += yn * weighted;
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
        float second = state == TrafficState.RED
                ? Math.max(yellow, green)
                : state == TrafficState.YELLOW ? Math.max(red, green) : Math.max(red, yellow);
        float dominance = winner / Math.max(0.001f, total);
        float coverage = Math.min(1f, active / Math.max(6f, sampled * 0.08f));
        float separation = clamp((winner - second) / Math.max(0.001f, winner), 0f, 1f);
        float winnerX = state == TrafficState.RED ? redX / Math.max(0.001f, red)
                : state == TrafficState.YELLOW ? yellowX / Math.max(0.001f, yellow)
                : greenX / Math.max(0.001f, green);
        float winnerY = state == TrafficState.RED ? redY / Math.max(0.001f, red)
                : state == TrafficState.YELLOW ? yellowY / Math.max(0.001f, yellow)
                : greenY / Math.max(0.001f, green);
        float position = positionEvidence(state, winnerX, winnerY,
                Math.max(1f, box.width()), Math.max(1f, box.height()));
        float confidence = clamp(dominance * 0.58f + coverage * 0.18f
                + separation * 0.16f + position * 0.08f, 0f, 1f);
        if (dominance < 0.44f || separation < 0.10f || confidence < 0.52f) {
            state = TrafficState.UNKNOWN;
        }
        return new Result(state, confidence);
    }

    private float positionEvidence(
            TrafficState state, float x, float y, float width, float height) {
        float target = state == TrafficState.RED ? 0.20f
                : state == TrafficState.YELLOW ? 0.50f : 0.80f;
        if (height > width * 1.15f) {
            return clamp(1f - Math.abs(y - target) / 0.72f, 0.35f, 1f);
        }
        if (width > height * 1.15f) {
            return clamp(1f - Math.abs(x - target) / 0.72f, 0.35f, 1f);
        }
        return 0.70f;
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
