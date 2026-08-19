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

import org.maplibre.android.MapLibre;
import org.maplibre.android.annotations.MarkerOptions;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.offline.OfflineManager;
import org.maplibre.android.offline.OfflineRegion;
import org.maplibre.android.offline.OfflineRegionError;
import org.maplibre.android.offline.OfflineRegionStatus;
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@OptIn(markerClass = UnstableApi.class)
public final class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final int LOCATION_PERMISSION_REQUEST = 1201;
    private static final long FRAME_INTERVAL_MS = 40L;
    private static final int CAPTURE_WIDTH = 1280;
    private static final int CAPTURE_HEIGHT = 720;
    private static final String MAP_STYLE_URL =
            "https://tiles.openfreemap.org/styles/liberty";
    private static final double OFFLINE_MAP_RADIUS_KM = 25d;
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
    private TextView hazardResult;
    private ProgressBar modelProgress;
    private Button initAiButton;
    private Button mapButton;
    private Button downloadMapButton;

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
    private long lastHazardSpeechAt;
    private String lastSpokenHazard = "";
    private long lastAiResultAt;
    private float smoothedAiFps;

    private LocationManager locationManager;
    private Location lastLocation;
    private double smoothedSpeedKmh;
    private int currentSpeedKmh;
    private int speedLimitKmh;
    private float lastTravelBearing;
    private OfflineGpsView mapView;
    private MapView baseMapView;
    private MapLibreMap baseMap;
    private boolean mapStyleReady;
    private boolean mapVisible;
    private long lastMapCameraAt;
    private OfflineManager offlineManager;
    private OfflineRegion downloadingRegion;
    private boolean offlineDownloadActive;
    private LandmarkMemoryStore landmarkStore;
    private LandmarkHint currentLandmarkHint = LandmarkHint.NONE;
    private long lastHintId = -1L;
    private long lastHintSpeechAt;
    private final Set<Long> mappedLandmarkIds = new HashSet<>();

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
        // MapLibre phải được khởi tạo trước khi Android inflate MapView từ XML.
        MapLibre.getInstance(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        bindViews();

        modelRepository = new ModelRepository(this);
        landmarkStore = new LandmarkMemoryStore(this);
        textToSpeech = new TextToSpeech(this, this);
        initBaseMap(savedInstanceState);
        initPlayer();
        bindActions();

        if (modelRepository.isReady()) {
            setStatus("Model đã có trong máy • bấm TẢI / MỞ AI để khởi tạo");
            modelProgress.setProgress(100);
        } else {
            setStatus("Giao diện sẵn sàng • AI đã đóng gói trong APK, lần đầu chỉ cần chép model");
        }
        CarTelemetryStore.updateConnection(false, false);
        updateMapButtonCount();
        checkOfflineMapStatus();
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
        hazardResult = findViewById(R.id.hazardResult);
        modelProgress = findViewById(R.id.modelProgress);
        initAiButton = findViewById(R.id.initAiButton);
        mapButton = findViewById(R.id.mapButton);
        downloadMapButton = findViewById(R.id.downloadMapButton);
        mapView = findViewById(R.id.mapView);
        baseMapView = findViewById(R.id.baseMapView);
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
        downloadMapButton.setOnClickListener(view -> downloadOfflineMap());
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

    private void initBaseMap(Bundle savedInstanceState) {
        offlineManager = OfflineManager.getInstance(this);
        offlineManager.setOfflineMapboxTileCountLimit(10_000L);
        baseMapView.onCreate(savedInstanceState);
        baseMapView.getMapAsync(map -> {
            if (destroyed) return;
            baseMap = map;
            map.setStyle(new Style.Builder().fromUri(MAP_STYLE_URL), style -> {
                if (destroyed) return;
                mapStyleReady = true;
                mapView.setBasemapVisible(true);
                mappedLandmarkIds.clear();
                refreshLandmarkMarkers();
                if (lastLocation != null) updateMapPosition(lastLocation);
                setStatus("Nền bản đồ đã sẵn sàng • có thể tải vùng offline 25 km");
            });
        });
    }

    private void checkOfflineMapStatus() {
        if (offlineManager == null) return;
        offlineManager.listOfflineRegions(new OfflineManager.ListOfflineRegionsCallback() {
            @Override
            public void onList(OfflineRegion[] regions) {
                if (destroyed || downloadMapButton == null) return;
                int count = regions == null ? 0 : regions.length;
                downloadMapButton.setText(count == 0
                        ? "TẢI MAP OFFLINE QUANH ĐÂY 25 KM"
                        : "TẢI THÊM VÙNG OFFLINE • ĐÃ CÓ " + count);
            }

            @Override
            public void onError(String error) {
                if (!destroyed) setStatus("Chưa đọc được kho bản đồ offline: " + error);
            }
        });
    }

    private void downloadOfflineMap() {
        if (offlineDownloadActive) {
            setStatus("Bản đồ offline đang được tải, vui lòng chờ");
            return;
        }
        if (lastLocation == null) {
            setStatus("Cần có vị trí GPS trước khi tải bản đồ quanh đây");
            Toast.makeText(this, "Hãy bật Vị trí và chờ GPS xác định tọa độ",
                    Toast.LENGTH_LONG).show();
            return;
        }
        double latitude = lastLocation.getLatitude();
        double longitude = lastLocation.getLongitude();
        double latDelta = GeoMath.latitudeDeltaForKm(OFFLINE_MAP_RADIUS_KM);
        double lonDelta = GeoMath.longitudeDeltaForKm(OFFLINE_MAP_RADIUS_KM, latitude);
        LatLngBounds bounds = new LatLngBounds.Builder()
                .include(new LatLng(latitude - latDelta, longitude - lonDelta))
                .include(new LatLng(latitude + latDelta, longitude + lonDelta))
                .build();
        float density = Math.min(2f, getResources().getDisplayMetrics().density);
        OfflineTilePyramidRegionDefinition definition =
                new OfflineTilePyramidRegionDefinition(
                        MAP_STYLE_URL, bounds, 8d, 15d, density, false);
        String metadata = "{\"name\":\"TrafficAI 2.1 • "
                + System.currentTimeMillis() + "\"}";
        offlineDownloadActive = true;
        downloadMapButton.setEnabled(false);
        downloadMapButton.setText("ĐANG TẠO VÙNG OFFLINE…");
        modelProgress.setProgress(0);
        setStatus("Đang chuẩn bị bản đồ bán kính 25 km • cần Internet trong lần tải này");

        offlineManager.createOfflineRegion(
                definition, metadata.getBytes(StandardCharsets.UTF_8),
                new OfflineManager.CreateOfflineRegionCallback() {
                    @Override
                    public void onCreate(OfflineRegion region) {
                        if (destroyed) return;
                        downloadingRegion = region;
                        region.setObserver(new OfflineRegion.OfflineRegionObserver() {
                            @Override
                            public void onStatusChanged(OfflineRegionStatus status) {
                                if (destroyed) return;
                                long completed = status.getCompletedResourceCount();
                                long required = status.getRequiredResourceCount();
                                int percent = status.isComplete() ? 100
                                        : required > 0L
                                        ? (int) Math.min(99L, completed * 100L / required) : 0;
                                modelProgress.setProgress(percent);
                                double megabytes = status.getCompletedResourceSize()
                                        / (1024d * 1024d);
                                setStatus("Đang tải map offline: " + percent + "% • "
                                        + completed + "/" + Math.max(required, completed)
                                        + " tài nguyên • "
                                        + String.format(Locale.US, "%.1f MB", megabytes));
                                if (status.isComplete()) finishOfflineMapDownload(region);
                            }

                            @Override
                            public void onError(OfflineRegionError error) {
                                if (!destroyed) failOfflineMapDownload(error.toString());
                            }

                            @Override
                            public void mapboxTileCountLimitExceeded(long limit) {
                                if (!destroyed) failOfflineMapDownload(
                                        "Vùng vượt giới hạn " + limit + " tile");
                            }
                        });
                        region.setDownloadState(OfflineRegion.STATE_ACTIVE);
                    }

                    @Override
                    public void onError(String error) {
                        if (!destroyed) failOfflineMapDownload(error);
                    }
                });
    }

    private void finishOfflineMapDownload(OfflineRegion region) {
        region.setDownloadState(OfflineRegion.STATE_INACTIVE);
        offlineDownloadActive = false;
        downloadingRegion = null;
        modelProgress.setProgress(100);
        downloadMapButton.setEnabled(true);
        setStatus("Map offline 25 km đã sẵn sàng • tắt Internet vẫn xem được vùng đã tải");
        if (ttsReady) speak("Đã tải xong bản đồ ngoại tuyến", TextToSpeech.QUEUE_ADD);
        checkOfflineMapStatus();
    }

    private void failOfflineMapDownload(String error) {
        if (downloadingRegion != null) {
            downloadingRegion.setDownloadState(OfflineRegion.STATE_INACTIVE);
        }
        offlineDownloadActive = false;
        downloadingRegion = null;
        downloadMapButton.setEnabled(true);
        downloadMapButton.setText("THỬ LẠI TẢI MAP OFFLINE");
        setStatus("Lỗi tải map offline: " + (error == null ? "không rõ" : error));
    }

    private void refreshLandmarkMarkers() {
        if (!mapStyleReady || baseMap == null || landmarkStore == null) return;
        List<LandmarkMemoryStore.Landmark> landmarks = landmarkStore.listRecent(120);
        for (LandmarkMemoryStore.Landmark landmark : landmarks) {
            if (!mappedLandmarkIds.add(landmark.id)) continue;
            String kind = LandmarkHint.TYPE_LIGHT.equals(landmark.type) ? "Đèn" : "Biển";
            baseMap.addMarker(new MarkerOptions()
                    .position(new LatLng(landmark.latitude, landmark.longitude))
                    .title(kind + ": " + landmark.label)
                    .snippet("Đã xác nhận " + landmark.confirmations + " lần"));
        }
    }

    private void updateMapButtonCount() {
        if (mapButton == null || landmarkStore == null || mapVisible) return;
        int count = landmarkStore.count();
        mapButton.setText(count > 0 ? "BẢN ĐỒ • " + count : "BẢN ĐỒ");
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
                    ready.setLandmarkHint(currentLandmarkHint);
                    boolean cameraReady = player != null
                            && player.getPlaybackState() == Player.STATE_READY;
                    CarTelemetryStore.updateConnection(cameraReady, true);
                    aiBadge.setText("AI: SẴN SÀNG");
                    initAiButton.setEnabled(true);
                    modelProgress.setProgress(100);
                    setStatus("TrafficAI 2.1: AI sẵn sàng • Map Memory đang học tuyến đường");
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
        recordLandmarkResult(result);
        overlayView.setResult(result, CAPTURE_WIDTH, CAPTURE_HEIGHT);
        long now = SystemClock.elapsedRealtime();
        if (lastAiResultAt > 0L) {
            float instantFps = 1_000f / Math.max(1f, now - lastAiResultAt);
            smoothedAiFps = smoothedAiFps == 0f
                    ? instantFps : smoothedAiFps * 0.72f + instantFps * 0.28f;
        }
        lastAiResultAt = now;
        aiBadge.setText("ADAS 2.1: " + result.inferenceMs + " ms • "
                + String.format(Locale.US, "%.1f fps", smoothedAiFps)
                + (result.targetLocked ? " • KHÓA" : " • QUÉT")
                + (currentLandmarkHint.isActive() ? " • NHỚ "
                + Math.round(currentLandmarkHint.distanceMeters) + "m" : ""));

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
        hazardResult.setText(result.hazardText.isEmpty()
                ? "PHÍA TRƯỚC\nĐANG QUAN SÁT"
                : "CẢNH BÁO\n" + result.hazardText + " "
                + Math.round(result.hazardConfidence * 100f) + "%");
        speakStableResult(result);
    }

    private void speakStableResult(AiResult result) {
        if (!ttsReady) return;
        long now = SystemClock.elapsedRealtime();
        String signal = result.lightState == TrafficState.UNKNOWN ? "" : result.lightState.vi;
        if (!result.hazardText.isEmpty()
                && result.hazardConfidence >= 0.78f
                && currentSpeedKmh >= 12
                && (!result.hazardText.equals(lastSpokenHazard)
                || now - lastHazardSpeechAt >= 8_000L)) {
            speak("Chú ý, " + result.hazardText.toLowerCase(new Locale("vi", "VN")),
                    TextToSpeech.QUEUE_FLUSH);
            lastSpokenHazard = result.hazardText;
            lastHazardSpeechAt = now;
            return;
        }
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
        baseMapView.setVisibility(mapVisible ? View.VISIBLE : View.GONE);
        mapView.setVisibility(mapVisible ? View.VISIBLE : View.GONE);
        playerView.setVisibility(mapVisible ? View.GONE : View.VISIBLE);
        overlayView.setVisibility(mapVisible ? View.GONE : View.VISIBLE);
        aiBadge.setVisibility(mapVisible ? View.GONE : View.VISIBLE);
        mapButton.setText(mapVisible ? "CAMERA" : "BẢN ĐỒ");
        if (mapVisible && lastLocation != null) {
            lastMapCameraAt = 0L;
            updateMapPosition(lastLocation);
        } else {
            updateMapButtonCount();
        }
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
        if (location.hasBearing()) lastTravelBearing = location.getBearing();
        updateMapPosition(location);
        updateLandmarkHint(location);
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
        float bearing = location.hasBearing() ? location.getBearing() : lastTravelBearing;
        mapView.updateLocation(
                location.getLatitude(),
                location.getLongitude(),
                bearing,
                currentSpeedKmh);
        if (!mapVisible || !mapStyleReady || baseMap == null) return;
        long now = SystemClock.elapsedRealtime();
        if (lastMapCameraAt != 0L && now - lastMapCameraAt < 1_000L) return;
        CameraPosition camera = new CameraPosition.Builder()
                .target(new LatLng(location.getLatitude(), location.getLongitude()))
                .zoom(15.8d)
                .bearing(bearing)
                .tilt(currentSpeedKmh >= 8 ? 38d : 18d)
                .build();
        if (lastMapCameraAt == 0L) {
            baseMap.moveCamera(CameraUpdateFactory.newCameraPosition(camera));
        } else {
            baseMap.animateCamera(CameraUpdateFactory.newCameraPosition(camera), 500);
        }
        lastMapCameraAt = now;
    }

    private void recordLandmarkResult(AiResult result) {
        Location location = lastLocation;
        if (result == null || location == null || landmarkStore == null) return;
        if (location.hasAccuracy() && location.getAccuracy() > 40f) return;
        float heading = location.hasBearing() ? location.getBearing() : lastTravelBearing;
        long now = System.currentTimeMillis();
        LandmarkMemoryStore.Landmark committed = null;

        if (result.targetLocked && result.lightConfidence >= .50f) {
            Detection light = bestDetection(result, Detection.Kind.TRAFFIC_LIGHT, null);
            if (light != null) {
                committed = landmarkStore.observe(
                        LandmarkHint.TYPE_LIGHT, "Đèn giao thông",
                        location.getLatitude(), location.getLongitude(), heading,
                        light.box.centerX() / CAPTURE_WIDTH,
                        light.box.centerY() / CAPTURE_HEIGHT,
                        result.lightConfidence, now);
            }
        }

        if (!result.signText.isEmpty() && result.signConfidence >= .58f) {
            Detection sign = bestDetection(result, Detection.Kind.TRAFFIC_SIGN, result.signText);
            if (sign != null) {
                LandmarkMemoryStore.Landmark signCommit = landmarkStore.observe(
                        LandmarkHint.TYPE_SIGN, result.signText,
                        location.getLatitude(), location.getLongitude(), heading,
                        sign.box.centerX() / CAPTURE_WIDTH,
                        sign.box.centerY() / CAPTURE_HEIGHT,
                        result.signConfidence, now);
                if (signCommit != null) committed = signCommit;
            }
        }

        if (committed != null) {
            updateMapButtonCount();
            refreshLandmarkMarkers();
            setStatus("Map Memory đã ghi/gộp: " + committed.label
                    + " • xác nhận " + committed.confirmations + " lần");
        }
    }

    private Detection bestDetection(
            AiResult result, Detection.Kind kind, String preferredLabel) {
        Detection best = null;
        for (Detection detection : result.detections) {
            if (detection.kind != kind) continue;
            if (preferredLabel != null
                    && !preferredLabel.equalsIgnoreCase(detection.label)) continue;
            if (best == null || detection.confidence > best.confidence) best = detection;
        }
        if (best != null || preferredLabel == null) return best;
        for (Detection detection : result.detections) {
            if (detection.kind == kind
                    && (best == null || detection.confidence > best.confidence)) best = detection;
        }
        return best;
    }

    private void updateLandmarkHint(Location location) {
        if (landmarkStore == null || location == null) return;
        float heading = location.hasBearing() ? location.getBearing() : lastTravelBearing;
        currentLandmarkHint = landmarkStore.findNearby(
                location.getLatitude(), location.getLongitude(), heading, 160d);
        AiCoordinator engine = aiCoordinator;
        if (engine != null) engine.setLandmarkHint(currentLandmarkHint);
        CarTelemetryStore.updateLandmark(currentLandmarkHint);

        if (!currentLandmarkHint.isActive()) {
            lastHintId = -1L;
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (currentLandmarkHint.distanceMeters >= 20d
                && currentLandmarkHint.distanceMeters <= 130d
                && currentSpeedKmh >= 8
                && (currentLandmarkHint.id != lastHintId
                || now - lastHintSpeechAt >= 60_000L)) {
            String message = currentLandmarkHint.expectsLight()
                    ? "Sắp đến vị trí đèn giao thông đã học"
                    : "Sắp đến biển " + currentLandmarkHint.label;
            if (ttsReady) speak(message, TextToSpeech.QUEUE_ADD);
            lastHintId = currentLandmarkHint.id;
            lastHintSpeechAt = now;
        }
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
        hazardResult.setText("PHÍA TRƯỚC\nĐANG QUAN SÁT");
        lastSpokenSignal = "";
        lastSpokenSign = "";
        lastSpokenCountdown = null;
        lastCountdownSpeechAt = 0;
        lastSpokenHazard = "";
        lastHazardSpeechAt = 0L;
        lastAiResultAt = 0L;
        smoothedAiFps = 0f;
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
    protected void onStart() {
        super.onStart();
        if (baseMapView != null) baseMapView.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (baseMapView != null) baseMapView.onResume();
        if (!destroyed) startFramePump();
    }

    @Override
    protected void onPause() {
        stopFramePump();
        if (baseMapView != null) baseMapView.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (baseMapView != null) baseMapView.onStop();
        super.onStop();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (baseMapView != null) baseMapView.onLowMemory();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (baseMapView != null) baseMapView.onSaveInstanceState(outState);
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
        if (downloadingRegion != null) {
            downloadingRegion.setDownloadState(OfflineRegion.STATE_INACTIVE);
            downloadingRegion = null;
        }
        if (landmarkStore != null) {
            landmarkStore.close();
            landmarkStore = null;
        }
        if (baseMapView != null) baseMapView.onDestroy();
        baseMap = null;
        mapView = null;
        baseMapView = null;
        worker.shutdown();
        super.onDestroy();
    }
}
