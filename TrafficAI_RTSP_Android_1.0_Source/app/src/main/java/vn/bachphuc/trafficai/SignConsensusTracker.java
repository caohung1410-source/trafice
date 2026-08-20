package vn.bachphuc.trafficai;

import android.graphics.RectF;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Theo dõi từng biển theo vị trí rồi mới bỏ phiếu lớp, thay vì gộp mọi biển cùng loại. */
public final class SignConsensusTracker {
    private static final long WINDOW_MS = 5_000L;
    private static final long TRACK_LOST_MS = 5_400L;
    private static final long STABLE_LOST_MS = 4_200L;
    private static final float MIN_SAMPLE_CONFIDENCE = .13f;
    private static final float MATCH_THRESHOLD = .20f;

    private final List<Track> tracks = new ArrayList<>();
    private long nextTrackId = 1L;
    private Stable stable;

    public synchronized Stable update(List<Detection> observations, long nowMs) {
        prune(nowMs);
        List<Detection> sorted = new ArrayList<>(observations);
        sorted.sort(Comparator.comparingDouble((Detection value) -> value.confidence).reversed());
        Set<Long> updatedTrackIds = new HashSet<>();

        for (Detection detection : sorted) {
            if (detection == null || detection.confidence < MIN_SAMPLE_CONFIDENCE) continue;
            Track best = null;
            float bestAffinity = -1f;
            for (Track candidate : tracks) {
                if (updatedTrackIds.contains(candidate.id)) continue;
                float affinity = spatialAffinity(candidate.lastDetection, detection);
                if (affinity > bestAffinity) {
                    bestAffinity = affinity;
                    best = candidate;
                }
            }
            if (best == null || bestAffinity < MATCH_THRESHOLD) {
                best = new Track(nextTrackId++);
                tracks.add(best);
            }
            best.add(detection, nowMs);
            updatedTrackIds.add(best.id);
        }

        Stable bestStable = null;
        for (Track track : tracks) {
            Stable candidate = track.evaluate(nowMs);
            if (candidate != null
                    && (bestStable == null || candidate.confidence > bestStable.confidence)) {
                bestStable = candidate;
            }
        }
        if (bestStable != null) stable = bestStable;
        if (stable != null && nowMs - stable.lastSeenAt > STABLE_LOST_MS) stable = null;
        return stable;
    }

    public synchronized int activeTrackCount() {
        return tracks.size();
    }

    public synchronized void reset() {
        tracks.clear();
        stable = null;
        nextTrackId = 1L;
    }

    private void prune(long nowMs) {
        for (Track track : tracks) track.prune(nowMs);
        tracks.removeIf(track -> track.samples.isEmpty()
                || nowMs - track.lastSeenAt > TRACK_LOST_MS);
        while (tracks.size() > 48) {
            Track oldest = tracks.stream()
                    .min(Comparator.comparingLong(value -> value.lastSeenAt))
                    .orElse(null);
            if (oldest == null) break;
            tracks.remove(oldest);
        }
    }

    private static float spatialAffinity(Detection first, Detection second) {
        if (first == null || second == null) return 0f;
        RectF a = first.box;
        RectF b = second.box;
        return SignTrackMath.affinity(
                a.left, a.top, a.right, a.bottom,
                b.left, b.top, b.right, b.bottom);
    }

    private static final class Track {
        final long id;
        final Deque<Sample> samples = new ArrayDeque<>();
        Detection lastDetection;
        long lastSeenAt;

        Track(long id) {
            this.id = id;
        }

        void add(Detection detection, long nowMs) {
            samples.addLast(new Sample(detection, nowMs));
            lastDetection = detection;
            lastSeenAt = nowMs;
            prune(nowMs);
            while (samples.size() > 7) samples.removeFirst();
        }

        void prune(long nowMs) {
            while (!samples.isEmpty() && nowMs - samples.peekFirst().at > WINDOW_MS) {
                samples.removeFirst();
            }
        }

        Stable evaluate(long nowMs) {
            if (samples.size() < 2 || nowMs - lastSeenAt > 1_800L) return null;
            Map<Integer, Vote> votes = new HashMap<>();
            for (Sample sample : samples) {
                Vote vote = votes.get(sample.detection.classId);
                if (vote == null) {
                    vote = new Vote();
                    votes.put(sample.detection.classId, vote);
                }
                vote.count++;
                vote.totalConfidence += sample.detection.confidence;
                vote.strongest = Math.max(vote.strongest, sample.detection.confidence);
                if (vote.last == null || sample.at > vote.last.at) vote.last = sample;
            }

            Stable best = null;
            for (Vote vote : votes.values()) {
                float average = vote.totalConfidence / Math.max(1, vote.count);
                SignDecisionPolicy.Decision decision = SignDecisionPolicy.evaluate(
                        vote.count, samples.size(), average, vote.strongest);
                if (!decision.confirmed || vote.last == null
                        || nowMs - vote.last.at > 1_800L) continue;
                Stable candidate = new Stable(
                        id, vote.last.detection, decision.confidence, vote.last.at);
                if (best == null || candidate.confidence > best.confidence) best = candidate;
            }
            return best;
        }
    }

    private static final class Vote {
        int count;
        float totalConfidence;
        float strongest;
        Sample last;
    }

    private static final class Sample {
        final Detection detection;
        final long at;

        Sample(Detection detection, long at) {
            this.detection = detection;
            this.at = at;
        }
    }

    public static final class Stable {
        public final long trackId;
        public final Detection detection;
        public final float confidence;
        public final long lastSeenAt;

        Stable(long trackId, Detection detection, float confidence, long lastSeenAt) {
            this.trackId = trackId;
            this.detection = detection;
            this.confidence = confidence;
            this.lastSeenAt = lastSeenAt;
        }
    }
}
