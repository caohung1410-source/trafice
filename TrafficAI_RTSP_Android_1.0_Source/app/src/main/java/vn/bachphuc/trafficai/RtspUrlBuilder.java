package vn.bachphuc.trafficai;

import java.net.URI;
import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;
import java.util.Locale;

public final class RtspUrlBuilder {
    private RtspUrlBuilder() {}

    public static String build(
            String fullUrl,
            String host,
            String portText,
            String username,
            String password,
            String path) {
        String direct = safe(fullUrl).trim();
        if (!direct.isEmpty()) {
            validate(direct);
            return direct;
        }

        HostPort cleanAddress = normalizeHost(host, portText);
        String cleanHost = cleanAddress.host;
        String cleanUser = safe(username).trim();
        String cleanPass = safe(password);
        if (cleanHost.isEmpty()) throw new IllegalArgumentException("Thiếu IP/tên miền camera");
        if (cleanUser.isEmpty()) throw new IllegalArgumentException("Thiếu tài khoản camera");
        if (cleanPass.isEmpty()) throw new IllegalArgumentException("Thiếu mật khẩu/Safety Code");

        int port;
        try {
            port = Integer.parseInt(cleanAddress.port);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Port RTSP không hợp lệ");
        }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port RTSP ngoài phạm vi");

        String cleanPath = safe(path).trim();
        if (cleanPath.isEmpty()) cleanPath = "/";
        if (!cleanPath.startsWith("/")) cleanPath = "/" + cleanPath;

        String url = "rtsp://" + encodeUserInfo(cleanUser) + ":" + encodeUserInfo(cleanPass)
                + "@" + cleanHost + ":" + port + cleanPath;
        validate(url);
        return url;
    }

    public static void validate(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("rtsp") && !scheme.equals("rtsps")) {
                throw new IllegalArgumentException("URL phải bắt đầu bằng rtsp:// hoặc rtsps://");
            }
            if (uri.getHost() == null && uri.getRawAuthority() == null) {
                throw new IllegalArgumentException("URL RTSP thiếu địa chỉ camera");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("URL RTSP không hợp lệ");
        }
    }

    public static String redact(String url) {
        String value = safe(url);
        return value.replaceFirst("(?i)^(rtsps?://)([^/@]+)@", "$1***:***@");
    }

    public static boolean isImouMainStream(String url) {
        return safe(url).matches("(?is).*([?&])subtype=0(?:&.*)?$");
    }

    public static String withImouSubtype(String url, int subtype) {
        String value = safe(url);
        if (subtype < 0 || subtype > 1) return value;
        return value.replaceFirst("(?i)([?&]subtype=)\\d+", "$1" + subtype);
    }

    private static String encodeUserInfo(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException("Thiết bị không hỗ trợ UTF-8", impossible);
        }
    }

    private static HostPort normalizeHost(String host, String portText) {
        String value = safe(host).trim().replaceFirst("(?i)^rtsps?://", "");
        int userInfo = value.lastIndexOf('@');
        if (userInfo >= 0) value = value.substring(userInfo + 1);
        int path = value.indexOf('/');
        if (path >= 0) value = value.substring(0, path);
        value = value.replaceAll("/+$", "");
        String port = safe(portText).trim();
        int colon = value.lastIndexOf(':');
        if (colon > 0 && value.indexOf(':') == colon) {
            String embeddedPort = value.substring(colon + 1);
            if (embeddedPort.matches("\\d{1,5}")) {
                value = value.substring(0, colon);
                port = embeddedPort;
            }
        }
        return new HostPort(value, port);
    }

    private static final class HostPort {
        final String host;
        final String port;

        HostPort(String host, String port) {
            this.host = host;
            this.port = port;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
