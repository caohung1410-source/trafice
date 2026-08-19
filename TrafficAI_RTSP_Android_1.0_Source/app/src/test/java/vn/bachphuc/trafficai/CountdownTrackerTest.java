package vn.bachphuc.trafficai;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class CountdownTrackerTest {
    @Test
    public void rejectsIncreasingCountdownAndResetsOnColorChange() {
        CountdownTracker tracker = new CountdownTracker();
        long t = 10_000;
        tracker.update(TrafficState.RED, .9f, 12, .9f, t);
        tracker.update(TrafficState.RED, .9f, 12, .9f, t + 100);
        tracker.update(TrafficState.RED, .9f, 12, .9f, t + 200);
        CountdownTracker.Result red = tracker.update(TrafficState.RED, .9f, 12, .9f, t + 300);
        assertEquals(TrafficState.RED, red.state);
        assertEquals(Integer.valueOf(12), red.visibleNumber);

        tracker.update(TrafficState.RED, .9f, 13, .9f, t + 400);
        CountdownTracker.Result invalid = tracker.update(TrafficState.RED, .9f, 13, .9f, t + 500);
        assertEquals(Integer.valueOf(12), invalid.visibleNumber);

        tracker.update(TrafficState.GREEN, .9f, 8, .9f, t + 1_600);
        tracker.update(TrafficState.GREEN, .9f, 8, .9f, t + 1_700);
        CountdownTracker.Result green = tracker.update(TrafficState.GREEN, .9f, 8, .9f, t + 1_800);
        assertEquals(TrafficState.GREEN, green.state);
        assertNull(green.visibleNumber);
    }
}
