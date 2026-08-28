package vn.bachphuc.trafficai;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

/** Bản đồ dẫn đường tối giản trên Camera HUD, không tạo MapLibre thứ hai. */
public final class CameraHudMiniMapView extends View {
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint roadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint routePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path routePath = new Path();
    private final Path vehiclePath = new Path();

    private float bearingDegrees;
    private boolean gpsReady;
    private boolean navigationActive;
    private String instruction = "CHẠM ĐỂ MỞ BẢN ĐỒ";
    private double distanceMeters = Double.NaN;

    public CameraHudMiniMapView(Context context, AttributeSet attrs) {
        super(context, attrs);
        fillPaint.setStyle(Paint.Style.FILL);
        roadPaint.setStyle(Paint.Style.STROKE);
        roadPaint.setStrokeCap(Paint.Cap.ROUND);
        routePaint.setStyle(Paint.Style.STROKE);
        routePaint.setStrokeCap(Paint.Cap.ROUND);
        routePaint.setStrokeJoin(Paint.Join.ROUND);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        setClickable(true);
        setFocusable(true);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    public void update(
            float bearingDegrees,
            boolean gpsReady,
            boolean navigationActive,
            String instruction,
            double distanceMeters) {
        this.bearingDegrees = Float.isFinite(bearingDegrees) ? bearingDegrees : 0f;
        this.gpsReady = gpsReady;
        this.navigationActive = navigationActive;
        this.instruction = instruction == null || instruction.trim().isEmpty()
                ? "CHẠM ĐỂ MỞ BẢN ĐỒ" : instruction.trim();
        this.distanceMeters = distanceMeters;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        float cx = width / 2f;
        float cy = height / 2f;
        float radius = Math.max(1f, Math.min(width, height) / 2f - dp(4));

        fillPaint.setColor(Color.argb(224, 5, 12, 19));
        fillPaint.setShadowLayer(dp(7), 0f, dp(2), Color.argb(150, 0, 0, 0));
        canvas.drawCircle(cx, cy, radius, fillPaint);
        fillPaint.clearShadowLayer();

        roadPaint.setColor(Color.argb(175, 160, 176, 190));
        roadPaint.setStrokeWidth(dp(10));
        canvas.drawLine(cx - radius * .75f, cy + radius * .25f,
                cx + radius * .72f, cy - radius * .18f, roadPaint);
        canvas.drawLine(cx - radius * .15f, cy - radius * .78f,
                cx + radius * .10f, cy + radius * .78f, roadPaint);

        routePath.reset();
        routePath.moveTo(cx - radius * .58f, cy + radius * .48f);
        routePath.cubicTo(cx - radius * .35f, cy + radius * .10f,
                cx + radius * .04f, cy + radius * .18f,
                cx + radius * .05f, cy - radius * .10f);
        routePath.cubicTo(cx + radius * .06f, cy - radius * .35f,
                cx + radius * .38f, cy - radius * .28f,
                cx + radius * .52f, cy - radius * .56f);
        routePaint.setColor(navigationActive
                ? Color.rgb(67, 235, 244) : Color.rgb(94, 130, 146));
        routePaint.setStrokeWidth(dp(4));
        routePaint.setShadowLayer(dp(5), 0f, 0f,
                navigationActive ? Color.rgb(34, 211, 238) : Color.TRANSPARENT);
        canvas.drawPath(routePath, routePaint);
        routePaint.clearShadowLayer();

        canvas.save();
        canvas.rotate(bearingDegrees, cx, cy);
        vehiclePath.reset();
        vehiclePath.moveTo(cx, cy - dp(15));
        vehiclePath.lineTo(cx - dp(8), cy + dp(10));
        vehiclePath.lineTo(cx, cy + dp(6));
        vehiclePath.lineTo(cx + dp(8), cy + dp(10));
        vehiclePath.close();
        fillPaint.setColor(gpsReady ? Color.rgb(255, 169, 49) : Color.rgb(135, 147, 155));
        canvas.drawPath(vehiclePath, fillPaint);
        canvas.restore();

        roadPaint.setColor(navigationActive
                ? Color.rgb(75, 235, 244) : Color.rgb(75, 116, 133));
        roadPaint.setStrokeWidth(dp(2));
        canvas.drawCircle(cx, cy, radius, roadPaint);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(dp(9));
        String bottomLabel = navigationActive && Double.isFinite(distanceMeters)
                ? formatDistance(distanceMeters)
                : gpsReady ? "GPS" : "ĐANG TÌM GPS";
        canvas.drawText(bottomLabel, cx, height - dp(10), textPaint);

        textPaint.setColor(Color.rgb(199, 239, 246));
        textPaint.setTextSize(dp(6.5f));
        canvas.drawText(shortInstruction(instruction), cx, dp(14), textPaint);
    }

    private String shortInstruction(String value) {
        String text = value.toUpperCase(new Locale("vi", "VN"));
        return text.length() > 24 ? text.substring(0, 23) + "…" : text;
    }

    private String formatDistance(double meters) {
        if (meters < 1_000d) return Math.max(0, Math.round(meters)) + " m";
        return String.format(Locale.US, "%.1f km", meters / 1_000d);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
