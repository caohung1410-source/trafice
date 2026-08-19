package vn.bachphuc.trafficai;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Khóa trạng thái bằng nhiều frame và chỉ chấp nhận số thực sự được quan sát.
 * Không tự suy diễn chuỗi đếm ngược khi bảng LED bị mất khỏi khung hình.
 */
public final class CountdownTracker {
    private static final long SIGNAL_WINDOW_MS = 1_200;
    private static final long DIGIT_WINDOW_MS = 1_250;
    private static final long LOST_DIGIT_MS = 1_800;
    private static final int MIN_SIGNAL_VOTES = 2;
    private static final int MIN_DIGIT_VOTES = 2;

    private final Deque<SignalSample> signals = new ArrayDeque<>();
    private final Deque<DigitSample> digits = new ArrayDeque<>();

    private TrafficState stableState = TrafficState.UNKNOWN;
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
        if (stateConfidence >= 0.42f && observedState != TrafficState.UNKNOWN) {
            signals.addLast(new SignalSample(observedState, nowMs));
        }
        trimSignals(nowMs);
        TrafficState votedState = voteSignal();
        boolean stateChanged = votedState != TrafficState.UNKNOWN && votedState != stableState;
        if (stateChanged) {
            stableState = votedState;
            resetDigits();
        } else if (signals.isEmpty()) {
            stableState = TrafficState.UNKNOWN;
            resetDigits();
        }

        boolean stopped = false;
        if (visibleNumber != null && digitConfidence >= 0.42f && !stateChanged) {
            if (visibleNumber == 0) {
                resetDigits();
                stopped = true;
            } else if (visibleNumber > 0 && visibleNumber <= 199 && stableState != TrafficState.UNKNOWN) {
                lastVisibleAt = nowMs;
                digits.addLast(new DigitSample(visibleNumber, nowMs));
                trimDigits(nowMs);
                Integer candidate = voteDigit();
                if (candidate == null && digitConfidence >= 0.78f) {
                    // Ở luồng 1280×720, một lượt AI có thể lâu gần một giây; cho phép
                    // một quan sát rất rõ thay vì bắt buộc nhìn cùng con số hai lần.
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

        return new Result(stableState, acceptedNumber, stateChanged, stopped);
    }

    public synchronized void reset() {
        signals.clear();
        stableState = TrafficState.UNKNOWN;
        resetDigits();
    }

    private boolean isPlausible(int candidate, long nowMs) {
        if (acceptedNumber == null) return true;
        if (candidate > acceptedNumber) return false;
        long elapsed = Math.max(0, nowMs - lastAcceptedAt);
        int maximumDrop = Math.max(2, (int) Math.ceil(elapsed / 700.0) + 1);
        return acceptedNumber - candidate <= maximumDrop;
    }

    private TrafficState voteSignal() {
        Map<TrafficState, Integer> counts = new EnumMap<>(TrafficState.class);
        for (SignalSample sample : signals) {
            counts.put(sample.state, counts.getOrDefault(sample.state, 0) + 1);
        }
        TrafficState best = TrafficState.UNKNOWN;
        int bestCount = 0;
        for (Map.Entry<TrafficState, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return bestCount >= MIN_SIGNAL_VOTES ? best : TrafficState.UNKNOWN;
    }

    private Integer voteDigit() {
        Map<Integer, Integer> counts = new HashMap<>();
        for (DigitSample sample : digits) {
            counts.put(sample.value, counts.getOrDefault(sample.value, 0) + 1);
        }
        Integer best = null;
        int bestCount = 0;
        for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return bestCount >= MIN_DIGIT_VOTES ? best : null;
    }

    private void trimSignals(long nowMs) {
        while (!signals.isEmpty() && nowMs - signals.peekFirst().at > SIGNAL_WINDOW_MS) {
            signals.removeFirst();
        }
        while (signals.size() > 7) signals.removeFirst();
    }

    private void trimDigits(long nowMs) {
        while (!digits.isEmpty() && nowMs - digits.peekFirst().at > DIGIT_WINDOW_MS) {
            digits.removeFirst();
        }
        while (digits.size() > 5) digits.removeFirst();
    }

    private void resetDigits() {
        digits.clear();
        acceptedNumber = null;
        lastAcceptedAt = 0;
        lastVisibleAt = 0;
    }

    private static final class SignalSample {
        final TrafficState state;
        final long at;

        SignalSample(TrafficState state, long at) {
            this.state = state;
            this.at = at;
        }
    }

    private static final class DigitSample {
        final int value;
        final long at;

        DigitSample(int value, long at) {
            this.value = value;
            this.at = at;
        }
    }

    public static final class Result {
        public final TrafficState state;
        public final Integer visibleNumber;
        public final boolean stateChanged;
        public final boolean stopped;

        Result(TrafficState state, Integer visibleNumber, boolean stateChanged, boolean stopped) {
            this.state = state;
            this.visibleNumber = visibleNumber;
            this.stateChanged = stateChanged;
            this.stopped = stopped;
        }
    }
}
