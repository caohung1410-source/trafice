package vn.bachphuc.trafficai;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Bộ nhớ tọa độ cục bộ. Không lưu ảnh camera, credential, màu đèn hay số đếm. */
public final class LandmarkMemoryStore extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "traffic_landmarks.db";
    private static final int DATABASE_VERSION = 1;
    private static final long PENDING_WINDOW_MS = 6_000L;
    private static final long COMMIT_COOLDOWN_MS = 15_000L;
    private static final double MERGE_RADIUS_M = 45d;

    public static final class Landmark {
        public final long id;
        public final String type;
        public final String label;
        public final double latitude;
        public final double longitude;
        public final float heading;
        public final float imageX;
        public final float imageY;
        public final float confidence;
        public final int confirmations;
        public final long lastSeen;

        Landmark(
                long id, String type, String label,
                double latitude, double longitude, float heading,
                float imageX, float imageY, float confidence,
                int confirmations, long lastSeen) {
            this.id = id;
            this.type = type;
            this.label = label;
            this.latitude = latitude;
            this.longitude = longitude;
            this.heading = heading;
            this.imageX = imageX;
            this.imageY = imageY;
            this.confidence = confidence;
            this.confirmations = confirmations;
            this.lastSeen = lastSeen;
        }
    }

    private static final class Pending {
        double latitude;
        double longitude;
        float heading;
        long lastAt;
        int hits;
    }

    private final Map<String, Pending> pending = new HashMap<>();
    private final Map<String, Long> lastCommitted = new HashMap<>();

    public LandmarkMemoryStore(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE landmarks ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "type TEXT NOT NULL,"
                + "label TEXT NOT NULL,"
                + "latitude REAL NOT NULL,"
                + "longitude REAL NOT NULL,"
                + "heading REAL NOT NULL,"
                + "image_x REAL NOT NULL,"
                + "image_y REAL NOT NULL,"
                + "confidence REAL NOT NULL,"
                + "confirmations INTEGER NOT NULL DEFAULT 1,"
                + "last_seen INTEGER NOT NULL)");
        database.execSQL("CREATE INDEX landmark_position ON landmarks(latitude, longitude)");
        database.execSQL("CREATE INDEX landmark_kind ON landmarks(type, label)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        // Phiên bản đầu tiên. Các nâng cấp sau sẽ migrate thay vì xóa dữ liệu đã học.
    }

    /** Trả landmark khi một quan sát đã đủ ba frame và vừa được ghi/gộp. */
    public synchronized Landmark observe(
            String type,
            String label,
            double latitude,
            double longitude,
            float heading,
            float imageX,
            float imageY,
            float confidence,
            long nowMs) {
        if ((!LandmarkHint.TYPE_LIGHT.equals(type) && !LandmarkHint.TYPE_SIGN.equals(type))
                || label == null || label.trim().isEmpty()
                || confidence < .45f) return null;
        String normalizedLabel = label.trim();
        String key = type + "|" + normalizedLabel;
        Pending sample = pending.get(key);
        if (sample == null
                || nowMs - sample.lastAt > PENDING_WINDOW_MS
                || GeoMath.distanceMeters(sample.latitude, sample.longitude,
                latitude, longitude) > 24d
                || GeoMath.headingDifference(sample.heading, heading) > 45d) {
            sample = new Pending();
            sample.latitude = latitude;
            sample.longitude = longitude;
            sample.heading = heading;
            sample.hits = 1;
            pending.put(key, sample);
        } else {
            sample.hits++;
        }
        sample.lastAt = nowMs;
        if (sample.hits < 3) return null;

        long last = lastCommitted.containsKey(key) ? lastCommitted.get(key) : 0L;
        if (nowMs - last < COMMIT_COOLDOWN_MS) return null;
        lastCommitted.put(key, nowMs);
        sample.hits = 0;
        return upsert(type, normalizedLabel, latitude, longitude, heading,
                imageX, imageY, confidence, nowMs);
    }

    public synchronized LandmarkHint findNearby(
            double latitude, double longitude, float heading, double radiusMeters) {
        double radiusKm = Math.max(20d, radiusMeters) / 1_000d;
        double latDelta = GeoMath.latitudeDeltaForKm(radiusKm);
        double lonDelta = GeoMath.longitudeDeltaForKm(radiusKm, latitude);
        String selection = "latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?";
        String[] args = {
                Double.toString(latitude - latDelta), Double.toString(latitude + latDelta),
                Double.toString(longitude - lonDelta), Double.toString(longitude + lonDelta)
        };
        Landmark best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        try (Cursor cursor = getReadableDatabase().query(
                "landmarks", null, selection, args, null, null,
                "confirmations DESC, last_seen DESC", "80")) {
            while (cursor.moveToNext()) {
                Landmark candidate = read(cursor);
                double distance = GeoMath.distanceMeters(
                        latitude, longitude, candidate.latitude, candidate.longitude);
                if (distance > radiusMeters) continue;
                double headingDelta = GeoMath.headingDifference(heading, candidate.heading);
                if (headingDelta > 50d) continue;
                double score = distance + headingDelta * 1.25d
                        - Math.min(25d, candidate.confirmations * 3d);
                if (score < bestScore) {
                    best = candidate;
                    bestScore = score;
                }
            }
        }
        if (best == null) return LandmarkHint.NONE;
        double distance = GeoMath.distanceMeters(
                latitude, longitude, best.latitude, best.longitude);
        return new LandmarkHint(best.id, best.type, best.label,
                best.imageX, best.imageY, distance, best.confirmations);
    }

    public synchronized List<Landmark> listRecent(int limit) {
        List<Landmark> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "landmarks", null, null, null, null, null,
                "confirmations DESC, last_seen DESC", Integer.toString(Math.max(1, limit)))) {
            while (cursor.moveToNext()) result.add(read(cursor));
        }
        return result;
    }

    public synchronized int count() {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM landmarks", null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    private Landmark upsert(
            String type, String label,
            double latitude, double longitude, float heading,
            float imageX, float imageY, float confidence, long nowMs) {
        Landmark match = null;
        try (Cursor cursor = getReadableDatabase().query(
                "landmarks", null, "type=? AND label=?",
                new String[]{type, label}, null, null, "last_seen DESC", "60")) {
            while (cursor.moveToNext()) {
                Landmark candidate = read(cursor);
                if (GeoMath.distanceMeters(latitude, longitude,
                        candidate.latitude, candidate.longitude) <= MERGE_RADIUS_M
                        && GeoMath.headingDifference(heading, candidate.heading) <= 45d) {
                    match = candidate;
                    break;
                }
            }
        }

        ContentValues values = new ContentValues();
        if (match == null) {
            values.put("type", type);
            values.put("label", label);
            values.put("latitude", latitude);
            values.put("longitude", longitude);
            values.put("heading", heading);
            values.put("image_x", clamp(imageX));
            values.put("image_y", clamp(imageY));
            values.put("confidence", clamp(confidence));
            values.put("confirmations", 1);
            values.put("last_seen", nowMs);
            long id = getWritableDatabase().insertOrThrow("landmarks", null, values);
            return new Landmark(id, type, label, latitude, longitude, heading,
                    clamp(imageX), clamp(imageY), clamp(confidence), 1, nowMs);
        }

        int confirmations = match.confirmations + 1;
        float weight = 1f / Math.min(12, confirmations);
        double mergedLat = match.latitude * (1d - weight) + latitude * weight;
        double mergedLon = match.longitude * (1d - weight) + longitude * weight;
        float mergedHeading = (float) GeoMath.averageHeading(match.heading, heading, weight);
        float mergedX = match.imageX * (1f - weight) + clamp(imageX) * weight;
        float mergedY = match.imageY * (1f - weight) + clamp(imageY) * weight;
        float mergedConfidence = Math.max(match.confidence * .98f, clamp(confidence));
        values.put("latitude", mergedLat);
        values.put("longitude", mergedLon);
        values.put("heading", mergedHeading);
        values.put("image_x", mergedX);
        values.put("image_y", mergedY);
        values.put("confidence", mergedConfidence);
        values.put("confirmations", confirmations);
        values.put("last_seen", nowMs);
        getWritableDatabase().update(
                "landmarks", values, "id=?", new String[]{Long.toString(match.id)});
        return new Landmark(match.id, type, label, mergedLat, mergedLon, mergedHeading,
                mergedX, mergedY, mergedConfidence, confirmations, nowMs);
    }

    private Landmark read(Cursor cursor) {
        return new Landmark(
                cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                cursor.getString(cursor.getColumnIndexOrThrow("type")),
                cursor.getString(cursor.getColumnIndexOrThrow("label")),
                cursor.getDouble(cursor.getColumnIndexOrThrow("latitude")),
                cursor.getDouble(cursor.getColumnIndexOrThrow("longitude")),
                cursor.getFloat(cursor.getColumnIndexOrThrow("heading")),
                cursor.getFloat(cursor.getColumnIndexOrThrow("image_x")),
                cursor.getFloat(cursor.getColumnIndexOrThrow("image_y")),
                cursor.getFloat(cursor.getColumnIndexOrThrow("confidence")),
                cursor.getInt(cursor.getColumnIndexOrThrow("confirmations")),
                cursor.getLong(cursor.getColumnIndexOrThrow("last_seen")));
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
