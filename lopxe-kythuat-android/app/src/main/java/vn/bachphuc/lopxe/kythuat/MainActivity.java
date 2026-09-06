package vn.bachphuc.lopxe.kythuat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String PREFS = "lopxe_tech_config";
    private static final String KEY_SERVER = "server_url";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView webView;
    private View setupPanel;
    private TextView statusText;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webView);
        setupPanel = findViewById(R.id.setupPanel);
        statusText = findViewById(R.id.statusText);
        configureWebView();
        findViewById(R.id.autoButton).setOnClickListener(v -> autoConfigure());
        findViewById(R.id.manualButton).setOnClickListener(v -> showManualDialog());

        String saved = prefs().getString(KEY_SERVER, "");
        if (!saved.isEmpty()) connect(saved, false);
    }

    private void configureWebView() {
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
            @Override public void onPageFinished(WebView view, String url) {
                setupPanel.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
            }
        });
        webView.setOnLongClickListener(v -> {
            showManualDialog();
            return true;
        });
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void autoConfigure() {
        statusText.setText("Đang tìm máy chủ Lốp Xe trong Wi‑Fi…");
        worker.execute(() -> {
            for (String candidate : autoCandidates()) {
                if (isHealthy(candidate)) {
                    main.post(() -> connect(candidate, true));
                    return;
                }
            }
            main.post(() -> {
                statusText.setText("Không tìm thấy máy chủ. Kiểm tra Wi‑Fi hoặc cấu hình thủ công.");
                Toast.makeText(this, "Không tìm thấy máy chủ", Toast.LENGTH_LONG).show();
            });
        });
    }

    private List<String> autoCandidates() {
        Set<String> urls = new LinkedHashSet<>();
        urls.add("http://lopxe.local");
        urls.add("http://lopxe.local:8080");
        urls.add("http://lopxe-server.local");
        urls.add("http://lopxe-server.local:8080");
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network = cm.getActiveNetwork();
        LinkProperties props = network == null ? null : cm.getLinkProperties(network);
        if (props != null) for (LinkAddress address : props.getLinkAddresses()) {
            byte[] raw = address.getAddress().getAddress();
            if (raw.length == 4) {
                String prefix = (raw[0] & 255) + "." + (raw[1] & 255) + "." + (raw[2] & 255) + ".";
                for (int host : new int[]{1, 2, 10, 100, 200, 254}) {
                    urls.add("https://" + prefix + host);
                    urls.add("https://" + prefix + host + ":8443");
                }
            }
        }
        return new ArrayList<>(urls);
    }

    private boolean isHealthy(String base) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(base + "/api/auth/me").openConnection();
            c.setConnectTimeout(900);
            c.setReadTimeout(900);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            c.disconnect();
            return code >= 200 && code < 500;
        } catch (Exception ignored) { return false; }
    }

    private void showManualDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Ví dụ: https://192.168.1.10:8443");
        input.setText(prefs().getString(KEY_SERVER, "https://"));
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        LinearLayout field = new LinearLayout(this);
        field.setPadding(pad, 0, pad, 0);
        field.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Cấu hình máy chủ thủ công")
                .setMessage("Nhập địa chỉ IP hoặc tên máy chủ trong Wi‑Fi cửa hàng")
                .setView(field)
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Kiểm tra & lưu", (d, w) -> verifyManual(input.getText().toString()))
                .show();
    }

    private void verifyManual(String raw) {
        String url = normalize(raw);
        if (url.isEmpty()) return;
        statusText.setText("Đang kiểm tra " + url + "…");
        worker.execute(() -> {
            boolean healthy = isHealthy(url);
            main.post(() -> {
                if (healthy) connect(url, true);
                else {
                    statusText.setText("Không kết nối được. Kiểm tra IP, cổng và Wi‑Fi.");
                    Toast.makeText(this, "Kết nối thất bại", Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private String normalize(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.equals("http://") || value.equals("https://")) return "";
        if (!value.startsWith("http://") && !value.startsWith("https://")) value = "https://" + value;
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private void connect(String server, boolean save) {
        if (save) prefs().edit().putString(KEY_SERVER, server).apply();
        statusText.setText("Đang kết nối máy chủ…");
        webView.loadUrl(server + "/?device=tablet&role=tech");
    }

    @Override public void onBackPressed() {
        if (webView.getVisibility() == View.VISIBLE && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        webView.destroy();
        worker.shutdownNow();
        super.onDestroy();
    }
}
