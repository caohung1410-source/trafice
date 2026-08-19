package vn.bachphuc.trafficai;

import android.app.Activity;
import android.graphics.Bitmap;
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
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.ui.PlayerView;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@OptIn(markerClass = UnstableApi.class)
public final class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final long FRAME_INTERVAL_MS = 360L;
    private static final int CAPTURE_WIDTH = 960;
    private static final int CAPTURE_HEIGHT = 540;

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
    private ProgressBar modelProgress;
    private Button initAiButton;

    private ExoPlayer player;
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
            setStatus("Giao diện sẵn sàng • lần đầu cần tải khoảng 50 MB model AI");
        }
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
        modelProgress = findViewById(R.id.modelProgress);
        initAiButton = findViewById(R.id.initAiButton);
    }

    private void bindActions() {
        Button toggleSettings = findViewById(R.id.toggleSettingsButton);
        Button subStream = findViewById(R.id.subStreamButton);
        Button mainStream = findViewById(R.id.mainStreamButton);
        Button connect = findViewById(R.id.connectButton);
        Button disconnect = findViewById(R.id.disconnectButton);

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
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) {
                    setStatus("Đang kết nối/buffer RTSP…");
                } else if (state == Player.STATE_READY) {
                    setStatus("RTSP đã kết nối • video đang chạy"
                            + (aiCoordinator == null ? " • AI chưa mở" : " • AI đang phân tích"));
                } else if (state == Player.STATE_ENDED) {
                    setStatus("Luồng RTSP đã kết thúc");
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
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
                aiCoordinator = ready;
                runOnUiThread(() -> {
                    if (destroyed) return;
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
                    setStatus("Lỗi tải/khởi tạo AI: " + safeMessage(error));
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
        Bitmap frame = ((TextureView) surface).getBitmap(CAPTURE_WIDTH, CAPTURE_HEIGHT);
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
                frame.recycle();
                frameBusy.set(false);
            }
        });
    }

    private void applyAiResult(AiResult result) {
        if (destroyed) return;
        overlayView.setResult(result, CAPTURE_WIDTH, CAPTURE_HEIGHT);
        aiBadge.setText("AI: " + result.inferenceMs + " ms");

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
        if (!signal.isEmpty() && !signal.equals(lastSpokenSignal) && now - lastSignalSpeechAt > 1_200) {
            speak("Đèn " + signal.toLowerCase(new Locale("vi", "VN")), TextToSpeech.QUEUE_FLUSH);
            lastSpokenSignal = signal;
            lastSpokenCountdown = null;
            lastSignalSpeechAt = now;
        }

        Integer number = result.countdown;
        if (number != null && !number.equals(lastSpokenCountdown)) {
            boolean read = number <= 5
                    || lastSpokenCountdown == null
                    || Math.abs(lastSpokenCountdown - number) >= 3;
            if (read) {
                String phrase = number <= 5 ? String.valueOf(number) : "Còn " + number + " giây";
                speak(phrase, TextToSpeech.QUEUE_ADD);
                lastSpokenCountdown = number;
            }
        }

        if (!result.signText.isEmpty()
                && (!result.signText.equals(lastSpokenSign) || now - lastSignSpeechAt > 12_000)) {
            speak(result.signText, TextToSpeech.QUEUE_ADD);
            lastSpokenSign = result.signText;
            lastSignSpeechAt = now;
        }
    }

    private void speak(String text, int queueMode) {
        if (!ttsReady || textToSpeech == null) return;
        textToSpeech.speak(text, queueMode, null, "trafficai-" + SystemClock.elapsedRealtime());
    }

    private void resetUiResults() {
        overlayView.clear();
        lightResult.setText("ĐÈN\n—");
        countdownResult.setText("GIÂY\n—");
        signResult.setText("BIỂN BÁO\n—");
        lastSpokenSignal = "";
        lastSpokenSign = "";
        lastSpokenCountdown = null;
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
            int language = textToSpeech.setLanguage(new Locale("vi", "VN"));
            textToSpeech.setSpeechRate(0.82f);
            ttsReady = language != TextToSpeech.LANG_MISSING_DATA
                    && language != TextToSpeech.LANG_NOT_SUPPORTED;
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
        worker.shutdown();
        super.onDestroy();
    }
}
