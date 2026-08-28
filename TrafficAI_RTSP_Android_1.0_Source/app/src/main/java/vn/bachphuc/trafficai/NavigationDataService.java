package vn.bachphuc.trafficai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Client nhỏ cho Nominatim, Overpass và OSRM; mọi lời gọi phải chạy ngoài main thread. */
public final class NavigationDataService {
    private static final String USER_AGENT =
            "TrafficAI-RTSP/2.6.2 (personal navigation; github.com/caohung1410-source/trafice)";
    private static final int MAX_RESPONSE_CHARS = 5_000_000;
    private static final int OVERPASS_CONNECT_TIMEOUT_MS = 10_000;
    private static final int OVERPASS_READ_TIMEOUT_MS = 28_000;
    private static final String[] PUBLIC_OVERPASS_FALLBACKS = {
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://overpass-api.de/api/interpreter"
    };
    private static long lastNominatimAt;
    private volatile String lastOverpassServer = "";

    public static final class Place {
        public final String displayName;
        public final double latitude;
        public final double longitude;

        public Place(String displayName, double latitude, double longitude) {
            this.displayName = displayName == null ? "Điểm đến" : displayName;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    public static final class TrafficFeature {
        public static final String LIGHT = "LIGHT";
        public static final String SIGN = "SIGN";
        public static final String CAMERA = "CAMERA";
        public static final String RAILWAY = "RAILWAY";
        public static final String TOLL = "TOLL";

        public final String osmId;
        public final String kind;
        public final String label;
        public final double latitude;
        public final double longitude;

        public TrafficFeature(
                String osmId, String kind, String label,
                double latitude, double longitude) {
            this.osmId = osmId;
            this.kind = kind;
            this.label = label;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    public Place searchPlace(
            String rawQuery, double nearLatitude, double nearLongitude,
            String nominatimBaseUrl) throws Exception {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.length() < 2) throw new IOException("Hãy nhập điểm đến cụ thể hơn");
        throttleNominatim();
        String endpoint = normalizeEndpoint(nominatimBaseUrl, "/search");
        double latDelta = 1.5d;
        double lonDelta = 1.5d / Math.max(.25d,
                Math.cos(Math.toRadians(nearLatitude)));
        String viewbox = String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f",
                nearLongitude - lonDelta, nearLatitude + latDelta,
                nearLongitude + lonDelta, nearLatitude - latDelta);
        String url = endpoint
                + "?format=jsonv2&limit=5&addressdetails=1&accept-language=vi"
                + "&countrycodes=vn&viewbox=" + encode(viewbox)
                + "&q=" + encode(query);
        JSONArray result = new JSONArray(requestJson("GET", url, null));
        if (result.length() == 0) throw new IOException("Không tìm thấy điểm đến: " + query);
        JSONObject best = result.getJSONObject(0);
        return new Place(
                best.optString("display_name", query),
                Double.parseDouble(best.getString("lat")),
                Double.parseDouble(best.getString("lon")));
    }

    public List<TrafficFeature> loadTrafficFeatures(
            double latitude, double longitude, String overpassUrl) throws Exception {
        // Overpass chỉ cần tâm gần đúng cho bán kính 5 km. Làm tròn khoảng 1 km để
        // không gửi tọa độ GPS chính xác của người dùng tới máy chủ công cộng.
        double queryLatitude = roundForPublicMapQuery(latitude);
        double queryLongitude = roundForPublicMapQuery(longitude);
        String query = String.format(Locale.US,
                "[out:json][timeout:30];("
                        + "node(around:5000,%.7f,%.7f)[\"highway\"=\"traffic_signals\"];"
                        + "node(around:5000,%.7f,%.7f)[\"traffic_sign\"];"
                        + "node(around:5000,%.7f,%.7f)[\"highway\"=\"stop\"];"
                        + "node(around:5000,%.7f,%.7f)[\"highway\"=\"give_way\"];"
                        + "node(around:5000,%.7f,%.7f)[\"highway\"=\"speed_camera\"];"
                        + "node(around:5000,%.7f,%.7f)[\"enforcement\"=\"maxspeed\"];"
                        + "node(around:5000,%.7f,%.7f)[\"railway\"=\"level_crossing\"];"
                        + "node(around:5000,%.7f,%.7f)[\"barrier\"=\"toll_booth\"];"
                        + ");out body 300;",
                queryLatitude, queryLongitude, queryLatitude, queryLongitude,
                queryLatitude, queryLongitude, queryLatitude, queryLongitude,
                queryLatitude, queryLongitude, queryLatitude, queryLongitude,
                queryLatitude, queryLongitude, queryLatitude, queryLongitude);
        String body = "data=" + encode(query);
        JSONObject root = requestOverpassWithFallback(overpassUrl, query, body);
        JSONArray elements = root.optJSONArray("elements");
        List<TrafficFeature> features = new ArrayList<>();
        if (elements == null) return features;
        for (int index = 0; index < elements.length(); index++) {
            JSONObject element = elements.optJSONObject(index);
            if (element == null || !element.has("lat") || !element.has("lon")) continue;
            JSONObject tags = element.optJSONObject("tags");
            if (tags == null) tags = new JSONObject();
            String highway = tags.optString("highway", "");
            String railway = tags.optString("railway", "");
            String barrier = tags.optString("barrier", "");
            String enforcement = tags.optString("enforcement", "");
            boolean light = "traffic_signals".equals(highway);
            boolean camera = "speed_camera".equals(highway) || "maxspeed".equals(enforcement);
            boolean railwayCrossing = "level_crossing".equals(railway);
            boolean toll = "toll_booth".equals(barrier);
            String kind = light ? TrafficFeature.LIGHT
                    : camera ? TrafficFeature.CAMERA
                    : railwayCrossing ? TrafficFeature.RAILWAY
                    : toll ? TrafficFeature.TOLL : TrafficFeature.SIGN;
            String label = light ? "Cột/điểm đèn tín hiệu OSM"
                    : camera ? "Camera tốc độ / giám sát OSM"
                    : railwayCrossing ? "Giao cắt đường sắt OSM"
                    : toll ? "Trạm thu phí OSM" : signLabel(tags, highway);
            String id = element.optString("type", "node") + element.optLong("id", index);
            features.add(new TrafficFeature(
                    id, kind, label,
                    element.optDouble("lat"), element.optDouble("lon")));
        }
        return features;
    }

    public String getLastOverpassServer() {
        return lastOverpassServer;
    }

    private JSONObject requestOverpassWithFallback(
            String configuredUrl, String query, String body) throws Exception {
        List<String> endpoints = overpassEndpoints(configuredUrl);
        List<String> failures = new ArrayList<>();
        for (String endpoint : endpoints) {
            try {
                // GET tránh lỗi một số mạng di động/proxy chặn POST tới Overpass.
                String response = requestJson(
                        "GET", endpoint + "?data=" + encode(query), null,
                        OVERPASS_CONNECT_TIMEOUT_MS, OVERPASS_READ_TIMEOUT_MS);
                JSONObject root = new JSONObject(response);
                if (!root.has("elements")) throw new IOException("phản hồi thiếu elements");
                lastOverpassServer = URI.create(endpoint).getHost();
                return root;
            } catch (Throwable getError) {
                failures.add(shortEndpointError(endpoint, getError));
                // Với cụm public, timeout/quá tải thì chuyển ngay sang máy chủ khác,
                // không lặp lại cùng server thêm gần 30 giây bằng POST.
                if (endpoints.size() > 1 && !shouldRetryAsPost(getError)) continue;
                try {
                    // POST là đường lui cho máy chủ/proxy giới hạn chiều dài URL GET.
                    String response = requestJson(
                            "POST", endpoint, body,
                            OVERPASS_CONNECT_TIMEOUT_MS, OVERPASS_READ_TIMEOUT_MS);
                    JSONObject root = new JSONObject(response);
                    if (!root.has("elements")) throw new IOException("phản hồi thiếu elements");
                    lastOverpassServer = URI.create(endpoint).getHost();
                    return root;
                } catch (Throwable postError) {
                    failures.add(shortEndpointError(endpoint, postError));
                }
            }
        }
        throw new IOException("Các máy chủ Overpass đang bận hoặc bị mạng chặn ("
                + String.join("; ", failures) + ")");
    }

    private boolean shouldRetryAsPost(Throwable error) {
        String message = error == null ? "" : String.valueOf(error.getMessage());
        return message.contains("HTTP 405")
                || message.contains("HTTP 413")
                || message.contains("HTTP 414")
                || message.contains("HTTP 431")
                || message.contains("HTTP 501");
    }

    private List<String> overpassEndpoints(String configuredUrl) throws IOException {
        String primary = validateUrl(configuredUrl);
        Set<String> unique = new LinkedHashSet<>();
        unique.add(primary);
        String host = URI.create(primary).getHost();
        if (isKnownPublicOverpassHost(host)) {
            for (String fallback : PUBLIC_OVERPASS_FALLBACKS) unique.add(fallback);
        }
        return new ArrayList<>(unique);
    }

    private boolean isKnownPublicOverpassHost(String host) {
        if (host == null) return false;
        String normalized = host.toLowerCase(Locale.US);
        return normalized.endsWith("overpass-api.de")
                || normalized.equals("overpass.kumi.systems")
                || normalized.equals("overpass.private.coffee");
    }

    private String shortEndpointError(String endpoint, Throwable error) {
        String host;
        try {
            host = URI.create(endpoint).getHost();
        } catch (Throwable ignored) {
            host = "máy chủ";
        }
        String message = error == null ? "lỗi không rõ" : error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = error == null ? "lỗi không rõ" : error.getClass().getSimpleName();
        }
        message = message.replaceAll("[\\r\\n]+", " ").trim();
        if (message.length() > 80) message = message.substring(0, 80);
        return host + ": " + message;
    }

    private double roundForPublicMapQuery(double value) {
        return Math.round(value * 100d) / 100d;
    }

    public RoutePlan route(
            double startLatitude, double startLongitude,
            Place destination, String osrmBaseUrl) throws Exception {
        String endpoint = normalizeEndpoint(osrmBaseUrl, "/route/v1/driving");
        String coordinates = String.format(Locale.US, "%.7f,%.7f;%.7f,%.7f",
                startLongitude, startLatitude,
                destination.longitude, destination.latitude);
        String url = endpoint + "/" + coordinates
                + "?alternatives=false&steps=true&geometries=geojson&overview=full";
        JSONObject root = new JSONObject(requestJson("GET", url, null));
        if (!"Ok".equals(root.optString("code"))) {
            throw new IOException("Dịch vụ định tuyến trả về " + root.optString("code"));
        }
        JSONArray routes = root.optJSONArray("routes");
        if (routes == null || routes.length() == 0) {
            throw new IOException("Không tìm được tuyến đường phù hợp");
        }
        JSONObject route = routes.getJSONObject(0);
        List<RoutePlan.Point> geometry = parseGeometry(
                route.getJSONObject("geometry").getJSONArray("coordinates"));
        List<RoutePlan.Step> steps = parseSteps(route.optJSONArray("legs"));
        if (steps.isEmpty()) {
            steps.add(new RoutePlan.Step(
                    "Đi tới " + destination.displayName,
                    destination.latitude, destination.longitude,
                    route.optDouble("distance")));
        }
        return new RoutePlan(
                destination.displayName,
                destination.latitude,
                destination.longitude,
                route.optDouble("distance"),
                route.optDouble("duration"),
                geometry,
                steps);
    }

    private List<RoutePlan.Point> parseGeometry(JSONArray coordinates) {
        List<RoutePlan.Point> result = new ArrayList<>();
        for (int index = 0; index < coordinates.length(); index++) {
            JSONArray point = coordinates.optJSONArray(index);
            if (point == null || point.length() < 2) continue;
            result.add(new RoutePlan.Point(point.optDouble(1), point.optDouble(0)));
        }
        return result;
    }

    private List<RoutePlan.Step> parseSteps(JSONArray legs) {
        List<RoutePlan.Step> result = new ArrayList<>();
        if (legs == null) return result;
        for (int legIndex = 0; legIndex < legs.length(); legIndex++) {
            JSONArray steps = legs.optJSONObject(legIndex).optJSONArray("steps");
            if (steps == null) continue;
            for (int stepIndex = 0; stepIndex < steps.length(); stepIndex++) {
                JSONObject step = steps.optJSONObject(stepIndex);
                JSONObject maneuver = step == null ? null : step.optJSONObject("maneuver");
                JSONArray location = maneuver == null ? null : maneuver.optJSONArray("location");
                if (location == null || location.length() < 2) continue;
                String instruction = NavigationInstruction.fromOsrm(
                        maneuver.optString("type"), maneuver.optString("modifier"),
                        step.optString("name"), maneuver.optInt("exit", 0));
                result.add(new RoutePlan.Step(
                        instruction,
                        location.optDouble(1), location.optDouble(0),
                        step.optDouble("distance")));
            }
        }
        return result;
    }

    private String signLabel(JSONObject tags, String highway) {
        if ("stop".equals(highway)) return "Biển STOP (OSM)";
        if ("give_way".equals(highway)) return "Biển nhường đường (OSM)";
        String raw = tags.optString("traffic_sign", "");
        String maxspeed = tags.optString("maxspeed", "");
        if (!maxspeed.isEmpty()) return "Giới hạn tốc độ " + maxspeed + " (OSM)";
        if (raw.isEmpty()) return "Biển báo giao thông OSM";
        return "Biển " + raw.replace(';', ',') + " (OSM)";
    }

    private synchronized void throttleNominatim() throws InterruptedException {
        long now = System.currentTimeMillis();
        long wait = 1_050L - (now - lastNominatimAt);
        if (wait > 0L) Thread.sleep(wait);
        lastNominatimAt = System.currentTimeMillis();
    }

    private String requestJson(String method, String rawUrl, String body) throws Exception {
        return requestJson(method, rawUrl, body, 12_000, 20_000);
    }

    private String requestJson(
            String method, String rawUrl, String body,
            int connectTimeoutMs, int readTimeoutMs) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(validateUrl(rawUrl))
                .toURL().openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(connectTimeoutMs);
        connection.setReadTimeout(readTimeoutMs);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Accept-Language", "vi-VN,vi;q=0.9");
        connection.setRequestProperty("Accept-Encoding", "identity");
        if (body != null) {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty(
                    "Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
        }
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String response = readLimited(stream);
        connection.disconnect();
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code + (response.isEmpty() ? "" : ": "
                    + response.substring(0, Math.min(180, response.length()))));
        }
        return response;
    }

    private String readLimited(InputStream stream) throws IOException {
        if (stream == null) return "";
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8_192];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                if (builder.length() + read > MAX_RESPONSE_CHARS) {
                    throw new IOException("Phản hồi dịch vụ bản đồ quá lớn");
                }
                builder.append(buffer, 0, read);
            }
        }
        return builder.toString();
    }

    private String normalizeEndpoint(String raw, String path) throws IOException {
        String value = validateUrl(raw);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value.endsWith(path) ? value : value + path;
    }

    private String validateUrl(String raw) throws IOException {
        String value = raw == null ? "" : raw.trim();
        if (!value.startsWith("https://")) {
            throw new IOException("Dịch vụ bản đồ phải dùng HTTPS");
        }
        return value;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
