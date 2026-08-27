package vn.bachphuc.trafficai;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.text.Normalizer;
import java.util.Locale;

/** Vẽ marker giao thông dạng icon, thay cho ghim bản đồ mặc định khó phân biệt. */
public final class TrafficMapIconFactory {
    private TrafficMapIconFactory() {
    }

    public static String cacheKey(String kind, String label) {
        String normalizedKind = kind == null ? "sign" : kind.trim().toLowerCase(Locale.US);
        SpeedSignPolicy.Parsed speed = SpeedSignPolicy.parse(label);
        if (speed != null && !speed.endsLimit) return "speed_" + speed.limitKmh;
        String normalizedLabel = normalize(label);
        if (normalizedKind.contains("light")) return "light";
        if (normalizedKind.contains("camera")) return "camera";
        if (normalizedKind.contains("railway")) return "railway";
        if (normalizedKind.contains("toll")) return "toll";
        if (normalizedLabel.contains("dung") || normalizedLabel.contains("stop")) return "stop";
        if (normalizedLabel.contains("nhuong")) return "yield";
        return "sign";
    }

    public static Bitmap create(String kind, String label, float density) {
        int size = Math.max(58, Math.round(68f * Math.max(1f, density)));
        // Giới hạn bitmap marker để không tăng bộ nhớ trên màn hình có mật độ rất cao.
        size = Math.min(104, size);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        String key = cacheKey(kind, label);
        if (key.startsWith("speed_")) drawSpeed(canvas, paint, size, key.substring(6));
        else if ("light".equals(key)) drawLight(canvas, paint, size);
        else if ("camera".equals(key)) drawCamera(canvas, paint, size);
        else if ("railway".equals(key)) drawRailway(canvas, paint, size);
        else if ("toll".equals(key)) drawToll(canvas, paint, size);
        else if ("stop".equals(key)) drawStop(canvas, paint, size);
        else if ("yield".equals(key)) drawYield(canvas, paint, size);
        else drawWarning(canvas, paint, size);
        return bitmap;
    }

    private static void drawLight(Canvas canvas, Paint paint, int size) {
        float scale = size / 68f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        canvas.drawRoundRect(new RectF(13 * scale, 1 * scale, 55 * scale, 63 * scale),
                15 * scale, 15 * scale, paint);
        paint.setColor(Color.rgb(29, 31, 36));
        canvas.drawRoundRect(new RectF(18 * scale, 4 * scale, 50 * scale, 59 * scale),
                11 * scale, 11 * scale, paint);
        drawLamp(canvas, paint, 34 * scale, 15 * scale, 7 * scale, Color.rgb(239, 45, 49));
        drawLamp(canvas, paint, 34 * scale, 31.5f * scale, 7 * scale, Color.rgb(255, 188, 20));
        drawLamp(canvas, paint, 34 * scale, 48 * scale, 7 * scale, Color.rgb(30, 201, 99));
        paint.setStrokeWidth(5 * scale);
        paint.setColor(Color.WHITE);
        canvas.drawLine(34 * scale, 59 * scale, 34 * scale, 67 * scale, paint);
        paint.setColor(Color.rgb(29, 31, 36));
        paint.setStrokeWidth(3 * scale);
        canvas.drawLine(34 * scale, 59 * scale, 34 * scale, 67 * scale, paint);
    }

    private static void drawLamp(
            Canvas canvas, Paint paint, float x, float y, float radius, int color) {
        paint.setColor(Color.rgb(7, 9, 12));
        canvas.drawCircle(x, y, radius + 2f, paint);
        paint.setColor(color);
        canvas.drawCircle(x, y, radius, paint);
        paint.setColor(Color.argb(150, 255, 255, 255));
        canvas.drawCircle(x - radius * .28f, y - radius * .30f, radius * .24f, paint);
    }

    private static void drawSpeed(Canvas canvas, Paint paint, int size, String number) {
        float center = size / 2f;
        float radius = size * .40f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(center, center, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(size * .105f);
        paint.setColor(Color.rgb(233, 40, 43));
        canvas.drawCircle(center, center, radius - paint.getStrokeWidth() / 2f, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(25, 25, 28));
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(number.length() >= 3 ? size * .31f : size * .38f);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        canvas.drawText(number, center, center - (metrics.ascent + metrics.descent) / 2f, paint);
    }

    private static void drawWarning(Canvas canvas, Paint paint, int size) {
        Path triangle = triangle(size, .10f, .88f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        canvas.drawPath(triangle, paint);
        Path inner = triangle(size, .18f, .80f);
        paint.setColor(Color.rgb(245, 51, 49));
        canvas.drawPath(inner, paint);
        Path face = triangle(size, .26f, .72f);
        paint.setColor(Color.WHITE);
        canvas.drawPath(face, paint);
        drawCenteredText(canvas, paint, size, "!", .47f, .42f, Color.rgb(30, 31, 35));
    }

    private static void drawYield(Canvas canvas, Paint paint, int size) {
        Path outer = invertedTriangle(size, .10f, .90f, .91f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        canvas.drawPath(outer, paint);
        Path red = invertedTriangle(size, .18f, .82f, .82f);
        paint.setColor(Color.rgb(235, 43, 47));
        canvas.drawPath(red, paint);
        Path face = invertedTriangle(size, .27f, .73f, .71f);
        paint.setColor(Color.WHITE);
        canvas.drawPath(face, paint);
    }

    private static void drawStop(Canvas canvas, Paint paint, int size) {
        Path outer = regularPolygon(size / 2f, size / 2f, size * .43f, 8, (float) Math.PI / 8f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        canvas.drawPath(outer, paint);
        Path inner = regularPolygon(size / 2f, size / 2f, size * .36f, 8,
                (float) Math.PI / 8f);
        paint.setColor(Color.rgb(220, 39, 43));
        canvas.drawPath(inner, paint);
        drawCenteredText(canvas, paint, size, "STOP", .50f, .20f, Color.WHITE);
    }

    private static void drawCamera(Canvas canvas, Paint paint, int size) {
        float s = size / 68f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(34 * s, 34 * s, 31 * s, paint);
        paint.setColor(Color.rgb(34, 45, 59));
        canvas.drawRoundRect(new RectF(13 * s, 21 * s, 55 * s, 48 * s),
                7 * s, 7 * s, paint);
        paint.setColor(Color.rgb(54, 166, 242));
        canvas.drawCircle(34 * s, 34 * s, 9 * s, paint);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(34 * s, 34 * s, 4 * s, paint);
        paint.setColor(Color.rgb(34, 45, 59));
        canvas.drawRoundRect(new RectF(21 * s, 15 * s, 37 * s, 24 * s),
                3 * s, 3 * s, paint);
    }

    private static void drawRailway(Canvas canvas, Paint paint, int size) {
        drawWarning(canvas, paint, size);
        float s = size / 68f;
        paint.setColor(Color.rgb(30, 31, 35));
        paint.setStrokeWidth(5 * s);
        canvas.drawLine(22 * s, 22 * s, 47 * s, 49 * s, paint);
        canvas.drawLine(47 * s, 22 * s, 22 * s, 49 * s, paint);
    }

    private static void drawToll(Canvas canvas, Paint paint, int size) {
        drawWarning(canvas, paint, size);
        drawCenteredText(canvas, paint, size, "₫", .49f, .36f, Color.rgb(30, 31, 35));
    }

    private static Path triangle(int size, float topRatio, float bottomRatio) {
        Path path = new Path();
        path.moveTo(size * .5f, size * topRatio);
        path.lineTo(size * bottomRatio, size * .82f);
        path.lineTo(size * (1f - bottomRatio), size * .82f);
        path.close();
        return path;
    }

    private static Path invertedTriangle(
            int size, float leftRatio, float rightRatio, float bottomRatio) {
        Path path = new Path();
        path.moveTo(size * leftRatio, size * .18f);
        path.lineTo(size * rightRatio, size * .18f);
        path.lineTo(size * .5f, size * bottomRatio);
        path.close();
        return path;
    }

    private static Path regularPolygon(
            float centerX, float centerY, float radius, int sides, float rotation) {
        Path path = new Path();
        for (int index = 0; index < sides; index++) {
            double angle = rotation + index * Math.PI * 2d / sides;
            float x = centerX + (float) Math.cos(angle) * radius;
            float y = centerY + (float) Math.sin(angle) * radius;
            if (index == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        path.close();
        return path;
    }

    private static void drawCenteredText(
            Canvas canvas, Paint paint, int size, String text,
            float centerYRatio, float sizeRatio, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(size * sizeRatio);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = size * centerYRatio - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(text, size / 2f, baseline, paint);
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value.toLowerCase(new Locale("vi", "VN")),
                Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd');
    }
}
