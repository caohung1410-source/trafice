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

        String cleanHost = safe(host).trim()
                .replaceFirst("(?i)^rtsps?://", "")
                .replaceAll("/+$", "");
        String cleanUser = safe(username).trim();
        String cleanPass = safe(password);
        if (cleanHost.isEmpty()) throw new IllegalArgumentException("Thiếu IP/tên miền camera");
        if (cleanUser.isEmpty()) throw new IllegalArgumentException("Thiếu tài khoản camera");
        if (cleanPass.isEmpty()) throw new IllegalArgumentException("Thiếu mật khẩu/Safety Code");

        int port;
        try {
            port = Integer.parseInt(safe(portText).trim());
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

    private static String encodeUserInfo(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException("Thiết bị không hỗ trợ UTF-8", impossible);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
