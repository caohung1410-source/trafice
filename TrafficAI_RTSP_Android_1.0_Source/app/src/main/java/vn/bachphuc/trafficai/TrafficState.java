package vn.bachphuc.trafficai;

public enum TrafficState {
    RED("ĐỎ"),
    YELLOW("VÀNG"),
    GREEN("XANH"),
    UNKNOWN("CHƯA CHẮC");

    public final String vi;

    TrafficState(String vi) {
        this.vi = vi;
    }
}
