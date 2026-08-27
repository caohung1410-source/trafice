package vn.bachphuc.trafficai;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Cache bền vững cho điểm OSM và kết quả tìm kiếm; dùng lại được khi mất mạng. */
public final class MapFeatureStore extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "traffic_navigation_cache.db";
    private static final int DATABASE_VERSION = 1;

    public MapFeatureStore(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE osm_features ("
                + "osm_id TEXT PRIMARY KEY, kind TEXT NOT NULL, label TEXT NOT NULL,"
                + "latitude REAL NOT NULL, longitude REAL NOT NULL, updated INTEGER NOT NULL)");
        database.execSQL("CREATE INDEX osm_position ON osm_features(latitude, longitude)");
        database.execSQL("CREATE TABLE geocode_cache ("
                + "query TEXT PRIMARY KEY, display_name TEXT NOT NULL,"
                + "latitude REAL NOT NULL, longitude REAL NOT NULL, updated INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
    }

    public synchronized void saveFeatures(
            List<NavigationDataService.TrafficFeature> features, long nowMs) {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            for (NavigationDataService.TrafficFeature feature : features) {
                ContentValues values = new ContentValues();
                values.put("osm_id", feature.osmId);
                values.put("kind", feature.kind);
                values.put("label", feature.label);
                values.put("latitude", feature.latitude);
                values.put("longitude", feature.longitude);
                values.put("updated", nowMs);
                database.insertWithOnConflict(
                        "osm_features", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    public synchronized List<NavigationDataService.TrafficFeature> nearbyFeatures(
            double latitude, double longitude, double radiusMeters, int limit) {
        double radiusKm = radiusMeters / 1_000d;
        double latDelta = GeoMath.latitudeDeltaForKm(radiusKm);
        double lonDelta = GeoMath.longitudeDeltaForKm(radiusKm, latitude);
        List<NavigationDataService.TrafficFeature> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "osm_features", null,
                "latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?",
                new String[]{
                        Double.toString(latitude - latDelta), Double.toString(latitude + latDelta),
                        Double.toString(longitude - lonDelta), Double.toString(longitude + lonDelta)},
                null, null, "updated DESC", Integer.toString(Math.max(1, limit)))) {
            while (cursor.moveToNext()) {
                double lat = cursor.getDouble(cursor.getColumnIndexOrThrow("latitude"));
                double lon = cursor.getDouble(cursor.getColumnIndexOrThrow("longitude"));
                if (GeoMath.distanceMeters(latitude, longitude, lat, lon) > radiusMeters) continue;
                result.add(new NavigationDataService.TrafficFeature(
                        cursor.getString(cursor.getColumnIndexOrThrow("osm_id")),
                        cursor.getString(cursor.getColumnIndexOrThrow("kind")),
                        cursor.getString(cursor.getColumnIndexOrThrow("label")),
                        lat, lon));
            }
        }
        return result;
    }

    public synchronized NavigationDataService.Place cachedPlace(String query, long maxAgeMs) {
        String key = normalizeQuery(query);
        try (Cursor cursor = getReadableDatabase().query(
                "geocode_cache", null, "query=?", new String[]{key},
                null, null, null, "1")) {
            if (!cursor.moveToFirst()) return null;
            long updated = cursor.getLong(cursor.getColumnIndexOrThrow("updated"));
            if (System.currentTimeMillis() - updated > maxAgeMs) return null;
            return new NavigationDataService.Place(
                    cursor.getString(cursor.getColumnIndexOrThrow("display_name")),
                    cursor.getDouble(cursor.getColumnIndexOrThrow("latitude")),
                    cursor.getDouble(cursor.getColumnIndexOrThrow("longitude")));
        }
    }

    public synchronized void cachePlace(
            String query, NavigationDataService.Place place, long nowMs) {
        ContentValues values = new ContentValues();
        values.put("query", normalizeQuery(query));
        values.put("display_name", place.displayName);
        values.put("latitude", place.latitude);
        values.put("longitude", place.longitude);
        values.put("updated", nowMs);
        getWritableDatabase().insertWithOnConflict(
                "geocode_cache", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private String normalizeQuery(String value) {
        return value == null ? "" : value.trim().toLowerCase(new Locale("vi", "VN"));
    }
}
