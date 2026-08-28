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

        // Biển rất rõ chỉ cần hai lần nhìn độc lập. Hai phiếu phải thống nhất hoàn toàn;
        // detector nhạy vẫn được giữ để tracking nhưng chưa được đọc thành cảnh báo.
        boolean strongPair = classVotes >= 2
                && ratio >= .99f
                && strongest >= .70f
                && average >= .44f;
        // Biển xa được giữ ở ngưỡng thấp hơn nhưng phải có đa số phiếu cùng lớp trên
        // cùng một track không gian, tránh hạ ngưỡng toàn cục rồi đọc nhầm quảng cáo.
        boolean temporalMajority = classVotes >= 3
                && ratio >= .74f
                && average >= .24f;
        // Ở khoảng cách xa model thường chỉ đạt 0,17–0,20. Chỉ chấp nhận ngưỡng này
        // khi cùng một track có ít nhất bốn phiếu và chiếm đa số rõ ràng.
        boolean distantMajority = classVotes >= 4
                && ratio >= .74f
                && average >= .19f;
        if (!strongPair && !temporalMajority && !distantMajority) {
            return Decision.REJECTED;
        }

        float temporal = Math.min(1f, classVotes / 5f);
        float agreement = clamp(ratio);
        float fused = clamp(average * .70f + temporal * .18f + agreement * .12f);
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
