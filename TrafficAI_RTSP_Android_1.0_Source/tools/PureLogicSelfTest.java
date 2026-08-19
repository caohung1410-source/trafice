import vn.bachphuc.trafficai.CountdownTracker;
import vn.bachphuc.trafficai.GeoMath;
import vn.bachphuc.trafficai.RoadGeometryPrior;
import vn.bachphuc.trafficai.RtspUrlBuilder;
import vn.bachphuc.trafficai.TrafficState;

public final class PureLogicSelfTest {
    public static void main(String[] args) {
        String url = RtspUrlBuilder.build(
                "", "192.168.1.108", "554", "admin", "A b@1",
                "/cam/realmonitor?channel=1&subtype=1");
        require(url.contains("A%20b%401"), "Mật khẩu phải được URI-encode");
        require(!RtspUrlBuilder.redact(url).contains("A%20b%401"), "Log không được lộ mật khẩu");

        CountdownTracker tracker = new CountdownTracker();
        long t = 1_000;
        tracker.update(TrafficState.RED, .9f, 12, .9f, t);
        tracker.update(TrafficState.RED, .9f, 12, .9f, t + 120);
        tracker.update(TrafficState.RED, .9f, 12, .9f, t + 240);
        CountdownTracker.Result locked = tracker.update(TrafficState.RED, .9f, 12, .9f, t + 360);
        require(locked.state == TrafficState.RED, "Cần khóa đèn đỏ sau nhiều frame");
        require(locked.visibleNumber != null && locked.visibleNumber == 12, "Cần khóa số 12");

        tracker.update(TrafficState.RED, .9f, 13, .9f, t + 480);
        CountdownTracker.Result noIncrease = tracker.update(TrafficState.RED, .9f, 13, .9f, t + 600);
        require(noIncrease.visibleNumber == 12, "Không được chấp nhận chuỗi tăng bất thường");

        tracker.update(TrafficState.GREEN, .9f, 8, .9f, t + 1_700);
        tracker.update(TrafficState.GREEN, .9f, 8, .9f, t + 1_820);
        CountdownTracker.Result changed = tracker.update(TrafficState.GREEN, .9f, 8, .9f, t + 1_940);
        require(changed.state == TrafficState.GREEN, "Cần nhận đổi màu đèn");
        require(changed.visibleNumber != null && changed.visibleNumber == 8,
                "Đổi màu phải bỏ số cũ và nhận số mới có độ tin cậy cao");

        float rightSignal = RoadGeometryPrior.trafficLightEvidence(.82f, .40f);
        float lowRoadSignal = RoadGeometryPrior.trafficLightEvidence(.50f, .82f);
        require(rightSignal > lowRoadSignal,
                "Đèn trên cột bên phải phải được ưu tiên hơn vùng thấp giữa làn xe");
        require(RoadGeometryPrior.trafficLightEvidence(.50f, .18f) > .80f,
                "Đèn treo ngang phía trên giữa ảnh phải được giữ lại");
        require(RoadGeometryPrior.trafficSignEvidence(.82f, .48f)
                        > RoadGeometryPrior.trafficSignEvidence(.50f, .86f),
                "Biển bên phải ở cao độ hợp lý phải được ưu tiên");
        require(RoadGeometryPrior.trafficSignEvidence(.18f, .38f) > .30f,
                "Không được cắt cứng biển nhắc lại bên trái");
        require(RoadGeometryPrior.adjustConfidence(.20f, 1f) <= .20f,
                "Prior không được tự nâng phát hiện yếu");
        require(RoadGeometryPrior.travelDirectionEvidence(.52f, .20f)
                        > RoadGeometryPrior.travelDirectionEvidence(.04f, .70f),
                "Đèn cao theo hướng xe phải ưu tiên hơn đèn thấp ngoài luồng giao thông");

        require(GeoMath.distanceMeters(13.97609, 108.00695,
                13.97609, 108.00695) < .01d, "Cùng tọa độ phải có khoảng cách bằng 0");
        double nearby = GeoMath.distanceMeters(13.97609, 108.00695,
                13.97709, 108.00695);
        require(nearby > 105d && nearby < 116d,
                "Chênh 0,001 độ vĩ phải xấp xỉ 111 m");
        require(Math.abs(GeoMath.headingDifference(350d, 10d) - 20d) < .001d,
                "So hướng phải xử lý đúng qua mốc 0/360 độ");
        require(GeoMath.headingDifference(
                GeoMath.averageHeading(350d, 10d, .5d), 0d) < .001d,
                "Trung bình hướng 350/10 phải gần hướng Bắc");

        System.out.println("PureLogicSelfTest: PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
