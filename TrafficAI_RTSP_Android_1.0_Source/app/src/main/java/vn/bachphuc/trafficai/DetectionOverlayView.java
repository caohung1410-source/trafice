package vn.bachphuc.trafficai;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class DetectionOverlayView extends View {
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBackground = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<Detection> detections = Collections.emptyList();
    private AiResult result;
    private int sourceWidth = 1;
    private int sourceHeight = 1;

    public DetectionOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(dp(1.3f));
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(dp(5.5f));
        cornerPaint.setStyle(Paint.Style.STROKE);
        cornerPaint.setStrokeCap(Paint.Cap.SQUARE);
        cornerPaint.setStrokeWidth(dp(3.2f));
        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextSize(dp(12));
        labelPaint.setFakeBoldText(true);
        labelBackground.setStyle(Paint.Style.FILL);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    public void setResult(AiResult result, int sourceWidth, int sourceHeight) {
        this.result = result;
        this.detections = result == null ? Collections.emptyList() : result.detections;
        this.sourceWidth = Math.max(1, sourceWidth);
        this.sourceHeight = Math.max(1, sourceHeight);
        invalidate();
    }

    public void clear() {
        result = null;
        detections = Collections.emptyList();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (detections.isEmpty()) return;

        float scale = Math.min(getWidth() / (float) sourceWidth, getHeight() / (float) sourceHeight);
        float drawWidth = sourceWidth * scale;
        float drawHeight = sourceHeight * scale;
        float offsetX = (getWidth() - drawWidth) / 2f;
        float offsetY = (getHeight() - drawHeight) / 2f;

        for (Detection detection : detections) {
            int color = colorFor(detection.kind);
            boxPaint.setColor(Color.argb(
                    185, Color.red(color), Color.green(color), Color.blue(color)));
            glowPaint.setColor(Color.argb(
                    76, Color.red(color), Color.green(color), Color.blue(color)));
            glowPaint.setShadowLayer(dp(7f), 0f, 0f, color);
            cornerPaint.setColor(color);
            labelBackground.setColor(Color.argb(205, Color.red(color), Color.green(color), Color.blue(color)));

            RectF src = detection.box;
            RectF dst = new RectF(
                    offsetX + src.left * scale,
                    offsetY + src.top * scale,
                    offsetX + src.right * scale,
                    offsetY + src.bottom * scale);
            canvas.drawRoundRect(dst, dp(7), dp(7), glowPaint);
            canvas.drawRoundRect(dst, dp(7), dp(7), boxPaint);
            drawCornerBrackets(canvas, dst);

            String label = labelFor(detection);
            float textWidth = labelPaint.measureText(label);
            float top = Math.max(dp(2), dst.top - dp(22));
            float tagLeft = Math.max(dp(2), Math.min(dst.left,
                    getWidth() - textWidth - dp(14)));
            RectF tag = new RectF(tagLeft, top,
                    tagLeft + textWidth + dp(12), top + dp(21));
            canvas.drawRoundRect(tag, dp(4), dp(4), labelBackground);
            canvas.drawText(label, tag.left + dp(6), tag.bottom - dp(6), labelPaint);
        }
    }

    private void drawCornerBrackets(Canvas canvas, RectF box) {
        float length = Math.min(dp(18f), Math.min(box.width(), box.height()) * .28f);
        if (length < dp(4f)) return;
        canvas.drawLine(box.left, box.top, box.left + length, box.top, cornerPaint);
        canvas.drawLine(box.left, box.top, box.left, box.top + length, cornerPaint);
        canvas.drawLine(box.right, box.top, box.right - length, box.top, cornerPaint);
        canvas.drawLine(box.right, box.top, box.right, box.top + length, cornerPaint);
        canvas.drawLine(box.left, box.bottom, box.left + length, box.bottom, cornerPaint);
        canvas.drawLine(box.left, box.bottom, box.left, box.bottom - length, cornerPaint);
        canvas.drawLine(box.right, box.bottom, box.right - length, box.bottom, cornerPaint);
        canvas.drawLine(box.right, box.bottom, box.right, box.bottom - length, cornerPaint);
    }

    private int colorFor(Detection.Kind kind) {
        if (kind == Detection.Kind.TRAFFIC_LIGHT) return Color.rgb(70, 225, 130);
        if (kind == Detection.Kind.COUNTDOWN) return Color.rgb(255, 198, 70);
        if (kind == Detection.Kind.LEAD_VEHICLE && result != null) {
            if (result.distanceState == DistanceWarningState.DANGER) {
                return Color.rgb(255, 76, 64);
            }
            if (result.distanceState == DistanceWarningState.CAUTION) {
                return Color.rgb(255, 151, 38);
            }
            if (result.distanceState == DistanceWarningState.SAFE) {
                return Color.rgb(57, 230, 238);
            }
            return Color.rgb(255, 176, 48);
        }
        if (kind == Detection.Kind.ROAD_HAZARD) return Color.rgb(255, 92, 72);
        return Color.rgb(75, 160, 255);
    }

    private String labelFor(Detection detection) {
        if (detection.kind == Detection.Kind.LEAD_VEHICLE
                && result != null
                && Double.isFinite(result.leadDistanceMeters)) {
            StringBuilder label = new StringBuilder("XE TRƯỚC • ")
                    .append(Math.round(result.leadDistanceMeters)).append(" m");
            if (Double.isFinite(result.ttcSeconds) && result.ttcSeconds <= 9.9d) {
                label.append(" • TTC ")
                        .append(String.format(Locale.US, "%.1f s", result.ttcSeconds));
            }
            return label.toString();
        }
        return detection.label;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
