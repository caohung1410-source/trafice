package vn.bachphuc.trafficai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Tuyến đường độc lập Android để có thể kiểm thử phần hướng dẫn trên JVM. */
public final class RoutePlan {
    public static final class Point {
        public final double latitude;
        public final double longitude;

        public Point(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    public static final class Step {
        public final String instruction;
        public final double latitude;
        public final double longitude;
        public final double distanceMeters;

        public Step(
                String instruction,
                double latitude,
                double longitude,
                double distanceMeters) {
            this.instruction = instruction == null ? "" : instruction;
            this.latitude = latitude;
            this.longitude = longitude;
            this.distanceMeters = Math.max(0d, distanceMeters);
        }
    }

    public final String destinationName;
    public final double destinationLatitude;
    public final double destinationLongitude;
    public final double distanceMeters;
    public final double durationSeconds;
    public final List<Point> geometry;
    public final List<Step> steps;

    public RoutePlan(
            String destinationName,
            double destinationLatitude,
            double destinationLongitude,
            double distanceMeters,
            double durationSeconds,
            List<Point> geometry,
            List<Step> steps) {
        this.destinationName = destinationName == null ? "Điểm đến" : destinationName;
        this.destinationLatitude = destinationLatitude;
        this.destinationLongitude = destinationLongitude;
        this.distanceMeters = Math.max(0d, distanceMeters);
        this.durationSeconds = Math.max(0d, durationSeconds);
        this.geometry = Collections.unmodifiableList(new ArrayList<>(geometry));
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
    }
}
