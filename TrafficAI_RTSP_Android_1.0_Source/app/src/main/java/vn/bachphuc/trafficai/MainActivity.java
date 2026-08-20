package vn.bachphuc.trafficai;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
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
import org.maplibre.android.annotations.Polyline;
import org.maplibre.android.annotations.PolylineOptions;
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
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@OptIn(markerClass = UnstableApi.class)
public final class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final int LOCATION_PERMISSION_REQUEST = 1201;
    private static final int VOICE_SEARCH_REQUEST = 1202;
    private static final int PHONE_CAMERA_PERMISSION_REQUEST = 1203;
    private static final long FRAME_INTERVAL_MS = 40L;
    private static final int CAPTURE_WIDTH = 1280;
    private static final int CAPTURE_HEIGHT = 720;
    private static final String MAP_STYLE_URL =
            "https://tiles.openfreemap.org/styles/liberty";
    private static final double OFFLINE_MAP_RADIUS_KM = 25d;
    private static final long SEARCH_CACHE_MS = 30L * 24L * 60L * 60L * 1_000L;
    private static final String PROVIDER_PREFS = "map_provider_settings";
    private PlayerView playerView;
    private TextureView phoneCameraView;
    private DetectionOverlayView overlayView;
    private View settingsPanel;
    private EditText fullUrlInput;
    private EditText hostInput;
    private EditText portInput;
    private EditText userInput;
    private EditText passwordInput;
    private EditText pathInput;
    private CheckBox tcpCheck;
    private TextView statusText;
    private TextView aiBadge;
    private TextView cameraSourceBadge;
    private TextView lightResult;
    private TextView countdownResult;
    private TextView signResult;
    private TextView speedResult;
    private TextView speedLimitResult;
    private TextView hazardResult;
    private TextView roadAlertBanner;
    private TextView tripSummaryText;
    private TextView driveModeText;
    private ProgressBar modelProgress;
    private Button initAiButton;
    private Button mapButton;
    private Button phoneCameraButton;
    private Button rotatePhoneCameraButton;
    private Button downloadMapButton;
    private LinearLayout navigationPanel;
    private EditText destinationSearchInput;
    private EditText geocoderUrlInput;
    private EditText routingUrlInput;
    private EditText overpassUrlInput;
    private TextView navigationInfo;
    private View routeButton;
    private View voiceSearchButton;
    private Button refreshTrafficMapButton;
    private Button clearRouteButton;
    private Button laneLeftButton;
    private Button laneCenterButton;
    private Button laneRightButton;
    private LanePreference lanePreference = LanePreference.CENTER;

    private ExoPlayer player;
    private volatile CameraDevice phoneCameraDevice;
    private volatile CameraCaptureSession phoneCameraSession;
    private Surface phonePreviewSurface;
    private HandlerThread phoneCameraThread;
    private Handler phoneCameraHandler;
    private Size phonePreviewSize;
    private int manualCameraRotationDegrees;
    private boolean phoneCameraMode;
    private boolean phoneCameraPaused;
    private boolean phoneCameraOpening;
    private final Object phoneCameraLock = new Object();
    private Bitmap captureBitmap;
    private ModelRepository modelRepository;
    private volatile AiCoordinator aiCoordinator;
    private boolean aiInitializing;
    private TextToSpeech textToSpeech;
    private boolean ttsReady;
    private volatile boolean destroyed;
    private boolean framePumpEnabled;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService networkWorker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean frameBusy = new AtomicBoolean(false);

    private String lastSpokenSignal = "";
    private String lastSpokenSign = "";
    private long lastSpokenSignTrackId = -1L;
    private Integer lastSpokenCountdown;
    private long lastSignalSpeechAt;
    private long lastSignSpeechAt;
    private long lastAppliedSpeedSignTrackId = -1L;
    private String lastAppliedSpeedSignLabel = "";
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
    private final Set<String> mappedOsmFeatureIds = new HashSet<>();
    private MapFeatureStore mapFeatureStore;
    private NavigationDataService navigationDataService;
    private final NavigationSession navigationSession = new NavigationSession();
    private RoutePlan currentRoutePlan;
    private NavigationDataService.Place currentDestination;
    private Polyline routePolyline;
    private boolean trafficDataBusy;
    private boolean routeBusy;
    private double lastTrafficFetchLat = Double.NaN;
    private double lastTrafficFetchLon = Double.NaN;
    private long lastTrafficFetchAt;
    private long offRouteSince;
    private long lastReplanAt;

    private final LocationListener locationListener = this::applyLocation;

    private final TextureView.SurfaceTextureListener phoneCameraTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(
                        SurfaceTexture surface, int width, int height) {
                    configurePhoneCameraTransform(width, height);
                    if (phoneCameraMode && !destroyed) openPhoneCamera();
                }

                @Override
                public void onSurfaceTextureSizeChanged(
                        SurfaceTexture surface, int width, int height) {
                    configurePhoneCameraTransform(width, height);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    closePhoneCamera();
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                }
            };

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
        mapFeatureStore = new MapFeatureStore(this);
        navigationDataService = new NavigationDataService();
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
        restoreProviderSettings();
        restoreLanePreference();
        updateMapButtonCount();
        checkOfflineMapStatus();
        requestGpsPermission();
        startFramePump();
    }

    private void bindViews() {
        playerView = findViewById(R.id.playerView);
        phoneCameraView = findViewById(R.id.phoneCameraView);
        phoneCameraView.setSurfaceTextureListener(phoneCameraTextureListener);
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
        cameraSourceBadge = findViewById(R.id.cameraSourceBadge);
        lightResult = findViewById(R.id.lightResult);
        countdownResult = findViewById(R.id.countdownResult);
        signResult = findViewById(R.id.signResult);
        speedResult = findViewById(R.id.speedResult);
        speedLimitResult = findViewById(R.id.speedLimitResult);
        hazardResult = findViewById(R.id.hazardResult);
        roadAlertBanner = findViewById(R.id.roadAlertBanner);
        tripSummaryText = findViewById(R.id.tripSummaryText);
        driveModeText = findViewById(R.id.driveModeText);
        modelProgress = findViewById(R.id.modelProgress);
        initAiButton = findViewById(R.id.initAiButton);
        mapButton = findViewById(R.id.mapButton);
        phoneCameraButton = findViewById(R.id.phoneCameraButton);
        rotatePhoneCameraButton = findViewById(R.id.rotatePhoneCameraButton);
        downloadMapButton = findViewById(R.id.downloadMapButton);
        navigationPanel = findViewById(R.id.navigationPanel);
        destinationSearchInput = findViewById(R.id.destinationSearchInput);
        geocoderUrlInput = findViewById(R.id.geocoderUrlInput);
        routingUrlInput = findViewById(R.id.routingUrlInput);
        overpassUrlInput = findViewById(R.id.overpassUrlInput);
        navigationInfo = findViewById(R.id.navigationInfo);
        routeButton = findViewById(R.id.routeButton);
        voiceSearchButton = findViewById(R.id.voiceSearchButton);
        refreshTrafficMapButton = findViewById(R.id.refreshTrafficMapButton);
        clearRouteButton = findViewById(R.id.clearRouteButton);
        laneLeftButton = findViewById(R.id.laneLeftButton);
        laneCenterButton = findViewById(R.id.laneCenterButton);
        laneRightButton = findViewById(R.id.laneRightButton);
        mapView = findViewById(R.id.mapView);
        baseMapView = findViewById(R.id.baseMapView);
    }

    private void bindActions() {
        View toggleSettings = findViewById(R.id.toggleSettingsButton);
        View closeSettings = findViewById(R.id.closeSettingsButton);
        Button subStream = findViewById(R.id.subStreamButton);
        Button mainStream = findViewById(R.id.mainStreamButton);
        Button connect = findViewById(R.id.connectButton);
        Button disconnect = findViewById(R.id.disconnectButton);
        Button testSpeech = findViewById(R.id.testSpeechButton);
        Button limit40 = findViewById(R.id.limit40Button);
        Button limit50 = findViewById(R.id.limit50Button);
        Button limit60 = findViewById(R.id.limit60Button);
        Button limit80 = findViewById(R.id.limit80Button);

        toggleSettings.setOnClickListener(view -> setSettingsVisible(true));
        closeSettings.setOnClickListener(view -> setSettingsVisible(false));
        subStream.setOnClickListener(view -> setImouSubtype(1));
        mainStream.setOnClickListener(view -> setImouSubtype(0));
        connect.setOnClickListener(view -> connectRtsp());
        disconnect.setOnClickListener(view -> disconnectRtsp());
        phoneCameraButton.setOnClickListener(view -> switchToPhoneCamera());
        rotatePhoneCameraButton.setOnClickListener(view -> rotatePhoneCameraPreview());
        initAiButton.setOnClickListener(view -> initializeAi());
        mapButton.setOnClickListener(view -> toggleMap());
        downloadMapButton.setOnClickListener(view -> downloadOfflineMap());
        voiceSearchButton.setOnClickListener(view -> startVoiceDestinationSearch());
        routeButton.setOnClickListener(view -> searchAndRoute());
        refreshTrafficMapButton.setOnClickListener(view -> refreshTrafficMapData(true));
        clearRouteButton.setOnClickListener(view -> clearNavigation());
        laneLeftButton.setOnClickListener(view -> selectLane(LanePreference.LEFT, true));
        laneCenterButton.setOnClickListener(view -> selectLane(LanePreference.CENTER, true));
        laneRightButton.setOnClickListener(view -> selectLane(LanePreference.RIGHT, true));
        destinationSearchInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_GO) return false;
            searchAndRoute();
            return true;
        });
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

    private void restoreProviderSettings() {
        SharedPreferences preferences = getSharedPreferences(PROVIDER_PREFS, MODE_PRIVATE);
        manualCameraRotationDegrees = CameraRotationPolicy.normalizeManualDegrees(
                preferences.getInt("phone_camera_rotation", 0));
        updatePhoneCameraRotationButton();
        geocoderUrlInput.setText(preferences.getString(
                "geocoder", "https://nominatim.openstreetmap.org"));
        routingUrlInput.setText(preferences.getString(
                "routing", "https://router.project-osrm.org"));
        overpassUrlInput.setText(preferences.getString(
                "overpass", "https://overpass-api.de/api/interpreter"));
    }

    private void restoreLanePreference() {
        SharedPreferences preferences = getSharedPreferences(PROVIDER_PREFS, MODE_PRIVATE);
        selectLane(LanePreference.fromStored(preferences.getString("lane", "CENTER")), false);
    }

    private void selectLane(LanePreference preference, boolean announce) {
        lanePreference = preference == null ? LanePreference.CENTER : preference;
        laneLeftButton.setSelected(lanePreference == LanePreference.LEFT);
        laneCenterButton.setSelected(lanePreference == LanePreference.CENTER);
        laneRightButton.setSelected(lanePreference == LanePreference.RIGHT);
        AiCoordinator engine = aiCoordinator;
        if (engine != null) engine.setLanePreference(lanePreference);
        getSharedPreferences(PROVIDER_PREFS, MODE_PRIVATE).edit()
                .putString("lane", lanePreference.name())
                .apply();
        updateDriveMode();
        CarTelemetryStore.updateLane(lanePreference.vi);
        if (announce) {
            String message = "Ưu tiên quan sát làn "
                    + lanePreference.vi.toLowerCase(new Locale("vi", "VN"));
            setStatus(message + " • AI vẫn quét toàn cảnh định kỳ");
            if (ttsReady) speak(message, TextToSpeech.QUEUE_ADD);
        }
    }

    private void setSettingsVisible(boolean visible) {
        settingsPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) settingsPanel.bringToFront();
    }

    private void saveProviderSettings() {
        getSharedPreferences(PROVIDER_PREFS, MODE_PRIVATE).edit()
                .putString("geocoder", text(geocoderUrlInput))
                .putString("routing", text(routingUrlInput))
                .putString("overpass", text(overpassUrlInput))
                .apply();
    }

    private void startVoiceDestinationSearch() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Bạn muốn đi đâu?");
        try {
            startActivityForResult(intent, VOICE_SEARCH_REQUEST);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this,
                    "Máy chưa có dịch vụ nhận dạng giọng nói. Hãy cài Google Speech Services.",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != VOICE_SEARCH_REQUEST || resultCode != RESULT_OK || data == null) return;
        ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (results == null || results.isEmpty()) {
            setStatus("Không nghe rõ điểm đến, hãy thử nói lại");
            return;
        }
        String destination = results.get(0).trim();
        destinationSearchInput.setText(destination);
        setStatus("Đã nghe: " + destination + " • đang tìm tuyến");
        searchAndRoute();
    }

    private void searchAndRoute() {
        if (routeBusy) return;
        Location origin = lastLocation;
        String query = text(destinationSearchInput);
        if (origin == null) {
            setStatus("Cần có GPS trước khi tìm đường");
            return;
        }
        if (query.length() < 2) {
            setStatus("Hãy nhập hoặc nói điểm đến");
            return;
        }
        saveProviderSettings();
        String geocoderEndpoint = text(geocoderUrlInput);
        String routingEndpoint = text(routingUrlInput);
        routeBusy = true;
        routeButton.setEnabled(false);
        voiceSearchButton.setEnabled(false);
        navigationInfo.setText("Đang tìm “" + query + "”…");
        setStatus("Đang tìm địa điểm bằng Nominatim • chỉ gửi một truy vấn do người dùng yêu cầu");
        double startLat = origin.getLatitude();
        double startLon = origin.getLongitude();
        networkWorker.execute(() -> {
            try {
                NavigationDataService.Place place = mapFeatureStore.cachedPlace(
                        query, SEARCH_CACHE_MS);
                if (place == null) {
                    place = navigationDataService.searchPlace(
                            query, startLat, startLon, geocoderEndpoint);
                    mapFeatureStore.cachePlace(query, place, System.currentTimeMillis());
                }
                RoutePlan plan = navigationDataService.route(
                        startLat, startLon, place, routingEndpoint);
                NavigationDataService.Place selected = place;
                runOnUiThread(() -> applyRoutePlan(selected, plan, false));
            } catch (Throwable error) {
                runOnUiThread(() -> finishRouteError(error));
            }
        });
    }

    private void routeToCurrentDestination(boolean replan) {
        if (routeBusy || currentDestination == null || lastLocation == null) return;
        routeBusy = true;
        routeButton.setEnabled(false);
        NavigationDataService.Place destination = currentDestination;
        String routingEndpoint = text(routingUrlInput);
        double startLat = lastLocation.getLatitude();
        double startLon = lastLocation.getLongitude();
        networkWorker.execute(() -> {
            try {
                RoutePlan plan = navigationDataService.route(
                        startLat, startLon, destination, routingEndpoint);
                runOnUiThread(() -> applyRoutePlan(destination, plan, replan));
            } catch (Throwable error) {
                runOnUiThread(() -> finishRouteError(error));
            }
        });
    }

    private void applyRoutePlan(
            NavigationDataService.Place destination, RoutePlan plan, boolean replan) {
        if (destroyed) return;
        routeBusy = false;
        routeButton.setEnabled(true);
        voiceSearchButton.setEnabled(true);
        currentDestination = destination;
        currentRoutePlan = plan;
        navigationSession.setPlan(plan);
        offRouteSince = 0L;
        navigationInfo.setText((replan ? "Đã tính lại • " : "")
                + shortPlaceName(destination.displayName) + " • "
                + formatDistance(plan.distanceMeters) + " • "
                + formatDuration(plan.durationSeconds));
        tripSummaryText.setText("Còn " + formatDistance(plan.distanceMeters)
                + " • " + formatDuration(plan.durationSeconds)
                + " • dự kiến " + formatArrivalTime(plan.durationSeconds));
        CarTelemetryStore.updateNavigation(
                destination.displayName, "Đã có tuyến", plan.distanceMeters, true);
        if (!mapVisible) toggleMap();
        redrawMapAnnotations();
        setStatus((replan ? "Đã tính lại tuyến" : "Đã tạo tuyến")
                + " • dữ liệu định tuyến OSRM/OpenStreetMap");
        if (ttsReady) speak(
                (replan ? "Đã tính lại tuyến đường" : "Đã tạo tuyến đến "
                        + shortPlaceName(destination.displayName)),
                TextToSpeech.QUEUE_ADD);
    }

    private void finishRouteError(Throwable error) {
        if (destroyed) return;
        routeBusy = false;
        routeButton.setEnabled(true);
        voiceSearchButton.setEnabled(true);
        String message = safeMessage(error);
        navigationInfo.setText("Không tạo được tuyến: " + message);
        setStatus("Lỗi tìm đường: " + message
                + " • kiểm tra Internet hoặc đổi máy chủ trong Cấu hình");
    }

    private void clearNavigation() {
        navigationSession.clear();
        currentRoutePlan = null;
        currentDestination = null;
        offRouteSince = 0L;
        navigationInfo.setText("Chưa chọn điểm đến");
        tripSummaryText.setText("GPS sẽ hiển thị quãng đường và thời gian dự kiến");
        CarTelemetryStore.updateNavigation("", "", 0d, false);
        redrawMapAnnotations();
        setStatus("Đã dừng dẫn đường");
    }

    private String shortPlaceName(String value) {
        if (value == null || value.trim().isEmpty()) return "điểm đến";
        String[] parts = value.split(",");
        return parts.length == 0 ? value : parts[0].trim();
    }

    private String formatDistance(double meters) {
        if (meters < 1_000d) return Math.round(meters) + " m";
        return String.format(Locale.US, "%.1f km", meters / 1_000d);
    }

    private String formatDuration(double seconds) {
        int minutes = Math.max(1, (int) Math.round(seconds / 60d));
        if (minutes < 60) return minutes + " phút";
        return minutes / 60 + " giờ " + minutes % 60 + " phút";
    }

    private String formatArrivalTime(double durationSeconds) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(System.currentTimeMillis()
                + Math.max(0L, Math.round(durationSeconds * 1_000d)));
        return String.format(Locale.US, "%02d:%02d",
                calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE));
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
                redrawMapAnnotations();
                if (lastLocation != null) {
                    updateMapPosition(lastLocation);
                    refreshTrafficMapData(false);
                }
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
        String metadata = "{\"name\":\"TrafficAI 2.3.2 • "
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

    private void redrawMapAnnotations() {
        if (!mapStyleReady || baseMap == null) return;
        baseMap.clear();
        routePolyline = null;
        mappedLandmarkIds.clear();
        mappedOsmFeatureIds.clear();
        refreshLandmarkMarkers();
        refreshCachedOsmMarkers();
        drawCurrentRoute();
    }

    private void refreshCachedOsmMarkers() {
        if (baseMap == null || mapFeatureStore == null || lastLocation == null) return;
        List<NavigationDataService.TrafficFeature> features = mapFeatureStore.nearbyFeatures(
                lastLocation.getLatitude(), lastLocation.getLongitude(), 12_000d, 260);
        for (NavigationDataService.TrafficFeature feature : features) {
            if (!mappedOsmFeatureIds.add(feature.osmId)) continue;
            String kind = mapFeatureKind(feature.kind);
            baseMap.addMarker(new MarkerOptions()
                    .position(new LatLng(feature.latitude, feature.longitude))
                    .title(kind + ": " + feature.label)
                    .snippet("Dữ liệu OpenStreetMap đã cache trong máy"));
        }
        if (refreshTrafficMapButton != null) {
            refreshTrafficMapButton.setText("ĐIỂM CẢNH BÁO OSM • " + features.size());
        }
    }

    private String mapFeatureKind(String kind) {
        if (NavigationDataService.TrafficFeature.LIGHT.equals(kind)) return "ĐÈN OSM";
        if (NavigationDataService.TrafficFeature.CAMERA.equals(kind)) return "CAMERA OSM";
        if (NavigationDataService.TrafficFeature.RAILWAY.equals(kind)) return "ĐƯỜNG SẮT OSM";
        if (NavigationDataService.TrafficFeature.TOLL.equals(kind)) return "THU PHÍ OSM";
        return "BIỂN OSM";
    }

    private void drawCurrentRoute() {
        if (baseMap == null || currentRoutePlan == null) return;
        List<LatLng> points = new ArrayList<>();
        for (RoutePlan.Point point : currentRoutePlan.geometry) {
            points.add(new LatLng(point.latitude, point.longitude));
        }
        if (points.size() >= 2) {
            routePolyline = baseMap.addPolyline(new PolylineOptions()
                    .addAll(points)
                    .color(Color.rgb(0, 145, 255))
                    .width(8f));
        }
        baseMap.addMarker(new MarkerOptions()
                .position(new LatLng(
                        currentRoutePlan.destinationLatitude,
                        currentRoutePlan.destinationLongitude))
                .title("ĐIỂM ĐẾN")
                .snippet(shortPlaceName(currentRoutePlan.destinationName)));
    }

    private void refreshTrafficMapData(boolean force) {
        Location location = lastLocation;
        if (location == null || mapFeatureStore == null || navigationDataService == null) {
            setStatus("Cần vị trí GPS trước khi nạp biển và đèn OSM");
            return;
        }
        if (mapStyleReady) redrawMapAnnotations();
        long now = SystemClock.elapsedRealtime();
        double moved = Double.isNaN(lastTrafficFetchLat) ? Double.POSITIVE_INFINITY
                : GeoMath.distanceMeters(
                lastTrafficFetchLat, lastTrafficFetchLon,
                location.getLatitude(), location.getLongitude());
        if (trafficDataBusy || (!force && moved < 2_000d
                && now - lastTrafficFetchAt < 30L * 60L * 1_000L)) return;

        saveProviderSettings();
        String endpoint = text(overpassUrlInput);
        double latitude = location.getLatitude();
        double longitude = location.getLongitude();
        trafficDataBusy = true;
        refreshTrafficMapButton.setEnabled(false);
        refreshTrafficMapButton.setText("ĐANG NẠP OSM…");
        networkWorker.execute(() -> {
            try {
                List<NavigationDataService.TrafficFeature> features =
                        navigationDataService.loadTrafficFeatures(latitude, longitude, endpoint);
                mapFeatureStore.saveFeatures(features, System.currentTimeMillis());
                runOnUiThread(() -> {
                    if (destroyed) return;
                    trafficDataBusy = false;
                    lastTrafficFetchLat = latitude;
                    lastTrafficFetchLon = longitude;
                    lastTrafficFetchAt = SystemClock.elapsedRealtime();
                    refreshTrafficMapButton.setEnabled(true);
                    redrawMapAnnotations();
                    String server = navigationDataService.getLastOverpassServer();
                    if (features.isEmpty()) {
                        setStatus("Đã kết nối " + server
                                + " nhưng OSM chưa có điểm cảnh báo trong vùng 5 km"
                                + " • Map Memory vẫn hoạt động");
                    } else {
                        setStatus("Đã gộp " + features.size()
                                + " điểm biển/đèn/camera OSM với Map Memory • nguồn " + server);
                    }
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    if (destroyed) return;
                    trafficDataBusy = false;
                    refreshTrafficMapButton.setEnabled(true);
                    refreshTrafficMapButton.setText("THỬ LẠI NẠP ĐIỂM CẢNH BÁO");
                    setStatus("Không nạp được OSM mới: " + safeMessage(error)
                            + " • vẫn hiển thị dữ liệu đã cache");
                });
            }
        });
    }

    private void updateMapButtonCount() {
        if (mapButton == null || landmarkStore == null || mapVisible) return;
        int count = landmarkStore.count();
        mapButton.setText(count > 0 ? "MAP " + count : "MAP");
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
                    if (phoneCameraMode) return;
                    cameraSourceBadge.setText("CAMERA: RTSP • ẢNH TRỰC TIẾP");
                    CarTelemetryStore.updateConnection(true, aiCoordinator != null);
                    setStatus("RTSP đã kết nối • mic camera đã tắt • video đang chạy"
                            + (aiCoordinator == null ? " • AI chưa mở" : " • AI đang phân tích"));
                } else if (state == Player.STATE_ENDED) {
                    if (!phoneCameraMode) cameraSourceBadge.setText("CAMERA: RTSP ĐÃ DỪNG");
                    CarTelemetryStore.updateConnection(false, aiCoordinator != null);
                    setStatus("Luồng RTSP đã kết thúc");
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                if (!phoneCameraMode) cameraSourceBadge.setText("CAMERA: RTSP LỖI");
                CarTelemetryStore.updateConnection(false, aiCoordinator != null);
                setStatus("Lỗi RTSP: " + error.getErrorCodeName());
                Toast.makeText(MainActivity.this,
                        "Không mở được camera. Kiểm tra IP, tài khoản, H.264 và cùng mạng Wi-Fi.",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void switchToPhoneCamera() {
        phoneCameraMode = true;
        phoneCameraPaused = false;
        if (mapVisible) toggleMap();
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        if (aiCoordinator != null) aiCoordinator.reset();
        resetUiResults();
        updateCameraSurfaceVisibility();
        cameraSourceBadge.setText("CAMERA: ĐIỆN THOẠI • ĐANG MỞ");
        setSettingsVisible(false);
        setStatus("Đang mở camera sau điện thoại • không thu âm mic");
        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA},
                    PHONE_CAMERA_PERMISSION_REQUEST);
            return;
        }
        openPhoneCamera();
        if (aiCoordinator == null) initializeAi();
    }

    private void rotatePhoneCameraPreview() {
        manualCameraRotationDegrees = CameraRotationPolicy.normalizeManualDegrees(
                manualCameraRotationDegrees + 90);
        getSharedPreferences(PROVIDER_PREFS, MODE_PRIVATE).edit()
                .putInt("phone_camera_rotation", manualCameraRotationDegrees)
                .apply();
        updatePhoneCameraRotationButton();
        configurePhoneCameraTransform(phoneCameraView.getWidth(), phoneCameraView.getHeight());
        cameraSourceBadge.setText("CAMERA: ĐIỆN THOẠI • BÙ XOAY "
                + manualCameraRotationDegrees + "°");
        Toast.makeText(this,
                "Đã bù xoay camera " + manualCameraRotationDegrees + " độ",
                Toast.LENGTH_SHORT).show();
    }

    private void updatePhoneCameraRotationButton() {
        if (rotatePhoneCameraButton == null) return;
        rotatePhoneCameraButton.setText(manualCameraRotationDegrees == 0
                ? "XOAY CAMERA 90° NẾU ẢNH CHƯA ĐÚNG"
                : "BÙ XOAY: " + manualCameraRotationDegrees + "° • BẤM ĐỂ XOAY TIẾP");
    }

    private void startPhoneCameraThread() {
        synchronized (phoneCameraLock) {
            if (phoneCameraThread != null) return;
            phoneCameraThread = new HandlerThread("TrafficAI-PhoneCamera");
            phoneCameraThread.start();
            phoneCameraHandler = new Handler(phoneCameraThread.getLooper());
        }
    }

    @SuppressLint("MissingPermission")
    private void openPhoneCamera() {
        if (!phoneCameraMode || phoneCameraPaused || destroyed
                || !phoneCameraView.isAvailable()) return;
        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) return;
        startPhoneCameraThread();
        synchronized (phoneCameraLock) {
            if (phoneCameraDevice != null || phoneCameraOpening) return;
            phoneCameraOpening = true;
        }
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            if (manager == null) throw new CameraAccessException(
                    CameraAccessException.CAMERA_ERROR, "CameraManager unavailable");
            String selectedId = null;
            Size selectedSize = null;
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
                StreamConfigurationMap map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) continue;
                Size candidate = choosePhonePreviewSize(
                        map.getOutputSizes(SurfaceTexture.class));
                if (candidate == null) continue;
                selectedId = cameraId;
                selectedSize = candidate;
                break;
            }
            if (selectedId == null || selectedSize == null) {
                throw new IllegalStateException("Không tìm thấy camera sau phù hợp");
            }
            phonePreviewSize = selectedSize;
            configurePhoneCameraTransform(phoneCameraView.getWidth(), phoneCameraView.getHeight());
            manager.openCamera(selectedId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    synchronized (phoneCameraLock) {
                        phoneCameraOpening = false;
                        if (!phoneCameraMode || phoneCameraPaused || destroyed) {
                            camera.close();
                            return;
                        }
                        phoneCameraDevice = camera;
                    }
                    createPhoneCameraPreview(camera);
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    synchronized (phoneCameraLock) {
                        phoneCameraOpening = false;
                        if (phoneCameraDevice == camera) phoneCameraDevice = null;
                    }
                    runOnUiThread(() -> {
                        CarTelemetryStore.updateConnection(false, aiCoordinator != null);
                        setStatus("Camera điện thoại đã bị ngắt");
                    });
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    synchronized (phoneCameraLock) {
                        phoneCameraOpening = false;
                        if (phoneCameraDevice == camera) phoneCameraDevice = null;
                    }
                    runOnUiThread(() -> {
                        CarTelemetryStore.updateConnection(false, aiCoordinator != null);
                        cameraSourceBadge.setText("CAMERA: LỖI " + error);
                        setStatus("Không mở được camera sau điện thoại • mã lỗi " + error);
                    });
                }
            }, phoneCameraHandler);
        } catch (Throwable error) {
            synchronized (phoneCameraLock) {
                phoneCameraOpening = false;
            }
            cameraSourceBadge.setText("CAMERA: LỖI");
            setStatus("Không mở được camera điện thoại: " + safeMessage(error));
        }
    }

    private void createPhoneCameraPreview(CameraDevice camera) {
        try {
            SurfaceTexture texture = phoneCameraView.getSurfaceTexture();
            Size size = phonePreviewSize;
            if (texture == null || size == null) {
                camera.close();
                return;
            }
            texture.setDefaultBufferSize(size.getWidth(), size.getHeight());
            Surface surface = new Surface(texture);
            CaptureRequest.Builder request = camera.createCaptureRequest(
                    CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(surface);
            request.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            request.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON);
            synchronized (phoneCameraLock) {
                if (phonePreviewSurface != null) phonePreviewSurface.release();
                phonePreviewSurface = surface;
            }
            camera.createCaptureSession(Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            synchronized (phoneCameraLock) {
                                if (phoneCameraDevice != camera || !phoneCameraMode
                                        || phoneCameraPaused || destroyed) {
                                    session.close();
                                    return;
                                }
                                phoneCameraSession = session;
                            }
                            try {
                                session.setRepeatingRequest(
                                        request.build(), null, phoneCameraHandler);
                                runOnUiThread(() -> {
                                    cameraSourceBadge.setText("CAMERA: ĐIỆN THOẠI • ẢNH TRỰC TIẾP");
                                    CarTelemetryStore.updateConnection(true, aiCoordinator != null);
                                    setStatus("Camera sau đã mở • mic không được thu • AI quét biển nhạy cao");
                                });
                            } catch (CameraAccessException error) {
                                runOnUiThread(() -> setStatus(
                                        "Lỗi chạy camera điện thoại: " + safeMessage(error)));
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            session.close();
                            runOnUiThread(() -> setStatus(
                                    "Camera điện thoại không tạo được luồng xem trước"));
                        }
                    }, phoneCameraHandler);
        } catch (Throwable error) {
            runOnUiThread(() -> setStatus(
                    "Không tạo được hình camera điện thoại: " + safeMessage(error)));
        }
    }

    private Size choosePhonePreviewSize(Size[] choices) {
        if (choices == null || choices.length == 0) return null;
        Size best = choices[0];
        long bestScore = Long.MAX_VALUE;
        final long targetPixels = (long) CAPTURE_WIDTH * CAPTURE_HEIGHT;
        for (Size size : choices) {
            long pixels = (long) size.getWidth() * size.getHeight();
            if (size.getWidth() > 1_920 || size.getHeight() > 1_920) continue;
            float ratio = Math.max(size.getWidth(), size.getHeight())
                    / (float) Math.max(1, Math.min(size.getWidth(), size.getHeight()));
            long ratioPenalty = Math.round(Math.abs(ratio - (16f / 9f)) * 2_000_000L);
            long score = Math.abs(pixels - targetPixels) + ratioPenalty;
            if (score < bestScore) {
                best = size;
                bestScore = score;
            }
        }
        return best;
    }

    private void configurePhoneCameraTransform(int viewWidth, int viewHeight) {
        Size size = phonePreviewSize;
        if (phoneCameraView == null || size == null || viewWidth <= 0 || viewHeight <= 0) return;
        int displayRotation = getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0f, 0f, viewWidth, viewHeight);
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        int totalRotation = CameraRotationPolicy.normalizeDegrees(
                Math.round(CameraRotationPolicy.previewRotationDegrees(displayRotation))
                        + manualCameraRotationDegrees);
        if (CameraRotationPolicy.isQuarterTurnDegrees(totalRotation)) {
            RectF bufferRect = new RectF(0f, 0f, size.getHeight(), size.getWidth());
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(viewHeight / (float) size.getHeight(),
                    viewWidth / (float) size.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
        }
        if (totalRotation != 0) {
            matrix.postRotate(totalRotation, centerX, centerY);
        }
        phoneCameraView.setTransform(matrix);
    }

    private void closePhoneCamera() {
        CameraCaptureSession session;
        CameraDevice camera;
        Surface surface;
        synchronized (phoneCameraLock) {
            session = phoneCameraSession;
            camera = phoneCameraDevice;
            surface = phonePreviewSurface;
            phoneCameraSession = null;
            phoneCameraDevice = null;
            phonePreviewSurface = null;
            phoneCameraOpening = false;
        }
        if (session != null) session.close();
        if (camera != null) camera.close();
        if (surface != null) surface.release();
    }

    private void stopPhoneCameraThread() {
        HandlerThread thread;
        synchronized (phoneCameraLock) {
            thread = phoneCameraThread;
            phoneCameraThread = null;
            phoneCameraHandler = null;
        }
        if (thread == null) return;
        thread.quitSafely();
        try {
            thread.join(800L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void updateCameraSurfaceVisibility() {
        boolean showCamera = !mapVisible;
        // Khi xem bản đồ, giữ nguồn đang dùng ở INVISIBLE để SurfaceTexture tiếp tục nhận
        // khung hình và cảnh báo giọng nói không bị dừng.
        playerView.setVisibility(!phoneCameraMode
                ? showCamera ? View.VISIBLE : View.INVISIBLE
                : View.GONE);
        phoneCameraView.setVisibility(phoneCameraMode
                ? showCamera ? View.VISIBLE : View.INVISIBLE
                : View.GONE);
    }

    private void connectRtsp() {
        try {
            phoneCameraMode = false;
            phoneCameraPaused = false;
            closePhoneCamera();
            updateCameraSurfaceVisibility();
            cameraSourceBadge.setText("CAMERA: RTSP • ĐANG KẾT NỐI");
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
        phoneCameraMode = false;
        phoneCameraPaused = false;
        closePhoneCamera();
        player.stop();
        player.clearMediaItems();
        updateCameraSurfaceVisibility();
        cameraSourceBadge.setText("CAMERA: ĐÃ TẮT");
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
        if (aiInitializing) {
            setStatus("AI đang được khởi tạo, vui lòng chờ");
            return;
        }
        aiInitializing = true;
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
                    aiInitializing = false;
                    ready.setLandmarkHint(currentLandmarkHint);
                    ready.setLanePreference(lanePreference);
                    boolean cameraReady = phoneCameraMode
                            ? phoneCameraDevice != null
                            : player != null && player.getPlaybackState() == Player.STATE_READY;
                    CarTelemetryStore.updateConnection(cameraReady, true);
                    aiBadge.setText("AI: SẴN SÀNG");
                    initAiButton.setEnabled(true);
                    modelProgress.setProgress(100);
                    setStatus("TrafficAI 2.3.2: camera đúng chiều • AI nhạy cao • ưu tiên làn "
                            + lanePreference.vi.toLowerCase(new Locale("vi", "VN")));
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    if (destroyed) return;
                    aiInitializing = false;
                    aiBadge.setText("AI: LỖI");
                    initAiButton.setEnabled(true);
                    setStatus("Lỗi khởi tạo AI: " + safeMessage(error));
                });
            }
        });
    }

    private void captureAndAnalyze() {
        if (aiCoordinator == null) return;
        TextureView source;
        if (phoneCameraMode) {
            if (phoneCameraSession == null || !phoneCameraView.isAvailable()) return;
            source = phoneCameraView;
        } else {
            if (player == null || player.getPlaybackState() != Player.STATE_READY) return;
            View surface = playerView.getVideoSurfaceView();
            if (!(surface instanceof TextureView) || !((TextureView) surface).isAvailable()) return;
            source = (TextureView) surface;
        }
        if (!frameBusy.compareAndSet(false, true)) return;
        if (captureBitmap == null || captureBitmap.isRecycled()) {
            captureBitmap = Bitmap.createBitmap(
                    CAPTURE_WIDTH, CAPTURE_HEIGHT, Bitmap.Config.ARGB_8888);
        }
        Bitmap frame = source.getBitmap(captureBitmap);
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
        aiBadge.setText("ADAS 2.3.2 • NHẠY CAO • " + result.engineStatus + " • "
                + String.format(Locale.US, "%.1f fps", smoothedAiFps)
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
            return;
        }

        boolean newSignTrack = result.signTrackId > 0L
                && result.signTrackId != lastSpokenSignTrackId;
        boolean correctedSign = result.signTrackId > 0L
                && result.signTrackId == lastSpokenSignTrackId
                && !result.signText.equals(lastSpokenSign)
                && result.signConfidence >= .55f
                && now - lastSignSpeechAt > 3_500L;
        boolean repeatedSign = result.signText.equals(lastSpokenSign)
                && now - lastSignSpeechAt > 30_000L;
        if (!result.signText.isEmpty()
                && ((newSignTrack && now - lastSignSpeechAt > 1_200L)
                || correctedSign || repeatedSign)) {
            SpeedSignPolicy.Parsed speedSign = SpeedSignPolicy.parse(result.signText);
            String announcement = speedSign == null
                    ? "Biển báo, " + result.signText
                    : speedSign.endsLimit
                    ? "Biển báo, hết giới hạn tốc độ"
                    : "Biển báo, giới hạn tốc độ " + speedSign.limitKmh
                    + " ki lô mét một giờ";
            speak(announcement, TextToSpeech.QUEUE_FLUSH);
            lastSpokenSign = result.signText;
            lastSpokenSignTrackId = result.signTrackId;
            lastSignSpeechAt = now;
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
        updateCameraSurfaceVisibility();
        overlayView.setVisibility(mapVisible ? View.GONE : View.VISIBLE);
        aiBadge.setVisibility(mapVisible ? View.GONE : View.VISIBLE);
        cameraSourceBadge.setVisibility(mapVisible ? View.GONE : View.VISIBLE);
        mapButton.setText(mapVisible ? "CAMERA" : "MAP");
        if (mapVisible && lastLocation != null) {
            lastMapCameraAt = 0L;
            redrawMapAnnotations();
            updateMapPosition(lastLocation);
            refreshTrafficMapData(false);
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
        if (requestCode == PHONE_CAMERA_PERMISSION_REQUEST) {
            if (checkSelfPermission(Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                openPhoneCamera();
                if (aiCoordinator == null) initializeAi();
            } else {
                phoneCameraMode = false;
                updateCameraSurfaceVisibility();
                cameraSourceBadge.setText("CAMERA: CHƯA CẤP QUYỀN");
                setStatus("Cần cấp quyền Camera để dùng camera sau điện thoại");
            }
        } else if (requestCode == LOCATION_PERMISSION_REQUEST && hasAnyLocationPermission()) {
            startGps();
        } else if (requestCode == LOCATION_PERMISSION_REQUEST) {
            speedResult.setText("GPS\nTẮT");
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
            speedResult.setText("GPS\nTẮT");
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
                speedResult.setText(requested ? "GPS\nĐANG TÌM" : "GPS\nTẮT");
                mapView.setStatus(requested
                        ? "ĐANG TÌM VỊ TRÍ • RA NGOÀI TRỜI"
                        : "HÃY BẬT VỊ TRÍ TRÊN ĐIỆN THOẠI");
            }
        } catch (Throwable error) {
            speedResult.setText("GPS\nLỖI");
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
        updateNavigationGuidance(location);
        if (!location.hasSpeed()) {
            currentSpeedKmh = 0;
            updateMapPosition(location);
            speedResult.setText("0\nkm/h");
            updateDriveMode();
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
        speedResult.setText(currentSpeedKmh + "\nkm/h");
        updateDriveMode();
        CarTelemetryStore.updateSpeed(currentSpeedKmh);
        evaluateOverSpeed();
    }

    private void updateDriveMode() {
        String mode = currentSpeedKmh >= 80 ? "CAO TỐC"
                : currentSpeedKmh >= 35 ? "ĐƯỜNG TRƯỜNG" : "ĐÔ THỊ";
        driveModeText.setText(mode + " • LÀN " + lanePreference.vi + " • GPS");
    }

    private void updateNavigationGuidance(Location location) {
        RoutePlan plan = navigationSession.getPlan();
        if (plan == null || location == null) return;
        NavigationSession.Guidance guidance = navigationSession.update(
                location.getLatitude(), location.getLongitude());
        if (!guidance.active) return;
        String text = guidance.arrived
                ? guidance.instruction
                : guidance.instruction + " • " + formatDistance(guidance.distanceMeters);
        navigationInfo.setText(text);
        CarTelemetryStore.updateNavigation(
                plan.destinationName, guidance.instruction,
                guidance.distanceMeters, true);
        if (guidance.shouldSpeak && ttsReady) {
            speak(guidance.arrived ? guidance.instruction
                    : NavigationInstruction.withDistance(
                    guidance.instruction, guidance.distanceMeters),
                    TextToSpeech.QUEUE_ADD);
        }
        if (guidance.arrived) {
            navigationSession.clear();
            currentRoutePlan = null;
            currentDestination = null;
            CarTelemetryStore.updateNavigation("", "Đã đến nơi", 0d, false);
            redrawMapAnnotations();
            setStatus("Đã đến điểm đến");
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (guidance.offRoute) {
            if (offRouteSince == 0L) offRouteSince = now;
            if (now - offRouteSince >= 8_000L && now - lastReplanAt >= 30_000L) {
                lastReplanAt = now;
                setStatus("Xe đã lệch tuyến • đang tính lại đường");
                routeToCurrentDestination(true);
            }
        } else {
            offRouteSince = 0L;
        }
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
        LandmarkHint learnedHint = landmarkStore.findNearby(
                location.getLatitude(), location.getLongitude(), heading, 160d);
        currentLandmarkHint = learnedHint.isActive()
                ? learnedHint : nearbyOsmHint(location, 180d);
        AiCoordinator engine = aiCoordinator;
        if (engine != null) engine.setLandmarkHint(currentLandmarkHint);
        CarTelemetryStore.updateLandmark(currentLandmarkHint);

        if (!currentLandmarkHint.isActive()) {
            lastHintId = -1L;
            roadAlertBanner.setText("ĐANG QUAN SÁT PHÍA TRƯỚC");
            return;
        }
        roadAlertBanner.setText(currentLandmarkHint.label.toUpperCase(new Locale("vi", "VN"))
                + " • " + Math.round(currentLandmarkHint.distanceMeters) + " m");
        updateLimitFromMapHint(currentLandmarkHint);
        long now = SystemClock.elapsedRealtime();
        if (currentLandmarkHint.distanceMeters >= 20d
                && currentLandmarkHint.distanceMeters <= 130d
                && currentSpeedKmh >= 8
                && (currentLandmarkHint.id != lastHintId
                || now - lastHintSpeechAt >= 60_000L)) {
            String message = mapAlertSpeech(currentLandmarkHint);
            if (ttsReady) speak(message, TextToSpeech.QUEUE_ADD);
            lastHintId = currentLandmarkHint.id;
            lastHintSpeechAt = now;
        }
    }

    private String mapAlertSpeech(LandmarkHint hint) {
        if (hint.expectsLight()) return "Sắp đến vị trí đèn giao thông";
        if (hint.isMapAlert()) {
            String lower = hint.label.toLowerCase(new Locale("vi", "VN"));
            if (lower.contains("camera")) return "Chú ý, sắp tới vị trí camera giao thông";
            if (lower.contains("đường sắt")) return "Chú ý, sắp tới giao cắt đường sắt";
            if (lower.contains("thu phí")) return "Sắp tới trạm thu phí";
            return "Sắp tới điểm cảnh báo giao thông";
        }
        return "Sắp đến biển " + hint.label;
    }

    private void updateLimitFromMapHint(LandmarkHint hint) {
        if (hint == null || !hint.expectsSign() || hint.distanceMeters > 120d) return;
        SpeedSignPolicy.Parsed parsed = SpeedSignPolicy.parse(hint.label);
        if (parsed == null || parsed.endsLimit) return;
        if (parsed.limitKmh != speedLimitKmh) {
            setSpeedLimit(parsed.limitKmh, "Dữ liệu OSM gần xe");
            if (ttsReady) speak("Giới hạn tốc độ sắp tới " + parsed.limitKmh,
                    TextToSpeech.QUEUE_ADD);
        }
    }

    private LandmarkHint nearbyOsmHint(Location location, double radiusMeters) {
        if (mapFeatureStore == null) return LandmarkHint.NONE;
        List<NavigationDataService.TrafficFeature> features = mapFeatureStore.nearbyFeatures(
                location.getLatitude(), location.getLongitude(), radiusMeters, 20);
        NavigationDataService.TrafficFeature best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        double bestScore = Double.POSITIVE_INFINITY;
        float heading = location.hasBearing() ? location.getBearing() : lastTravelBearing;
        for (NavigationDataService.TrafficFeature feature : features) {
            double distance = GeoMath.distanceMeters(
                    location.getLatitude(), location.getLongitude(),
                    feature.latitude, feature.longitude);
            double bearing = GeoMath.bearingDegrees(
                    location.getLatitude(), location.getLongitude(),
                    feature.latitude, feature.longitude);
            double directionDelta = GeoMath.headingDifference(heading, bearing);
            if (currentSpeedKmh >= 5 && directionDelta > 85d) continue;
            double score = distance + directionDelta * .55d;
            if (score < bestScore) {
                best = feature;
                bestDistance = distance;
                bestScore = score;
            }
        }
        if (best == null) return LandmarkHint.NONE;
        boolean light = NavigationDataService.TrafficFeature.LIGHT.equals(best.kind);
        boolean sign = NavigationDataService.TrafficFeature.SIGN.equals(best.kind);
        long syntheticId = 4_000_000_000L + (best.osmId.hashCode() & 0x7fffffffL);
        return new LandmarkHint(
                syntheticId,
                light ? LandmarkHint.TYPE_LIGHT
                        : sign ? LandmarkHint.TYPE_SIGN : LandmarkHint.TYPE_ALERT,
                best.label,
                light ? .68f : .80f,
                light ? .34f : .45f,
                bestDistance,
                1);
    }

    private void updateLimitFromSign(AiResult result) {
        if (result == null || result.signText == null || result.signTrackId <= 0L
                || result.signConfidence < .33f) return;
        String label = result.signText.trim();
        SpeedSignPolicy.Parsed parsed = SpeedSignPolicy.parse(label);
        if (parsed == null) return;
        if (result.signTrackId == lastAppliedSpeedSignTrackId
                && label.equals(lastAppliedSpeedSignLabel)) return;
        lastAppliedSpeedSignTrackId = result.signTrackId;
        lastAppliedSpeedSignLabel = label;
        int confidence = Math.round(result.signConfidence * 100f);
        if (parsed.endsLimit) {
            if (speedLimitKmh > 0) {
                setSpeedLimit(0, "Biển AI hết hạn chế " + confidence + "%");
                roadAlertBanner.setText("HẾT GIỚI HẠN TỐC ĐỘ • BIỂN AI " + confidence + "%");
            }
            return;
        }
        setSpeedLimit(parsed.limitKmh, "Biển báo AI " + confidence + "%");
        roadAlertBanner.setText("GIỚI HẠN " + parsed.limitKmh
                + " • BIỂN AI " + confidence + "%");
    }

    private void setSpeedLimit(int value, String source) {
        speedLimitKmh = Math.max(0, value);
        String sourceTag = source != null && source.contains("AI") ? "AI"
                : source != null && source.contains("OSM") ? "MAP" : "TAY";
        speedLimitResult.setText(speedLimitKmh > 0
                ? speedLimitKmh + "\n" + sourceTag
                : "MAX\n—");
        CarTelemetryStore.updateLimit(speedLimitKmh, source);
        overSpeedSince = 0L;
    }

    private void evaluateOverSpeed() {
        long now = SystemClock.elapsedRealtime();
        if (speedLimitKmh <= 0 || currentSpeedKmh <= speedLimitKmh + 3) {
            overSpeedSince = 0L;
            speedResult.setTextColor(Color.WHITE);
            return;
        }
        speedResult.setTextColor(Color.rgb(255, 102, 92));
        if (overSpeedSince == 0L) overSpeedSince = now;
        if (now - overSpeedSince >= 1_200L && now - lastOverSpeedSpeechAt >= 10_000L) {
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
        hazardResult.setText("PHÍA TRƯỚC • ĐANG QUAN SÁT");
        roadAlertBanner.setText("ĐANG QUAN SÁT PHÍA TRƯỚC");
        lastSpokenSignal = "";
        lastSpokenSign = "";
        lastSpokenSignTrackId = -1L;
        lastAppliedSpeedSignTrackId = -1L;
        lastAppliedSpeedSignLabel = "";
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
    public void onBackPressed() {
        if (settingsPanel != null && settingsPanel.getVisibility() == View.VISIBLE) {
            setSettingsVisible(false);
            return;
        }
        if (mapVisible) {
            toggleMap();
            return;
        }
        super.onBackPressed();
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
        phoneCameraPaused = false;
        if (phoneCameraMode) openPhoneCamera();
        if (!destroyed) startFramePump();
    }

    @Override
    protected void onPause() {
        stopFramePump();
        phoneCameraPaused = true;
        closePhoneCamera();
        saveProviderSettings();
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
        phoneCameraMode = false;
        phoneCameraPaused = true;
        closePhoneCamera();
        stopPhoneCameraThread();
        networkWorker.shutdownNow();
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
        if (mapFeatureStore != null) {
            mapFeatureStore.close();
            mapFeatureStore = null;
        }
        if (baseMapView != null) baseMapView.onDestroy();
        baseMap = null;
        mapView = null;
        baseMapView = null;
        worker.shutdown();
        super.onDestroy();
    }
}
