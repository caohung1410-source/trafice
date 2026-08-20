package vn.bachphuc.trafficai;

/** Chính sách xác nhận biển độc lập Android để kiểm thử ngưỡng trên JVM. */
public final class SignDecisionPolicy {
    private SignDecisionPolicy() {
    }

    public static Decision evaluate(
            int classVotes, int totalSamples, float averageConfidence, float strongestConfidence) {
        if (classVotes < 2 || totalSamples < 2) return Decision.REJECTED;
        float average = clamp(averageConfidence);
        float strongest = clamp(strongestConfidence);
        float ratio = classVotes / (float) Math.max(1, totalSamples);

        // Biển rất rõ chỉ cần hai lần nhìn độc lập. Ngưỡng 0,66 đã tính đến prior hình học
        // chỉ được phép giảm nhẹ độ tin cậy gốc.
        boolean strongPair = classVotes >= 2
                && ratio >= .66f
                && strongest >= .66f
                && average >= .38f;
        // Biển xa được giữ ở ngưỡng thấp hơn nhưng phải có đa số phiếu cùng lớp trên
        // cùng một track không gian, tránh hạ ngưỡng toàn cục rồi đọc nhầm quảng cáo.
        boolean temporalMajority = classVotes >= 3
                && ratio >= .66f
                && average >= .20f;
        // Ở khoảng cách xa model thường chỉ đạt 0,17–0,20. Chỉ chấp nhận ngưỡng này
        // khi cùng một track có ít nhất bốn phiếu và chiếm đa số rõ ràng.
        boolean distantMajority = classVotes >= 4
                && ratio >= .66f
                && average >= .17f;
        if (!strongPair && !temporalMajority && !distantMajority) {
            return Decision.REJECTED;
        }

        float temporal = Math.min(1f, classVotes / 4f);
        float fused = clamp(average * .80f + temporal * .20f);
        return new Decision(true, fused);
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    public static final class Decision {
        static final Decision REJECTED = new Decision(false, 0f);

        public final boolean confirmed;
        public final float confidence;

        Decision(boolean confirmed, float confidence) {
            this.confirmed = confirmed;
            this.confidence = confidence;
        }
    }
}
