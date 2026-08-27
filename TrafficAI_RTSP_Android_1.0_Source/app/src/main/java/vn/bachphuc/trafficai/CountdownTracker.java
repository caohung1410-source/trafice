package vn.bachphuc.trafficai;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Bộ nhớ quyết định cho màu đèn và số giây. Mẫu mới được cân theo độ tin cậy/độ mới;
 * kết quả chỉ đổi khi có đồng thuận và chuỗi số phải giảm theo thời gian hợp lý.
 */
public final class CountdownTracker {
    private static final long SIGNAL_WINDOW_MS = 1_200L;
    private static final long LOST_SIGNAL_HOLD_MS = 1_850L;
    private static final long DIGIT_WINDOW_MS = 1_450L;
    private static final long LOST_DIGIT_MS = 1_650L;
    private static final int MIN_DIGIT_VOTES = 2;

    private final Deque<SignalSample> signals = new ArrayDeque<>();
    private final Deque<DigitSample> digits = new ArrayDeque<>();

    private TrafficState stableState = TrafficState.UNKNOWN;
    private float stableConfidence;
    private long lastStableEvidenceAt;
    private Integer acceptedNumber;
    private long lastAcceptedAt;
    private long lastVisibleAt;

    public synchronized Result update(
            TrafficState observedState,
            float stateConfidence,
            Integer visibleNumber,
            float digitConfidence,
            long nowMs) {
        if (observedState == null) observedState = TrafficState.UNKNOWN;
        if (stateConfidence >= 0.46f && observedState != TrafficState.UNKNOWN) {
            signals.addLast(new SignalSample(observedState, stateConfidence, nowMs));
        }
        trimSignals(nowMs);
        SignalVote vote = voteSignal(nowMs);
        TrafficState votedState = vote.state;
        // Khi đã khóa một màu, phải có ít nhất ba quan sát độc lập mới được đổi màu.
        // Một frame xanh/đỏ giả từ biển quảng cáo hoặc đèn hậu không thể lật trạng thái.
        boolean switchConfirmed = stableState == TrafficState.UNKNOWN
                || votedState == stableState || vote.count >= 3;
        boolean stateChanged = votedState != TrafficState.UNKNOWN
                && votedState != stableState && switchConfirmed;
        if (stateChanged) {
            stableState = votedState;
            stableConfidence = vote.confidence;
            lastStableEvidenceAt = nowMs;
            resetDigits();
        } else if (votedState == stableState && votedState != TrafficState.UNKNOWN) {
            stableConfidence = stableConfidence * 0.36f + vote.confidence * 0.64f;
            lastStableEvidenceAt = nowMs;
        } else if (signals.isEmpty() && nowMs - lastStableEvidenceAt > LOST_SIGNAL_HOLD_MS) {
            stableState = TrafficState.UNKNOWN;
            stableConfidence = 0f;
            resetDigits();
        } else {
            stableConfidence *= 0.94f;
        }

        boolean stopped = false;
        if (visibleNumber != null && digitConfidence >= 0.44f && !stateChanged
                && observedState == stableState) {
            if (visibleNumber == 0) {
                resetDigits();
                stopped = true;
            } else if (visibleNumber > 0 && visibleNumber <= 199
                    && stableState != TrafficState.UNKNOWN) {
                lastVisibleAt = nowMs;
                digits.addLast(new DigitSample(visibleNumber, digitConfidence, nowMs));
                trimDigits(nowMs);
                Integer candidate = voteDigit(nowMs);
                if (candidate == null && digitConfidence >= 0.90f && signals.size() >= 3) {
                    candidate = visibleNumber;
                }
                if (candidate != null && isPlausible(candidate, nowMs)) {
                    acceptedNumber = candidate;
                    lastAcceptedAt = nowMs;
                }
            }
        } else {
            trimDigits(nowMs);
        }

        if (acceptedNumber != null && nowMs - lastVisibleAt > LOST_DIGIT_MS) {
            acceptedNumber = null;
            digits.clear();
            stopped = true;
        }
        return new Result(stableState, stableConfidence,
                acceptedNumber, stateChanged, stopped);
    }

    public synchronized void reset() {
        signals.clear();
        stableState = TrafficState.UNKNOWN;
        stableConfidence = 0f;
        lastStableEvidenceAt = 0L;
        resetDigits();
    }

    private boolean isPlausible(int candidate, long nowMs) {
        if (acceptedNumber == null) return true;
        if (candidate > acceptedNumber) return false;
        long elapsed = Math.max(0L, nowMs - lastAcceptedAt);
        int expectedDrop = (int) Math.floor(elapsed / 1_000.0);
        int actualDrop = acceptedNumber - candidate;
        return actualDrop <= Math.max(2, expectedDrop + 2);
    }

    private SignalVote voteSignal(long nowMs) {
        Map<TrafficState, Float> weights = new EnumMap<>(TrafficState.class);
        Map<TrafficState, Integer> counts = new EnumMap<>(TrafficState.class);
        for (SignalSample sample : signals) {
            float recency = clamp(1f - (nowMs - sample.at) / (float) SIGNAL_WINDOW_MS,
                    0.25f, 1f);
            float weight = sample.confidence * (0.55f + recency * 0.45f);
            weights.put(sample.state, weights.getOrDefault(sample.state, 0f) + weight);
            counts.put(sample.state, counts.getOrDefault(sample.state, 0) + 1);
        }
        TrafficState best = TrafficState.UNKNOWN;
        float bestWeight = 0f;
        float secondWeight = 0f;
        int bestCount = 0;
        for (Map.Entry<TrafficState, Float> entry : weights.entrySet()) {
            if (entry.getValue() > bestWeight) {
                secondWeight = bestWeight;
                bestWeight = entry.getValue();
                best = entry.getKey();
                bestCount = counts.getOrDefault(entry.getKey(), 0);
            } else if (entry.getValue() > secondWeight) {
                secondWeight = entry.getValue();
            }
        }
        float separation = (bestWeight - secondWeight) / Math.max(0.01f, bestWeight);
        float confidence = clamp(bestWeight / 2.2f, 0f, 1f)
                * (0.72f + separation * 0.28f);
        boolean enough = bestCount >= 2 && bestWeight >= 0.78f && separation >= 0.18f;
        return enough
                ? new SignalVote(best, confidence, bestCount)
                : new SignalVote(TrafficState.UNKNOWN, 0f, 0);
    }

    private Integer voteDigit(long nowMs) {
        Map<Integer, Float> weights = new HashMap<>();
        Map<Integer, Integer> counts = new HashMap<>();
        for (DigitSample sample : digits) {
            float recency = clamp(1f - (nowMs - sample.at) / (float) DIGIT_WINDOW_MS,
                    0.30f, 1f);
            weights.put(sample.value, weights.getOrDefault(sample.value, 0f)
                    + sample.confidence * recency);
            counts.put(sample.value, counts.getOrDefault(sample.value, 0) + 1);
        }
        Integer best = null;
        float bestWeight = 0f;
        for (Map.Entry<Integer, Float> entry : weights.entrySet()) {
            if (counts.getOrDefault(entry.getKey(), 0) >= MIN_DIGIT_VOTES
                    && entry.getValue() > bestWeight) {
                bestWeight = entry.getValue();
                best = entry.getKey();
            }
        }
        return bestWeight >= 0.72f ? best : null;
    }

    private void trimSignals(long nowMs) {
        while (!signals.isEmpty() && nowMs - signals.peekFirst().at > SIGNAL_WINDOW_MS) {
            signals.removeFirst();
        }
        while (signals.size() > 8) signals.removeFirst();
    }

    private void trimDigits(long nowMs) {
        while (!digits.isEmpty() && nowMs - digits.peekFirst().at > DIGIT_WINDOW_MS) {
            digits.removeFirst();
        }
        while (digits.size() > 6) digits.removeFirst();
    }

    private void resetDigits() {
        digits.clear();
        acceptedNumber = null;
        lastAcceptedAt = 0L;
        lastVisibleAt = 0L;
    }

    private static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }

    private static final class SignalSample {
        final TrafficState state;
        final float confidence;
        final long at;

        SignalSample(TrafficState state, float confidence, long at) {
            this.state = state;
            this.confidence = confidence;
            this.at = at;
        }
    }

    private static final class SignalVote {
        final TrafficState state;
        final float confidence;
        final int count;

        SignalVote(TrafficState state, float confidence, int count) {
            this.state = state;
            this.confidence = confidence;
            this.count = count;
        }
    }

    private static final class DigitSample {
        final int value;
        final float confidence;
        final long at;

        DigitSample(int value, float confidence, long at) {
            this.value = value;
            this.confidence = confidence;
            this.at = at;
        }
    }

    public static final class Result {
        public final TrafficState state;
        public final float confidence;
        public final Integer visibleNumber;
        public final boolean stateChanged;
        public final boolean stopped;

        Result(TrafficState state, float confidence, Integer visibleNumber,
               boolean stateChanged, boolean stopped) {
            this.state = state;
            this.confidence = confidence;
            this.visibleNumber = visibleNumber;
            this.stateChanged = stateChanged;
            this.stopped = stopped;
        }
    }
}
