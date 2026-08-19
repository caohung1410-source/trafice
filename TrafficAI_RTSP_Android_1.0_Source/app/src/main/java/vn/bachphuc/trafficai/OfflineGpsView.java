package vn.bachphuc.trafficai;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/** Lớp phủ GPS cục bộ; đồng thời là nền dự phòng khi MapLibre chưa nạp được. */
public final class OfflineGpsView extends View {
    private static final int MAX_POINTS = 160;
    private static final double METERS_PER_DEGREE_LAT = 111_320d;

    private static final class Point {
        final double lat;
        final double lon;

        Point(double lat, double lon) {
            this.lat = lat;
            this.lon = lon;
        }
    }

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vehiclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Deque<Point> trail = new ArrayDeque<>();

    private double latitude;
    private double longitude;
    private float bearing;
    private int speedKmh;
    private boolean hasFix;
    private boolean basemapVisible;
    private String statusMessage = "ĐANG TÌM VỊ TRÍ";

    public OfflineGpsView(Context context) {
        this(context, null);
    }

    public OfflineGpsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setBackgroundColor(Color.TRANSPARENT);
        gridPaint.setColor(Color.rgb(30, 61, 84));
        gridPaint.setStrokeWidth(1.4f);
        trailPaint.setColor(Color.rgb(50, 210, 150));
        trailPaint.setStrokeWidth(5f);
        trailPaint.setStyle(Paint.Style.STROKE);
        trailPaint.setStrokeCap(Paint.Cap.ROUND);
        vehiclePaint.setColor(Color.rgb(255, 196, 42));
        vehiclePaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(34f);
    }

    public void updateLocation(double lat, double lon, float direction, int speed) {
        latitude = lat;
        longitude = lon;
        bearing = direction;
        speedKmh = Math.max(0, speed);
        hasFix = true;
        Point last = trail.peekLast();
        if (last == null || distanceMeters(last.lat, last.lon, lat, lon) >= 2.5d) {
            trail.addLast(new Point(lat, lon));
            while (trail.size() > MAX_POINTS) trail.removeFirst();
        }
        invalidate();
    }

    public void setStatus(String value) {
        statusMessage = value == null || value.trim().isEmpty() ? "ĐANG TÌM VỊ TRÍ" : value;
        invalidate();
    }

    public void setBasemapVisible(boolean visible) {
        basemapVisible = visible;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (!basemapVisible) canvas.drawColor(Color.rgb(7, 17, 31));
        gridPaint.setAlpha(basemapVisible ? 45 : 255);
        for (int i = 1; i < 8; i++) {
            float x = width * i / 8f;
            float y = height * i / 8f;
            canvas.drawLine(x, 0, x, height, gridPaint);
            canvas.drawLine(0, y, width, y, gridPaint);
        }
        if (!hasFix) {
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(24f);
            canvas.drawText(statusMessage, width / 2f, height / 2f, textPaint);
            textPaint.setTextSize(34f);
            return;
        }

        drawTrail(canvas, width, height);
        drawVehicle(canvas, width / 2f, height / 2f);
        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(speedKmh + " km/h", 24f, 48f, textPaint);
        textPaint.setTextSize(24f);
        canvas.drawText(basemapVisible ? "GPS • MAPLIBRE / OPENSTREETMAP"
                : "GPS OFFLINE • ĐANG CHỜ NỀN ĐƯỜNG", 24f, 82f, textPaint);
        canvas.drawText(String.format(Locale.US, "%.5f, %.5f", latitude, longitude),
                24f, height - 24f, textPaint);
        textPaint.setTextSize(34f);
    }

    private void drawTrail(Canvas canvas, float width, float height) {
        if (trail.size() < 2) return;
        double cosLat = Math.max(0.15d, Math.cos(Math.toRadians(latitude)));
        float metersPerPixel = 1.2f;
        Path path = new Path();
        boolean first = true;
        for (Point point : trail) {
            double east = (point.lon - longitude) * METERS_PER_DEGREE_LAT * cosLat;
            double north = (point.lat - latitude) * METERS_PER_DEGREE_LAT;
            float x = width / 2f + (float) (east / metersPerPixel);
            float y = height / 2f - (float) (north / metersPerPixel);
            if (first) {
                path.moveTo(x, y);
                first = false;
            } else {
                path.lineTo(x, y);
            }
        }
        canvas.drawPath(path, trailPaint);
    }

    private void drawVehicle(Canvas canvas, float cx, float cy) {
        canvas.save();
        canvas.rotate(bearing, cx, cy);
        Path arrow = new Path();
        arrow.moveTo(cx, cy - 28f);
        arrow.lineTo(cx - 19f, cy + 23f);
        arrow.lineTo(cx, cy + 14f);
        arrow.lineTo(cx + 19f, cy + 23f);
        arrow.close();
        canvas.drawPath(arrow, vehiclePaint);
        canvas.restore();
    }

    private double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double north = (lat2 - lat1) * METERS_PER_DEGREE_LAT;
        double east = (lon2 - lon1) * METERS_PER_DEGREE_LAT
                * Math.cos(Math.toRadians((lat1 + lat2) * 0.5d));
        return Math.hypot(north, east);
    }
}
