package vn.bachphuc.trafficai;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Đọc giới hạn tốc độ từ nhãn model sau khi tracker đã xác nhận cùng một biển. */
public final class SpeedSignPolicy {
    private static final Pattern LIMIT_PATTERN = Pattern.compile(
            "(?:gioi han toc do|toc do toi da)\\s*(\\d{1,3})");

    private SpeedSignPolicy() {
    }

    public static Parsed parse(String label) {
        if (label == null) return null;
        String normalized = normalize(label);
        if (normalized.startsWith("het gioi han toc do")
                || normalized.startsWith("het han che toc do")) {
            return new Parsed(true, 0);
        }
        Matcher matcher = LIMIT_PATTERN.matcher(normalized);
        if (!matcher.find()) return null;
        try {
            int value = Integer.parseInt(matcher.group(1));
            if (value < 10 || value > 130) return null;
            return new Parsed(false, value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String normalize(String value) {
        String decomposed = Normalizer.normalize(
                value.trim().toLowerCase(new Locale("vi", "VN")),
                Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "")
                .replace('đ', 'd')
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    public static final class Parsed {
        public final boolean endsLimit;
        public final int limitKmh;

        Parsed(boolean endsLimit, int limitKmh) {
            this.endsLimit = endsLimit;
            this.limitKmh = limitKmh;
        }
    }
}
