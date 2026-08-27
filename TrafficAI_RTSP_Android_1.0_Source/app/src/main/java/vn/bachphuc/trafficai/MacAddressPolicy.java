package vn.bachphuc.trafficai;

import java.util.Locale;

/** Chuẩn hóa và kiểm tra MAC để không quét LAN với một định danh mơ hồ. */
public final class MacAddressPolicy {
    private MacAddressPolicy() {
    }

    public static String normalize(String value) {
        if (value == null) return "";
        String hex = value.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.US);
        if (hex.length() != 12) return "";
        StringBuilder result = new StringBuilder(17);
        for (int index = 0; index < 12; index += 2) {
            if (result.length() > 0) result.append(':');
            result.append(hex, index, index + 2);
        }
        return result.toString();
    }

    public static boolean isValidDeviceMac(String value) {
        String normalized = normalize(value);
        if (normalized.isEmpty()
                || "00:00:00:00:00:00".equals(normalized)
                || "FF:FF:FF:FF:FF:FF".equals(normalized)) return false;
        int first = Integer.parseInt(normalized.substring(0, 2), 16);
        return (first & 1) == 0;
    }

    public static boolean matches(String expected, String observed) {
        String first = normalize(expected);
        String second = normalize(observed);
        return !first.isEmpty() && first.equals(second);
    }
}
