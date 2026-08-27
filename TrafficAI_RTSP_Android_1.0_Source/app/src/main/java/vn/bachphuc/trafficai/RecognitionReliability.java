package vn.bachphuc.trafficai;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Cổng an toàn độc lập với Android cho các quyết định có tác động đến người lái.
 * Detector có thể chạy ở ngưỡng nhạy để không bỏ sót, nhưng TTS và giới hạn tốc độ
 * chỉ được phép dùng kết quả đã có đủ bằng chứng hoặc trùng dữ liệu vị trí đã biết.
 */
public final class RecognitionReliability {
    private RecognitionReliability() {
    }

    public static boolean shouldAnnounceSignal(float confidence) {
        return clamp01(confidence) >= .56f;
    }

    public static boolean shouldAnnounceSign(float confidence, boolean mapAgrees) {
        float threshold = mapAgrees ? .38f : .52f;
        return clamp01(confidence) >= threshold;
    }

    public static boolean shouldApplySpeedLimit(float confidence, boolean mapAgrees) {
        float threshold = mapAgrees ? .42f : .60f;
        return clamp01(confidence) >= threshold;
    }

    /** Điểm ổn định hiển thị trên HUD; đây không phải cam kết xác suất đúng tuyệt đối. */
    public static int qualityScore(
            float lightConfidence,
            float signConfidence,
            boolean targetLocked,
            boolean hasCountdown,
            long inferenceMs) {
        float evidence = Math.max(clamp01(lightConfidence), clamp01(signConfidence));
        float score = evidence * .78f;
        if (targetLocked) score += .13f;
        if (hasCountdown) score += .09f;
        if (inferenceMs > 650L) score -= .08f;
        else if (inferenceMs > 420L) score -= .04f;
        return Math.round(clamp01(score) * 100f);
    }

    public static Level qualityLevel(int score) {
        if (score >= 68) return Level.CONFIRMED;
        if (score >= 36) return Level.VERIFYING;
        return Level.SEARCHING;
    }

    /** So khớp mềm nhãn AI với Map Memory/OSM, có bỏ dấu và giữ chữ số tốc độ. */
    public static boolean labelsAgree(String detected, String expected) {
        String first = normalize(detected);
        String second = normalize(expected);
        if (first.length() < 3 || second.length() < 3) return false;
        if (first.equals(second)) return true;

        String firstSpeed = digits(first);
        String secondSpeed = digits(second);
        if (!firstSpeed.isEmpty() && firstSpeed.equals(secondSpeed)
                && (first.contains("toc do") || second.contains("toc do")
                || first.contains("gioi han") || second.contains("gioi han"))) {
            return true;
        }
        return first.length() >= 7 && second.length() >= 7
                && (first.contains(second) || second.contains(first));
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(
                value.toLowerCase(new Locale("vi", "VN")), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd')
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        return normalized.replaceAll("\\s+", " ");
    }

    private static String digits(String value) {
        return value.replaceAll("[^0-9]", "");
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    public enum Level {
        SEARCHING,
        VERIFYING,
        CONFIRMED
    }
}
