import vn.bachphuc.trafficai.CountdownTracker;
import vn.bachphuc.trafficai.GeoMath;
import vn.bachphuc.trafficai.NavigationInstruction;
import vn.bachphuc.trafficai.NavigationSession;
import vn.bachphuc.trafficai.RoadGeometryPrior;
import vn.bachphuc.trafficai.RoutePlan;
import vn.bachphuc.trafficai.RtspUrlBuilder;
import vn.bachphuc.trafficai.SignDecisionPolicy;
import vn.bachphuc.trafficai.SignTrackMath;
import vn.bachphuc.trafficai.TrafficState;

import java.util.Arrays;

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
        require(RoadGeometryPrior.adjustConfidence(.20f, .22f) >= .184f,
                "Prior không được làm mất biển thật ở giữa hoặc bên trái");
        require(RoadGeometryPrior.travelDirectionEvidence(.52f, .20f)
                        > RoadGeometryPrior.travelDirectionEvidence(.04f, .70f),
                "Đèn cao theo hướng xe phải ưu tiên hơn đèn thấp ngoài luồng giao thông");

        require(!SignDecisionPolicy.evaluate(2, 2, .34f, .55f).confirmed,
                "Hai phát hiện yếu chưa được đọc thành biển thật");
        require(SignDecisionPolicy.evaluate(2, 2, .46f, .70f).confirmed,
                "Biển rõ phải xác nhận được sau hai khung");
        require(!SignDecisionPolicy.evaluate(2, 4, .80f, .90f).confirmed,
                "Hai lớp hòa phiếu không được xác nhận chỉ vì độ tin cậy cao");
        require(SignDecisionPolicy.evaluate(3, 4, .24f, .31f).confirmed,
                "Ba phiếu cùng lớp phải giữ được biển xa");
        require(!SignDecisionPolicy.evaluate(2, 5, .80f, .90f).confirmed,
                "Không xác nhận lớp chỉ chiếm thiểu số trên một track");
        require(SignTrackMath.affinity(
                100f, 100f, 120f, 120f,
                108f, 102f, 128f, 122f) > .20f,
                "Cùng biển dịch chuyển nhẹ phải được nối track");
        require(SignTrackMath.affinity(
                100f, 100f, 120f, 120f,
                500f, 100f, 520f, 120f) < .20f,
                "Hai biển cùng cỡ nhưng ở xa không được nhập chung track");

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
        require(GeoMath.distanceToSegmentMeters(
                13.97659, 108.00696,
                13.97609, 108.00695,
                13.97709, 108.00695) < 3d,
                "Điểm nằm sát đoạn tuyến phải có khoảng cách rất nhỏ");

        String turnRight = NavigationInstruction.fromOsrm(
                "turn", "right", "Lê Lợi", 0);
        require(turnRight.contains("Rẽ phải") && turnRight.contains("Lê Lợi"),
                "Maneuver OSRM phải đổi thành câu rẽ tiếng Việt");
        require(NavigationInstruction.withDistance(turnRight, 120d).contains("120 mét"),
                "Câu TTS phải kèm khoảng cách tới chỗ rẽ");

        RoutePlan route = new RoutePlan(
                "Quảng trường",
                13.97809, 108.00695,
                222d, 60d,
                Arrays.asList(
                        new RoutePlan.Point(13.97609, 108.00695),
                        new RoutePlan.Point(13.97709, 108.00695),
                        new RoutePlan.Point(13.97809, 108.00695)),
                Arrays.asList(
                        new RoutePlan.Step("Bắt đầu", 13.97609, 108.00695, 111d),
                        new RoutePlan.Step("Rẽ phải", 13.97709, 108.00695, 111d),
                        new RoutePlan.Step("Đã đến", 13.97809, 108.00695, 0d)));
        NavigationSession navigation = new NavigationSession();
        navigation.setPlan(route);
        NavigationSession.Guidance firstGuide = navigation.update(13.97609, 108.00695);
        require(firstGuide.active && firstGuide.instruction.contains("Rẽ phải"),
                "Điều hướng phải bỏ bước depart và chỉ bước rẽ kế tiếp");
        NavigationSession.Guidance advanced = navigation.update(13.97708, 108.00695);
        require(advanced.instruction.contains("Đã đến"),
                "Qua vị trí rẽ phải chuyển sang bước tiếp theo");
        NavigationSession.Guidance arrived = navigation.update(13.97809, 108.00695);
        require(arrived.arrived, "Đến trong 32 m phải kết thúc tuyến");

        System.out.println("PureLogicSelfTest: PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
