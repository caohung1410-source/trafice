package vn.bachphuc.trafficai;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Lưu hồ sơ camera trên chính điện thoại. URL đầy đủ và Safety Code được mã hóa bằng
 * Android Keystore; không có khóa hoặc bản rõ nào được ghi vào SharedPreferences.
 */
public final class CameraProfileStore {
    private static final String PREFS = "camera_profile_v2";
    private static final String KEY_ALIAS = "trafficai_camera_profile_aes_v2";
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";

    private final SharedPreferences preferences;

    public CameraProfileStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void save(Profile profile) {
        if (profile == null) return;
        SharedPreferences.Editor editor = preferences.edit()
                .putString("host", safe(profile.host))
                .putString("port", safe(profile.port))
                .putString("username", safe(profile.username))
                .putString("path", safe(profile.path))
                .putString("pinned_mac", MacAddressPolicy.normalize(profile.pinnedMac))
                .putBoolean("rtp_tcp", profile.rtpTcp)
                .putBoolean("auto_reconnect", profile.autoReconnect)
                .putString("source", profile.phoneCamera ? "phone" : "rtsp");
        putEncrypted(editor, "full_url_ciphertext", profile.fullUrl);
        putEncrypted(editor, "password_ciphertext", profile.password);
        editor.apply();
    }

    public synchronized Profile load() {
        return new Profile(
                decrypt(preferences.getString("full_url_ciphertext", "")),
                preferences.getString("host", ""),
                preferences.getString("port", "554"),
                preferences.getString("username", "admin"),
                decrypt(preferences.getString("password_ciphertext", "")),
                preferences.getString("path",
                        "/cam/realmonitor?channel=1&subtype=0&unicast=true&proto=Onvif"),
                preferences.getString("pinned_mac", ""),
                preferences.getBoolean("rtp_tcp", true),
                preferences.getBoolean("auto_reconnect", true),
                "phone".equals(preferences.getString("source", "rtsp")));
    }

    private void putEncrypted(SharedPreferences.Editor editor, String key, String value) {
        String plain = value == null ? "" : value;
        if (!"password_ciphertext".equals(key)) plain = plain.trim();
        if (plain.isEmpty()) {
            editor.remove(key);
            return;
        }
        try {
            editor.putString(key, encrypt(plain));
        } catch (Exception ignored) {
            // Không hạ cấp sang lưu bản rõ khi Keystore tạm thời không hoạt động.
            editor.remove(key);
        }
    }

    private String encrypt(String plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        ByteBuffer buffer = ByteBuffer.allocate(4 + iv.length + encrypted.length);
        buffer.putInt(iv.length);
        buffer.put(iv);
        buffer.put(encrypted);
        return Base64.encodeToString(buffer.array(), Base64.NO_WRAP);
    }

    private String decrypt(String encoded) {
        if (encoded == null || encoded.isEmpty()) return "";
        try {
            ByteBuffer buffer = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP));
            int ivLength = buffer.getInt();
            if (ivLength < 12 || ivLength > 32 || buffer.remaining() <= ivLength) return "";
            byte[] iv = new byte[ivLength];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance(ANDROID_KEY_STORE);
        store.load(null);
        KeyStore.Entry entry = store.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class Profile {
        public final String fullUrl;
        public final String host;
        public final String port;
        public final String username;
        public final String password;
        public final String path;
        public final String pinnedMac;
        public final boolean rtpTcp;
        public final boolean autoReconnect;
        public final boolean phoneCamera;

        public Profile(
                String fullUrl,
                String host,
                String port,
                String username,
                String password,
                String path,
                String pinnedMac,
                boolean rtpTcp,
                boolean autoReconnect,
                boolean phoneCamera) {
            this.fullUrl = safe(fullUrl);
            this.host = safe(host);
            this.port = safe(port);
            this.username = safe(username);
            this.password = password == null ? "" : password;
            this.path = safe(path);
            this.pinnedMac = MacAddressPolicy.normalize(pinnedMac);
            this.rtpTcp = rtpTcp;
            this.autoReconnect = autoReconnect;
            this.phoneCamera = phoneCamera;
        }
    }
}
