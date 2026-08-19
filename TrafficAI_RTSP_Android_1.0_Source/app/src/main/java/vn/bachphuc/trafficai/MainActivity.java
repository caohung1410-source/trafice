package vn.bachphuc.trafficai;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.ui.PlayerView;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@OptIn(markerClass = UnstableApi.class)
public final class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final int LOCATION_PERMISSION_REQUEST = 1201;
    private static final long FRAME_INTERVAL_MS = 90L;
    private static final int CAPTURE_WIDTH = 1280;
    private static final int CAPTURE_HEIGHT = 720;
    private static final Pattern SPEED_LIMIT_PATTERN = Pattern.compile(
            "(?i)giới hạn tốc độ\\s+(\\d{1,3})");

    private PlayerView playerView;
    private DetectionOverlayView overlayView;
    private LinearLayout settingsPanel;
    private EditText fullUrlInput;
    private EditText hostInput;
    private EditText portInput;
    private EditText userInput;
    private EditText passwordInput;
    private EditText pathInput;
    private CheckBox tcpCheck;
    private TextView statusText;
    private TextView aiBadge;
    private TextView lightResult;
    private TextView countdownResult;
    private TextView signResult;
    private TextView speedResult;
    private TextView speedLimitResult;
    private ProgressBar modelProgress;
    private Button initAiButton;
    private Button mapButton;

    private ExoPlayer player;
    private Bitmap captureBitmap;
    private ModelRepository modelRepository;
    private volatile AiCoordinator aiCoordinator;
    private TextToSpeech textToSpeech;
    private boolean ttsReady;
    private volatile boolean destroyed;
    private boolean framePumpEnabled;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean frameBusy = new AtomicBoolean(false);

    private String lastSpokenSignal = "";
    private String lastSpokenSign = "";
    private Integer lastSpokenCountdown;
    private long lastSignalSpeechAt;
    private long lastSignSpeechAt;
    private long lastCountdownSpeechAt;
    private long overSpeedSince;
    private long lastOverSpeedSpeechAt;

    private LocationManager locationManager;
    private Location lastLocation;
    private double smoothedSpeedKmh;
    private int currentSpeedKmh;
    private int speedLimitKmh;
    private OfflineGpsView mapView;
    private boolean mapVisible;

    private final LocationListener locationListener = this::applyLocation;

    private final Runnable framePump = new Runnable() {
        @Override
        public void run() {
            if (!framePumpEnabled || destroyed) return;
            captureAndAnalyze();
            mainHandler.postDelayed(this, FRAME_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        bindViews();

        modelRepository = new ModelRepository(this);
        textToSpeech = new TextToSpeech(this, this);
        initPlayer();
        bindActions();

        if (modelRepository.isReady()) {
            setStatus("Model đã có trong máy • bấm TẢI / MỞ AI để khởi tạo");
            modelProgress.setProgress(100);
        } else {
            setStatus("Giao diện sẵn sàng • AI đã đóng gói trong APK, lần đầu chỉ cần chép model");
        }
        CarTelemetryStore.updateConnection(false, false);
        requestGpsPermission();
        startFramePump();
    }

    private void bindViews() {
        playerView = findViewById(R.id.playerView);
        overlayView = findViewById(R.id.overlayView);
        settingsPanel = findViewById(R.id.settingsPanel);
        fullUrlInput = findViewById(R.id.fullUrlInput);
        hostInput = findViewById(R.id.hostInput);
        portInput = findViewById(R.id.portInput);
        userInput = findViewById(R.id.userInput);
        passwordInput = findViewById(R.id.passwordInput);
        pathInput = findViewById(R.id.pathInput);
        tcpCheck = findViewById(R.id.tcpCheck);
        statusText = findViewById(R.id.statusText);
        aiBadge = findViewById(R.id.aiBadge);
        lightResult = findViewById(R.id.lightResult);
        countdownResult = findViewById(R.id.countdownResult);
        signResult = findViewById(R.id.signResult);
        speedResult = findViewById(R.id.speedResult);
        speedLimitResult = findViewById(R.id.speedLimitResult);
        modelProgress = findViewById(R.id.modelProgress);
        initAiButton = findViewById(R.id.initAiButton);
        mapButton = findViewById(R.id.mapButton);
        mapView = findViewById(R.id.mapView);
    }

    private void bindActions() {
        Button toggleSettings = findViewById(R.id.toggleSettingsButton);
        Button subStream = findViewById(R.id.subStreamButton);
        Button mainStream = findViewById(R.id.mainStreamButton);
        Button connect = findViewById(R.id.connectButton);
        Button disconnect = findViewById(R.id.disconnectButton);
        Button testSpeech = findViewById(R.id.testSpeechButton);
        Button limit40 = findViewById(R.id.limit40Button);
        Button limit50 = findViewById(R.id.limit50Button);
        Button limit60 = findViewById(R.id.limit60Button);
        Button limit80 = findViewById(R.id.limit80Button);

        toggleSettings.setOnClickListener(view -> {
            boolean hidden = settingsPanel.getVisibility() == View.GONE;
            settingsPanel.setVisibility(hidden ? View.VISIBLE : View.GONE);
            toggleSettings.setText(hidden ? "ẨN" : "CẤU HÌNH");
        });
        subStream.setOnClickListener(view -> setImouSubtype(1));
        mainStream.setOnClickListener(view -> setImouSubtype(0));
        connect.setOnClickListener(view -> connectRtsp());
        disconnect.setOnClickListener(view -> disconnectRtsp());
        initAiButton.setOnClickListener(view -> initializeAi());
        mapButton.setOnClickListener(view -> toggleMap());
        limit40.setOnClickListener(view -> setSpeedLimit(40, "Đặt thủ công"));
        limit50.setOnClickListener(view -> setSpeedLimit(50, "Đặt thủ công"));
        limit60.setOnClickListener(view -> setSpeedLimit(60, "Đặt thủ công"));
        limit80.setOnClickListener(view -> setSpeedLimit(80, "Đặt thủ công"));
        testSpeech.setOnClickListener(view -> {
            if (ttsReady) {
                speak("Kiểm tra giọng nói. Đèn đỏ, còn mười giây. Biển báo giới hạn tốc độ bốn mươi.",
                        TextToSpeech.QUEUE_FLUSH);
            } else {
                Toast.makeText(this,
                        "Chưa có giọng đọc. Hãy chọn Google Speech Services và tải giọng Tiếng Việt.",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void setImouSubtype(int subtype) {
        fullUrlInput.setText("");
        pathInput.setText("/cam/realmonitor?channel=1&subtype=" + subtype
                + "&unicast=true&proto=Onvif");
        Toast.makeText(this,
                subtype == 1 ? "Đã chọn luồng phụ nhẹ hơn" : "Đã chọn luồng chính nét hơn",
                Toast.LENGTH_SHORT).show();
    }

    private void initPlayer() {
        DefaultLoadControl lowLatencyLoadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(300, 1_000, 100, 250)
                .setBackBuffer(0, false)
                .build();
        player = new ExoPlayer.Builder(this)
                .setLoadControl(lowLatencyLoadControl)
                .build();
        // Camera chỉ cung cấp hình cho AI. Tắt chọn audio track và đặt volume 0 để mic
        // camera không phát ra loa, không che giọng TTS cảnh báo.
        player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build());
        player.setVolume(0f);
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) {
                    setStatus("Đang kết nối/buffer RTSP…");
                } else if (state == Player.STATE_READY) {
                    CarTelemetryStore.updateConnection(true, aiCoordinator != null);
                    setStatus("RTSP đã kết nối • mic camera đã tắt • video đang chạy"
                            + (aiCoordinator == null ? " • AI chưa mở" : " • AI đang phân tích"));
                } else if (state == Player.STATE_ENDED) {
                    CarTelemetryStore.updateConnection(false, aiCoordinator != null);
                    setStatus("Luồng RTSP đã kết thúc");
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                CarTelemetryStore.updateConnection(false, aiCoordinator != null);
                setStatus("Lỗi RTSP: " + error.getErrorCodeName());
                Toast.makeText(MainActivity.this,
                        "Không mở được camera. Kiểm tra IP, tài khoản, H.264 và cùng mạng Wi-Fi.",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void connectRtsp() {
        try {
            String url = RtspUrlBuilder.build(
                    text(fullUrlInput),
                    text(hostInput),
                    text(portInput),
                    text(userInput),
                    text(passwordInput),
                    text(pathInput));

            RtspMediaSource.Factory factory = new RtspMediaSource.Factory()
                    .setTimeoutMs(8_000)
                    .setForceUseRtpTcp(tcpCheck.isChecked());
            MediaItem item = MediaItem.fromUri(Uri.parse(url));
            MediaSource source = factory.createMediaSource(item);

            if (aiCoordinator != null) aiCoordinator.reset();
            resetUiResults();
            setStatus("Đang mở " + RtspUrlBuilder.redact(url)
                    + (tcpCheck.isChecked() ? " • RTP/TCP" : " • UDP→TCP fallback"));
            player.stop();
            player.clearMediaItems();
            player.setMediaSource(source);
            player.prepare();
            player.play();
        } catch (IllegalArgumentException error) {
            setStatus(error.getMessage());
        } catch (Throwable error) {
            setStatus("Không tạo được kết nối RTSP: " + error.getClass().getSimpleName());
        }
    }

    private void disconnectRtsp() {
        player.stop();
        player.clearMediaItems();
        CarTelemetryStore.updateConnection(false, aiCoordinator != null);
        if (aiCoordinator != null) aiCoordinator.reset();
        resetUiResults();
        setStatus("Đã ngắt camera");
    }

    private void initializeAi() {
        if (aiCoordinator != null) {
            setStatus("AI đã sẵn sàng");
            return;
        }
        initAiButton.setEnabled(false);
        aiBadge.setText("AI: ĐANG KHỞI TẠO");
        worker.execute(() -> {
            try {
                modelRepository.ensureModels((message, percent) -> runOnUiThread(() -> {
                    if (destroyed) return;
                    modelProgress.setProgress(percent);
                    setStatus(message);
                }));
                String[] signLabels = modelRepository.loadSignLabels();
                AiCoordinator ready = new AiCoordinator(
                        modelRepository.lightModel(), modelRepository.signModel(), signLabels);
                if (destroyed) {
                    ready.close();
                    return;
                }
                runOnUiThread(() -> {
                    if (destroyed) {
                        try {
                            ready.close();
                        } catch (Exception ignored) {
                        }
                        return;
                    }
                    aiCoordinator = ready;
                    boolean cameraReady = player != null
                            && player.getPlaybackState() == Player.STATE_READY;
                    CarTelemetryStore.updateConnection(cameraReady, true);
                    aiBadge.setText("AI: SẴN SÀNG");
                    initAiButton.setEnabled(true);
                    modelProgress.setProgress(100);
                    setStatus("AI đèn: OK • AI biển VN 82 lớp: OK • LED countdown: OK");
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    if (destroyed) return;
                    aiBadge.setText("AI: LỖI");
                    initAiButton.setEnabled(true);
                    setStatus("Lỗi khởi tạo AI: " + safeMessage(error));
                });
            }
        });
    }

    private void captureAndAnalyze() {
        if (aiCoordinator == null || player == null || player.getPlaybackState() != Player.STATE_READY) return;
        if (!frameBusy.compareAndSet(false, true)) return;
        View surface = playerView.getVideoSurfaceView();
        if (!(surface instanceof TextureView) || !((TextureView) surface).isAvailable()) {
            frameBusy.set(false);
            return;
        }
        if (captureBitmap == null || captureBitmap.isRecycled()) {
            captureBitmap = Bitmap.createBitmap(
                    CAPTURE_WIDTH, CAPTURE_HEIGHT, Bitmap.Config.ARGB_8888);
        }
        Bitmap frame = ((TextureView) surface).getBitmap(captureBitmap);
        if (frame == null) {
            frameBusy.set(false);
            return;
        }
        long timestamp = SystemClock.elapsedRealtime();
        worker.execute(() -> {
            try {
                AiCoordinator engine = aiCoordinator;
                if (engine == null) return;
                AiResult result = engine.analyze(frame, timestamp);
                runOnUiThread(() -> applyAiResult(result));
            } catch (Throwable error) {
                runOnUiThread(() -> aiBadge.setText("AI: LỖI FRAME"));
            } finally {
                frameBusy.set(false);
            }
        });
    }

    private void applyAiResult(AiResult result) {
        if (destroyed) return;
        CarTelemetryStore.updateAi(result);
        updateLimitFromSign(result);
        overlayView.setResult(result, CAPTURE_WIDTH, CAPTURE_HEIGHT);
        float effectiveFps = 1_000f / Math.max(1f, result.inferenceMs + FRAME_INTERVAL_MS);
        aiBadge.setText("AI: " + result.inferenceMs + " ms • "
                + String.format(Locale.US, "%.1f fps", effectiveFps));

        String light = result.lightState == TrafficState.UNKNOWN
                ? "ĐÈN\nCHƯA CHẮC"
                : "ĐÈN\n" + result.lightState.vi + " "
                + Math.round(result.lightConfidence * 100f) + "%";
        lightResult.setText(light);
        countdownResult.setText(result.countdown == null
                ? "GIÂY\n—" : "GIÂY\n" + result.countdown);
        signResult.setText(result.signText.isEmpty()
                ? "BIỂN BÁO\n—"
                : "BIỂN BÁO\n" + result.signText + " "
                + Math.round(result.signConfidence * 100f) + "%");
        speakStableResult(result);
    }

    private void speakStableResult(AiResult result) {
        if (!ttsReady) return;
        long now = SystemClock.elapsedRealtime();
        String signal = result.lightState == TrafficState.UNKNOWN ? "" : result.lightState.vi;
        if (!result.signText.isEmpty()
                && (!result.signText.equals(lastSpokenSign) || now - lastSignSpeechAt > 12_000)) {
            speak("Biển báo, " + result.signText, TextToSpeech.QUEUE_FLUSH);
            lastSpokenSign = result.signText;
            lastSignSpeechAt = now;
            return;
        }

        Integer number = result.countdown;
        if (number != null && !signal.isEmpty()
                && !number.equals(lastSpokenCountdown)
                && now - lastCountdownSpeechAt >= 650L) {
            String color = signal.toLowerCase(new Locale("vi", "VN"));
            String phrase = number <= 5
                    ? color + ", " + number
                    : "Đèn " + color + ", còn " + number + " giây";
            // Luôn bỏ câu cũ để giọng đọc không bị chậm hơn đồng hồ thật.
            speak(phrase, TextToSpeech.QUEUE_FLUSH);
            lastSpokenSignal = signal;
            lastSpokenCountdown = number;
            lastSignalSpeechAt = now;
            lastCountdownSpeechAt = now;
            return;
        }

        if (!signal.isEmpty() && !signal.equals(lastSpokenSignal)
                && now - lastSignalSpeechAt > 1_000L) {
            speak("Đèn " + signal.toLowerCase(new Locale("vi", "VN")),
                    TextToSpeech.QUEUE_FLUSH);
            lastSpokenSignal = signal;
            lastSpokenCountdown = null;
            lastSignalSpeechAt = now;
        }
    }

    private void speak(String text, int queueMode) {
        if (!ttsReady || textToSpeech == null) return;
        textToSpeech.speak(text, queueMode, null, "trafficai-" + SystemClock.elapsedRealtime());
    }

    private void toggleMap() {
        mapVisible = !mapVisible;
        mapView.setVisibility(mapVisible ? View.VISIBLE : View.GONE);
        playerView.setVisibility(mapVisible ? View.GONE : View.VISIBLE);
        overlayView.setVisibility(mapVisible ? View.GONE : View.VISIBLE);
        aiBadge.setVisibility(mapVisible ? View.GONE : View.VISIBLE);
        mapButton.setText(mapVisible ? "CAMERA" : "BẢN ĐỒ");
        if (mapVisible && lastLocation != null) updateMapPosition(lastLocation);
    }

    private void requestGpsPermission() {
        if (hasAnyLocationPermission()) {
            startGps();
            return;
        }
        requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        }, LOCATION_PERMISSION_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST && hasAnyLocationPermission()) {
            startGps();
        } else if (requestCode == LOCATION_PERMISSION_REQUEST) {
            speedResult.setText("GPS\nCHƯA CẤP QUYỀN");
            mapView.setStatus("CHƯA CẤP QUYỀN VỊ TRÍ");
        }
    }

    private boolean hasAnyLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startGps() {
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null || !hasAnyLocationPermission()) {
            speedResult.setText("GPS\nCHƯA CẤP QUYỀN");
            mapView.setStatus("CHƯA CẤP QUYỀN VỊ TRÍ");
            return;
        }
        try {
            boolean requested = false;
            Location initial = null;
            boolean precise = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            if (precise && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 700L, 0f, locationListener);
                initial = newest(initial,
                        locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER));
                requested = true;
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, 1_000L, 0f, locationListener);
                initial = newest(initial,
                        locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER));
                requested = true;
            }
            locationManager.requestLocationUpdates(
                    LocationManager.PASSIVE_PROVIDER, 1_000L, 0f, locationListener);
            initial = newest(initial,
                    locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER));

            if (initial != null) applyLocation(initial);
            if (initial == null) {
                speedResult.setText(requested ? "VỊ TRÍ\nĐANG TÌM" : "HÃY BẬT\nVỊ TRÍ");
                mapView.setStatus(requested
                        ? "ĐANG TÌM VỊ TRÍ • RA NGOÀI TRỜI"
                        : "HÃY BẬT VỊ TRÍ TRÊN ĐIỆN THOẠI");
            }
        } catch (Throwable error) {
            speedResult.setText("GPS\nKHÔNG SẴN SÀNG");
            mapView.setStatus("KHÔNG ĐỌC ĐƯỢC VỊ TRÍ");
        }
    }

    private Location newest(Location first, Location second) {
        if (first == null) return second;
        if (second == null) return first;
        return second.getTime() > first.getTime() ? second : first;
    }

    private void applyLocation(Location location) {
        if (destroyed || location == null) return;
        lastLocation = location;
        updateMapPosition(location);
        if (!location.hasSpeed()) {
            currentSpeedKmh = 0;
            updateMapPosition(location);
            speedResult.setText("TỐC ĐỘ GPS\n0 km/h");
            CarTelemetryStore.updateSpeed(0);
            return;
        }
        if (location.hasAccuracy() && location.getAccuracy() > 45f) return;
        if (location.hasSpeedAccuracy()
                && location.getSpeedAccuracyMetersPerSecond() > 5f) return;

        double rawKmh = Math.max(0d, location.getSpeed() * 3.6d);
        if (rawKmh < 2.0d) rawKmh = 0d;
        smoothedSpeedKmh = smoothedSpeedKmh == 0d
                ? rawKmh : smoothedSpeedKmh * 0.62d + rawKmh * 0.38d;
        currentSpeedKmh = Math.max(0, (int) Math.round(smoothedSpeedKmh));
        speedResult.setText("TỐC ĐỘ GPS\n" + currentSpeedKmh + " km/h");
        CarTelemetryStore.updateSpeed(currentSpeedKmh);
        evaluateOverSpeed();
    }

    private void updateMapPosition(Location location) {
        if (mapView == null || location == null) return;
        mapView.updateLocation(
                location.getLatitude(),
                location.getLongitude(),
                location.hasBearing() ? location.getBearing() : 0f,
                currentSpeedKmh);
    }

    private void updateLimitFromSign(AiResult result) {
        if (result == null || result.signConfidence < 0.50f || result.signText == null) return;
        String text = result.signText.trim();
        if (text.toLowerCase(new Locale("vi", "VN")).startsWith("hết giới hạn tốc độ")) {
            setSpeedLimit(0, "Biển hết hạn chế");
            return;
        }
        Matcher matcher = SPEED_LIMIT_PATTERN.matcher(text);
        if (!matcher.find()) return;
        int detected;
        try {
            detected = Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException error) {
            return;
        }
        if (detected >= 10 && detected <= 130 && detected != speedLimitKmh) {
            setSpeedLimit(detected, "Biển báo AI");
            speak("Đã nhận giới hạn tốc độ " + detected, TextToSpeech.QUEUE_ADD);
        }
    }

    private void setSpeedLimit(int value, String source) {
        speedLimitKmh = Math.max(0, value);
        speedLimitResult.setText(speedLimitKmh > 0
                ? "GIỚI HẠN\n" + speedLimitKmh + " km/h"
                : "GIỚI HẠN\n—");
        CarTelemetryStore.updateLimit(speedLimitKmh, source);
        overSpeedSince = 0L;
    }

    private void evaluateOverSpeed() {
        long now = SystemClock.elapsedRealtime();
        if (speedLimitKmh <= 0 || currentSpeedKmh <= speedLimitKmh + 5) {
            overSpeedSince = 0L;
            return;
        }
        if (overSpeedSince == 0L) overSpeedSince = now;
        if (now - overSpeedSince >= 1_800L && now - lastOverSpeedSpeechAt >= 12_000L) {
            speak("Bạn đang chạy " + currentSpeedKmh + ", giới hạn "
                    + speedLimitKmh + ", hãy giảm tốc độ", TextToSpeech.QUEUE_FLUSH);
            lastOverSpeedSpeechAt = now;
        }
    }

    private void resetUiResults() {
        overlayView.clear();
        lightResult.setText("ĐÈN\n—");
        countdownResult.setText("GIÂY\n—");
        signResult.setText("BIỂN BÁO\n—");
        lastSpokenSignal = "";
        lastSpokenSign = "";
        lastSpokenCountdown = null;
        lastCountdownSpeechAt = 0;
    }

    private void startFramePump() {
        framePumpEnabled = true;
        mainHandler.removeCallbacks(framePump);
        mainHandler.post(framePump);
    }

    private void stopFramePump() {
        framePumpEnabled = false;
        mainHandler.removeCallbacks(framePump);
    }

    private String text(EditText view) {
        return view.getText() == null ? "" : view.getText().toString().trim();
    }

    private void setStatus(String message) {
        statusText.setText(message == null ? "" : message);
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return error.getClass().getSimpleName();
        return message.replaceAll("(?i)(rtsps?://)([^/@]+)@", "$1***:***@");
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS && textToSpeech != null) {
            textToSpeech.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            int language = textToSpeech.setLanguage(Locale.forLanguageTag("vi-VN"));
            if (language == TextToSpeech.LANG_MISSING_DATA
                    || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                language = textToSpeech.setLanguage(new Locale("vi"));
            }
            if (language == TextToSpeech.LANG_MISSING_DATA
                    || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                language = textToSpeech.setLanguage(Locale.getDefault());
            }
            textToSpeech.setSpeechRate(1.02f);
            textToSpeech.setPitch(1.0f);
            ttsReady = language != TextToSpeech.LANG_MISSING_DATA
                    && language != TextToSpeech.LANG_NOT_SUPPORTED;
            if (ttsReady) {
                mainHandler.postDelayed(() -> {
                    if (!destroyed) speak("Đã bật cảnh báo giao thông", TextToSpeech.QUEUE_FLUSH);
                }, 450L);
            } else {
                Toast.makeText(this,
                        "Máy chưa có dữ liệu giọng nói. Bấm THỬ GIỌNG sau khi cài giọng Tiếng Việt.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!destroyed) startFramePump();
    }

    @Override
    protected void onPause() {
        stopFramePump();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        stopFramePump();
        frameBusy.set(false);
        if (locationManager != null) {
            locationManager.removeUpdates(locationListener);
            locationManager = null;
        }
        if (playerView != null) playerView.setPlayer(null);
        if (player != null) {
            player.release();
            player = null;
        }
        AiCoordinator engine = aiCoordinator;
        aiCoordinator = null;
        if (engine != null) {
            worker.execute(() -> {
                try {
                    engine.close();
                } catch (Exception ignored) {
                }
            });
        }
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
        }
        mapView = null;
        worker.shutdown();
        super.onDestroy();
    }
}
