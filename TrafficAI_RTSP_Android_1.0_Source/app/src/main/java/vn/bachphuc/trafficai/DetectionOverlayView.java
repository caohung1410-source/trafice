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
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBackground = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<Detection> detections = Collections.emptyList();
    private int sourceWidth = 1;
    private int sourceHeight = 1;

    public DetectionOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(dp(2.5f));
        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextSize(dp(12));
        labelPaint.setFakeBoldText(true);
        labelBackground.setStyle(Paint.Style.FILL);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    public void setResult(AiResult result, int sourceWidth, int sourceHeight) {
        this.detections = result == null ? Collections.emptyList() : result.detections;
        this.sourceWidth = Math.max(1, sourceWidth);
        this.sourceHeight = Math.max(1, sourceHeight);
        invalidate();
    }

    public void clear() {
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
            boxPaint.setColor(color);
            labelBackground.setColor(Color.argb(205, Color.red(color), Color.green(color), Color.blue(color)));

            RectF src = detection.box;
            RectF dst = new RectF(
                    offsetX + src.left * scale,
                    offsetY + src.top * scale,
                    offsetX + src.right * scale,
                    offsetY + src.bottom * scale);
            canvas.drawRoundRect(dst, dp(5), dp(5), boxPaint);

            String label = detection.label + " "
                    + String.format(Locale.US, "%.0f%%", detection.confidence * 100f);
            float textWidth = labelPaint.measureText(label);
            float top = Math.max(dp(2), dst.top - dp(22));
            RectF tag = new RectF(dst.left, top, dst.left + textWidth + dp(12), top + dp(21));
            canvas.drawRoundRect(tag, dp(4), dp(4), labelBackground);
            canvas.drawText(label, tag.left + dp(6), tag.bottom - dp(6), labelPaint);
        }
    }

    private int colorFor(Detection.Kind kind) {
        if (kind == Detection.Kind.TRAFFIC_LIGHT) return Color.rgb(70, 225, 130);
        if (kind == Detection.Kind.COUNTDOWN) return Color.rgb(255, 198, 70);
        if (kind == Detection.Kind.LEAD_VEHICLE) return Color.rgb(255, 176, 48);
        if (kind == Detection.Kind.ROAD_HAZARD) return Color.rgb(255, 92, 72);
        return Color.rgb(75, 160, 255);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
