package vn.bachphuc.trafficai;

import android.graphics.RectF;

public final class Detection {
    public enum Kind { TRAFFIC_LIGHT, TRAFFIC_SIGN, COUNTDOWN }

    public final RectF box;
    public final int classId;
    public final String label;
    public final float confidence;
    public final Kind kind;

    public Detection(RectF box, int classId, String label, float confidence, Kind kind) {
        this.box = new RectF(box);
        this.classId = classId;
        this.label = label;
        this.confidence = confidence;
        this.kind = kind;
    }
}
