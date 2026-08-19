package vn.bachphuc.trafficai;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/** Bộ nhớ dùng chung giữa màn hình điện thoại và Android Auto trong cùng tiến trình. */
public final class CarTelemetryStore {
    public interface Listener {
        void onTelemetryChanged(State state);
    }

    public static final class State {
        public final int speedKmh;
        public final int speedLimitKmh;
        public final String limitSource;
        public final String light;
        public final Integer countdown;
        public final String sign;
        public final String hazard;
        public final String landmark;
        public final boolean targetLocked;
        public final boolean cameraConnected;
        public final boolean aiReady;

        State(
                int speedKmh,
                int speedLimitKmh,
                String limitSource,
                String light,
                Integer countdown,
                String sign,
                String hazard,
                String landmark,
                boolean targetLocked,
                boolean cameraConnected,
                boolean aiReady) {
            this.speedKmh = speedKmh;
            this.speedLimitKmh = speedLimitKmh;
            this.limitSource = limitSource;
            this.light = light;
            this.countdown = countdown;
            this.sign = sign;
            this.hazard = hazard;
            this.landmark = landmark;
            this.targetLocked = targetLocked;
            this.cameraConnected = cameraConnected;
            this.aiReady = aiReady;
        }
    }

    private static final Set<Listener> LISTENERS = new CopyOnWriteArraySet<>();

    private static volatile int speedKmh;
    private static volatile int speedLimitKmh;
    private static volatile String limitSource = "Chưa đặt";
    private static volatile String light = "Chưa thấy";
    private static volatile Integer countdown;
    private static volatile String sign = "Chưa thấy";
    private static volatile String hazard = "Đang quan sát";
    private static volatile String landmark = "Chưa có điểm gần";
    private static volatile boolean targetLocked;
    private static volatile boolean cameraConnected;
    private static volatile boolean aiReady;

    private CarTelemetryStore() {
    }

    public static State snapshot() {
        return new State(
                speedKmh, speedLimitKmh, limitSource, light, countdown, sign,
                hazard, landmark, targetLocked,
                cameraConnected, aiReady);
    }

    public static void updateSpeed(int value) {
        speedKmh = Math.max(0, value);
        notifyListeners();
    }

    public static void updateLimit(int value, String source) {
        speedLimitKmh = Math.max(0, value);
        limitSource = source == null || source.trim().isEmpty() ? "Chưa đặt" : source;
        notifyListeners();
    }

    public static void updateAi(AiResult result) {
        if (result == null) return;
        light = result.lightState == TrafficState.UNKNOWN ? "Chưa chắc" : result.lightState.vi;
        countdown = result.countdown;
        sign = result.signText == null || result.signText.isEmpty() ? "Chưa thấy" : result.signText;
        hazard = result.hazardText == null || result.hazardText.isEmpty()
                ? "Đang quan sát" : result.hazardText;
        targetLocked = result.targetLocked;
        notifyListeners();
    }

    public static void updateConnection(boolean camera, boolean ai) {
        cameraConnected = camera;
        aiReady = ai;
        notifyListeners();
    }

    public static void updateLandmark(LandmarkHint hint) {
        if (hint == null || !hint.isActive()) {
            landmark = "Chưa có điểm gần";
        } else {
            String kind = hint.expectsLight() ? "Đèn" : "Biển";
            landmark = kind + ": " + hint.label + " • "
                    + Math.round(hint.distanceMeters) + " m";
        }
        notifyListeners();
    }

    public static void addListener(Listener listener) {
        if (listener != null) LISTENERS.add(listener);
    }

    public static void removeListener(Listener listener) {
        if (listener != null) LISTENERS.remove(listener);
    }

    private static void notifyListeners() {
        State current = snapshot();
        for (Listener listener : LISTENERS) listener.onTelemetryChanged(current);
    }
}
