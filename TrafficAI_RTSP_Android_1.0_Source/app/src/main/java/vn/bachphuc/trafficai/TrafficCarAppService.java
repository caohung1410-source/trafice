package vn.bachphuc.trafficai;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.car.app.model.Action;
import androidx.car.app.model.Pane;
import androidx.car.app.model.PaneTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.car.app.validation.HostValidator;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

/** Màn hình Android Auto thử nghiệm; không đưa video camera lên màn hình xe. */
public final class TrafficCarAppService extends CarAppService {
    @NonNull
    @Override
    public HostValidator createHostValidator() {
        // APK debug cá nhân phải cho phép Android Auto host trên xe kết nối khi đã bật
        // Developer mode/Unknown sources. Bản release vẫn dùng allowlist chữ ký chính thức.
        if (BuildConfig.DEBUG) return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
        return new HostValidator.Builder(this)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build();
    }

    @NonNull
    @Override
    public Session onCreateSession() {
        return new TrafficSession();
    }

    private static final class TrafficSession extends Session {
        @NonNull
        @Override
        public Screen onCreateScreen(@NonNull Intent intent) {
            return new TrafficScreen(getCarContext());
        }
    }

    private static final class TrafficScreen extends Screen implements CarTelemetryStore.Listener {
        private volatile CarTelemetryStore.State state = CarTelemetryStore.snapshot();

        TrafficScreen(CarContext carContext) {
            super(carContext);
            getLifecycle().addObserver(new DefaultLifecycleObserver() {
                @Override
                public void onStart(@NonNull LifecycleOwner owner) {
                    CarTelemetryStore.addListener(TrafficScreen.this);
                    state = CarTelemetryStore.snapshot();
                    invalidate();
                }

                @Override
                public void onStop(@NonNull LifecycleOwner owner) {
                    CarTelemetryStore.removeListener(TrafficScreen.this);
                }
            });
        }

        @Override
        public void onTelemetryChanged(CarTelemetryStore.State next) {
            state = next;
            invalidate();
        }

        @NonNull
        @Override
        public Template onGetTemplate() {
            CarTelemetryStore.State value = state;
            String limit = value.speedLimitKmh > 0
                    ? value.speedLimitKmh + " km/h • " + value.limitSource
                    : "Chưa có giới hạn • đặt trên điện thoại";
            String signal = value.light;
            if (value.countdown != null) signal += " • còn " + value.countdown + " giây";
            signal += value.targetLocked ? " • đã khóa mục tiêu" : " • đang quét";

            Pane.Builder pane = new Pane.Builder()
                    .addRow(new Row.Builder()
                            .setTitle("Tốc độ " + value.speedKmh + " km/h")
                            .addText("Giới hạn: " + limit + " • làn " + value.lane)
                            .build())
                    .addRow(new Row.Builder()
                            .setTitle("Tín hiệu: " + signal)
                            .addText("AI chạy trên điện thoại")
                            .build())
                    .addRow(new Row.Builder()
                            .setTitle("Nhận biết giao thông")
                            .addText("Biển: " + value.sign)
                            .addText("Phía trước: " + value.hazard)
                            .build())
                    .addRow(new Row.Builder()
                            .setTitle(value.navigationActive
                                    ? "Dẫn đường: " + shortName(value.destination)
                                    : "Map Memory")
                            .addText(value.navigationActive
                                    ? value.navigationInstruction + " • "
                                    + distance(value.navigationDistanceMeters)
                                    : value.landmark)
                            .addText(value.navigationActive ? value.landmark : "Biển/đèn đã học")
                            .build())
                    .addRow(new Row.Builder()
                            .setTitle(value.cameraConnected ? "Camera đã kết nối" : "Chưa kết nối camera")
                            .addText(value.aiReady ? "AI sẵn sàng" : "Mở AI trên điện thoại")
                            .build());

            return new PaneTemplate.Builder(pane.build())
                    .setTitle("TrafficAI Drive 2.3.1")
                    .setHeaderAction(Action.APP_ICON)
                    .build();
        }

        private String shortName(String value) {
            if (value == null || value.isEmpty()) return "điểm đến";
            int comma = value.indexOf(',');
            return comma > 0 ? value.substring(0, comma) : value;
        }

        private String distance(double meters) {
            return meters < 1_000d
                    ? Math.round(meters) + " m"
                    : String.format(java.util.Locale.US, "%.1f km", meters / 1_000d);
        }
    }
}
