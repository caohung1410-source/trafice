package vn.bachphuc.trafficai;

/** Các phép tính GPS nhỏ, độc lập Android để dùng chung và kiểm thử trên JVM. */
public final class GeoMath {
    private static final double EARTH_RADIUS_M = 6_371_000d;

    private GeoMath() {
    }

    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2d) * Math.sin(dLat / 2d)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2d) * Math.sin(dLon / 2d);
        return EARTH_RADIUS_M * 2d * Math.atan2(Math.sqrt(a), Math.sqrt(1d - a));
    }

    public static double headingDifference(double first, double second) {
        double delta = Math.abs(normalizeHeading(first) - normalizeHeading(second));
        return Math.min(delta, 360d - delta);
    }

    public static double normalizeHeading(double value) {
        double normalized = value % 360d;
        return normalized < 0d ? normalized + 360d : normalized;
    }

    public static double averageHeading(double first, double second, double secondWeight) {
        double weight = Math.max(0d, Math.min(1d, secondWeight));
        double a = Math.toRadians(first);
        double b = Math.toRadians(second);
        double x = Math.cos(a) * (1d - weight) + Math.cos(b) * weight;
        double y = Math.sin(a) * (1d - weight) + Math.sin(b) * weight;
        return normalizeHeading(Math.toDegrees(Math.atan2(y, x)));
    }

    public static double latitudeDeltaForKm(double km) {
        return Math.max(0d, km) / 111.32d;
    }

    public static double longitudeDeltaForKm(double km, double latitude) {
        double cos = Math.max(0.15d, Math.abs(Math.cos(Math.toRadians(latitude))));
        return Math.max(0d, km) / (111.32d * cos);
    }

    public static double distanceToSegmentMeters(
            double latitude, double longitude,
            double startLatitude, double startLongitude,
            double endLatitude, double endLongitude) {
        double referenceLat = Math.toRadians(
                (latitude + startLatitude + endLatitude) / 3d);
        double metersPerLon = 111_320d * Math.max(.15d, Math.cos(referenceLat));
        double px = (longitude - startLongitude) * metersPerLon;
        double py = (latitude - startLatitude) * 111_320d;
        double bx = (endLongitude - startLongitude) * metersPerLon;
        double by = (endLatitude - startLatitude) * 111_320d;
        double lengthSquared = bx * bx + by * by;
        if (lengthSquared < .0001d) return Math.hypot(px, py);
        double t = Math.max(0d, Math.min(1d, (px * bx + py * by) / lengthSquared));
        return Math.hypot(px - bx * t, py - by * t);
    }
}
