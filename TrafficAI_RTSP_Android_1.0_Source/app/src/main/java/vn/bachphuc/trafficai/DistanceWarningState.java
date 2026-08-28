package vn.bachphuc.trafficai;

/** Mức cảnh báo khoảng cách; không điều khiển phanh hay ga của xe. */
public enum DistanceWarningState {
    SEARCHING("ĐANG TÌM XE"),
    TRACKING("ĐANG ĐỐI CHIẾU"),
    SAFE("KHOẢNG CÁCH TỐT"),
    CAUTION("NÊN TĂNG KHOẢNG CÁCH"),
    DANGER("NGUY HIỂM QUÁ GẦN");

    public final String vi;

    DistanceWarningState(String vi) {
        this.vi = vi;
    }
}
