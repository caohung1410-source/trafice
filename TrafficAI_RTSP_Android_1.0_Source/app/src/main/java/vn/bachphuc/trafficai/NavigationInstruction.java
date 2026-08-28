package vn.bachphuc.trafficai;

import java.util.Locale;

/** Chuyển maneuver OSRM sang câu hướng dẫn tiếng Việt ngắn, dễ đọc bằng TTS. */
public final class NavigationInstruction {
    private NavigationInstruction() {
    }

    public static String fromOsrm(
            String type, String modifier, String roadName, int exitNumber) {
        String maneuver = safe(type).toLowerCase(Locale.US);
        String direction = direction(safe(modifier).toLowerCase(Locale.US));
        String road = safe(roadName).trim();
        String suffix = road.isEmpty() ? "" : " vào " + road;
        switch (maneuver) {
            case "depart":
                return road.isEmpty() ? "Bắt đầu di chuyển" : "Bắt đầu đi trên " + road;
            case "arrive":
                return "Đã đến " + (road.isEmpty() ? "điểm đến" : road);
            case "roundabout":
            case "rotary":
                return exitNumber > 0
                        ? "Vào vòng xuyến, ra ở lối thứ " + exitNumber + suffix
                        : "Đi vào vòng xuyến" + suffix;
            case "merge":
                return "Nhập làn " + direction + suffix;
            case "fork":
                return "Đi theo nhánh " + direction + suffix;
            case "on ramp":
                return "Đi vào đường dẫn " + direction + suffix;
            case "off ramp":
                return "Ra khỏi đường chính " + direction + suffix;
            case "end of road":
                return "Cuối đường, rẽ " + direction + suffix;
            case "new name":
            case "continue":
                return "Tiếp tục " + direction + suffix;
            case "turn":
                return "Rẽ " + direction + suffix;
            default:
                return direction.equals("thẳng")
                        ? "Tiếp tục đi thẳng" + suffix : "Đi " + direction + suffix;
        }
    }

    public static String withDistance(String instruction, double distanceMeters) {
        if (distanceMeters < 45d) return "Chuẩn bị, " + instruction;
        if (distanceMeters < 1_000d) {
            int rounded = Math.max(10, (int) (Math.round(distanceMeters / 10d) * 10));
            return "Sau " + rounded + " mét, " + lowerFirst(instruction);
        }
        double km = Math.round(distanceMeters / 100d) / 10d;
        return "Sau " + km + " ki lô mét, " + lowerFirst(instruction);
    }

    private static String direction(String modifier) {
        switch (modifier) {
            case "left": return "trái";
            case "right": return "phải";
            case "slight left": return "chếch trái";
            case "slight right": return "chếch phải";
            case "sharp left": return "gấp sang trái";
            case "sharp right": return "gấp sang phải";
            case "uturn": return "quay đầu";
            default: return "thẳng";
        }
    }

    private static String lowerFirst(String value) {
        if (value == null || value.isEmpty()) return "tiếp tục";
        return value.substring(0, 1).toLowerCase(new Locale("vi", "VN")) + value.substring(1);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
