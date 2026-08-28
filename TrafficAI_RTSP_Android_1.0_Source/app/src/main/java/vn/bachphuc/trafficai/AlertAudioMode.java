package vn.bachphuc.trafficai;

import java.util.Locale;

/** Ba chế độ âm thanh khi lái xe, có thứ tự để nút loa chuyển nhanh từng chế độ. */
public enum AlertAudioMode {
    VOICE("ĐỌC", "Đọc đầy đủ bằng giọng nói"),
    CHIME("ĐING ĐINH", "Chỉ phát tiếng đing đinh"),
    MUTE("TẮT TIẾNG", "Không phát âm thanh");

    public final String shortLabel;
    public final String description;

    AlertAudioMode(String shortLabel, String description) {
        this.shortLabel = shortLabel;
        this.description = description;
    }

    public AlertAudioMode next() {
        if (this == VOICE) return CHIME;
        if (this == CHIME) return MUTE;
        return VOICE;
    }

    public static AlertAudioMode fromStored(String value, boolean legacyVoiceEnabled) {
        if (value == null || value.trim().isEmpty()) {
            return legacyVoiceEnabled ? VOICE : MUTE;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.US));
        } catch (IllegalArgumentException ignored) {
            return legacyVoiceEnabled ? VOICE : MUTE;
        }
    }
}
