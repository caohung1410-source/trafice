package vn.bachphuc.trafficai;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public final class ModelRepository {
    public static final String LIGHT_MODEL_URL =
            "https://huggingface.co/webnn/yolo11n/resolve/main/onnx/yolo11n.onnx";
    public static final String SIGN_MODEL_URL =
            "https://huggingface.co/star092304/traffic-sign-detection-vietnam-yolo/resolve/main/best.onnx";

    private static final long MIN_LIGHT_BYTES = 3_000_000L;
    private static final long MIN_SIGN_BYTES = 30_000_000L;

    private final Context context;
    private final File directory;

    public ModelRepository(Context context) {
        this.context = context.getApplicationContext();
        this.directory = new File(context.getFilesDir(), "trafficai_models");
        if (!directory.exists()) directory.mkdirs();
    }

    public File lightModel() {
        return new File(directory, "yolo11n_coco.onnx");
    }

    public File signModel() {
        return new File(directory, "traffic_sign_vietnam_yolo11s.onnx");
    }

    public boolean isReady() {
        return valid(lightModel(), MIN_LIGHT_BYTES) && valid(signModel(), MIN_SIGN_BYTES);
    }

    public void ensureModels(ProgressListener listener) throws Exception {
        if (!valid(lightModel(), MIN_LIGHT_BYTES)) {
            download("AI đèn tín hiệu", LIGHT_MODEL_URL, lightModel(), MIN_LIGHT_BYTES, 0, 28, listener);
        } else {
            listener.onProgress("AI đèn đã có", 28);
        }
        if (!valid(signModel(), MIN_SIGN_BYTES)) {
            download("AI biển báo Việt Nam", SIGN_MODEL_URL, signModel(), MIN_SIGN_BYTES, 28, 98, listener);
        } else {
            listener.onProgress("AI biển báo đã có", 98);
        }
        listener.onProgress("Đã tải đủ model", 100);
    }

    public String[] loadSignLabels() throws Exception {
        List<String> labels = new ArrayList<>();
        try (InputStream input = context.getAssets().open("sign_labels_vi.txt");
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String value = line.trim();
                if (!value.isEmpty()) labels.add(value);
            }
        }
        if (labels.size() != 82) {
            throw new IllegalStateException("Danh sách biển phải có đúng 82 lớp, hiện có " + labels.size());
        }
        return labels.toArray(new String[0]);
    }

    private void download(
            String name,
            String url,
            File destination,
            long minimumBytes,
            int progressStart,
            int progressEnd,
            ProgressListener listener) throws Exception {
        File temp = new File(destination.getParentFile(), destination.getName() + ".part");
        if (temp.exists()) temp.delete();

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(60_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "TrafficAI-Android/1.0");
        connection.connect();
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            connection.disconnect();
            throw new IllegalStateException(name + " tải lỗi HTTP " + code);
        }

        long total = connection.getContentLengthLong();
        long received = 0;
        byte[] buffer = new byte[128 * 1024];
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             FileOutputStream output = new FileOutputStream(temp)) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count == 0) continue;
                output.write(buffer, 0, count);
                received += count;
                int progress;
                if (total > 0) {
                    progress = progressStart + (int) ((progressEnd - progressStart) * received / total);
                } else {
                    progress = Math.min(progressEnd - 1, progressStart + (int) (received / 1_000_000L));
                }
                listener.onProgress(name + " • " + (received / 1_048_576L) + " MB", progress);
            }
            output.getFD().sync();
        } finally {
            connection.disconnect();
        }

        if (!valid(temp, minimumBytes)) {
            temp.delete();
            throw new IllegalStateException(name + " tải thiếu dữ liệu");
        }
        Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        listener.onProgress(name + " • OK", progressEnd);
    }

    private boolean valid(File file, long minimumBytes) {
        return file.isFile() && file.length() >= minimumBytes;
    }

    public interface ProgressListener {
        void onProgress(String message, int percent);
    }
}
