package vn.bachphuc.trafficai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AiResult {
    public final List<Detection> detections;
    public final TrafficState lightState;
    public final float lightConfidence;
    public final Integer countdown;
    public final String signText;
    public final float signConfidence;
    public final long signTrackId;
    public final String hazardText;
    public final float hazardConfidence;
    public final boolean targetLocked;
    public final long inferenceMs;
    public final String engineStatus;

    public AiResult(
            List<Detection> detections,
            TrafficState lightState,
            float lightConfidence,
            Integer countdown,
            String signText,
            float signConfidence,
            long signTrackId,
            String hazardText,
            float hazardConfidence,
            boolean targetLocked,
            long inferenceMs,
            String engineStatus) {
        this.detections = Collections.unmodifiableList(new ArrayList<>(detections));
        this.lightState = lightState;
        this.lightConfidence = lightConfidence;
        this.countdown = countdown;
        this.signText = signText == null ? "" : signText;
        this.signConfidence = signConfidence;
        this.signTrackId = signTrackId;
        this.hazardText = hazardText == null ? "" : hazardText;
        this.hazardConfidence = hazardConfidence;
        this.targetLocked = targetLocked;
        this.inferenceMs = inferenceMs;
        this.engineStatus = engineStatus == null ? "" : engineStatus;
    }

    public static AiResult idle(String status) {
        return new AiResult(
                Collections.emptyList(), TrafficState.UNKNOWN, 0f,
                null, "", 0f, -1L, "", 0f, false, 0L, status);
    }
}
