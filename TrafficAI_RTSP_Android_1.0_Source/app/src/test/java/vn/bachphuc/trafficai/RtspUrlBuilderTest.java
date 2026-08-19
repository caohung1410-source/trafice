package vn.bachphuc.trafficai;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RtspUrlBuilderTest {
    @Test
    public void buildsImouUrlAndEncodesPassword() {
        String url = RtspUrlBuilder.build(
                "", "192.168.1.108", "554", "admin", "A b@1",
                "/cam/realmonitor?channel=1&subtype=1");
        assertTrue(url.startsWith("rtsp://admin:A%20b%401@192.168.1.108:554/"));
    }

    @Test
    public void redactsUserInfo() {
        String redacted = RtspUrlBuilder.redact("rtsp://admin:secret@192.168.1.10:554/live");
        assertFalse(redacted.contains("secret"));
        assertTrue(redacted.contains("***:***@"));
    }
}
