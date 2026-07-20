package com.eveningoutpost.dexdrip.utilitymodels;

import com.eveningoutpost.dexdrip.models.BgReading;
import com.eveningoutpost.dexdrip.models.JoH;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.eveningoutpost.dexdrip.utilitymodels.Constants.HOUR_IN_MS;
import static com.eveningoutpost.dexdrip.utilitymodels.Constants.MINUTE_IN_MS;

/**
 * Observed reading cadence, used to derive follower staleness and gap thresholds.
 *
 * A follower cannot know which sensor the master collects from, so these are derived from
 * the interval actually observed between received readings rather than assumed.
 */
public class FollowerCadence {

    private static final long DEFAULT_INTERVAL_MS = MINUTE_IN_MS * 5; // used until enough data is seen
    private static final long MIN_INTERVAL_MS = MINUTE_IN_MS;         // fastest cadence xDrip supports
    private static final long MAX_INTERVAL_MS = MINUTE_IN_MS * 15;
    private static final long RECOMPUTE_MS = MINUTE_IN_MS * 5;        // cadence changes rarely
    private static final int SAMPLES = 9;

    private static volatile long cachedInterval = DEFAULT_INTERVAL_MS;
    private static volatile long cachedAt = 0;

    // Median interval between recent readings - the median so gaps do not inflate it.
    public static long intervalMs() {
        if (cachedAt > 0 && JoH.msSince(cachedAt) < RECOMPUTE_MS) return cachedInterval;
        long result = DEFAULT_INTERVAL_MS;
        final List<BgReading> readings = BgReading.latestForGraph(SAMPLES, JoH.tsl() - HOUR_IN_MS * 6);
        if (readings != null && readings.size() >= 3) {
            final List<Long> deltas = new ArrayList<>();
            for (int i = 0; i < readings.size() - 1; i++) {
                final long delta = readings.get(i).timestamp - readings.get(i + 1).timestamp;
                if (delta > 0) deltas.add(delta);
            }
            if (!deltas.isEmpty()) {
                Collections.sort(deltas);
                result = deltas.get(deltas.size() / 2);
            }
        }
        cachedInterval = Math.max(MIN_INTERVAL_MS, Math.min(MAX_INTERVAL_MS, result));
        cachedAt = JoH.tsl();
        return cachedInterval;
    }

    private static long slack(final long interval) {
        return Math.max(MINUTE_IN_MS, interval / 5);
    }

    // Data is stale once a reading is clearly missed. Floored to allow for jitter.
    public static long staleMs() {
        final long interval = intervalMs();
        return Math.max(MINUTE_IN_MS * 3, interval + slack(interval));
    }

    // An interior hole worth backfilling: at least one missed reading.
    public static long gapMs() {
        final long interval = intervalMs();
        return interval + slack(interval);
    }
}
