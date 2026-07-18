package com.eveningoutpost.dexdrip.utilitymodels;

import com.eveningoutpost.dexdrip.models.BgReading;
import com.eveningoutpost.dexdrip.models.JoH;
import com.eveningoutpost.dexdrip.models.UserError;

import java.util.List;

import static com.eveningoutpost.dexdrip.utilitymodels.Constants.HOUR_IN_MS;
import static com.eveningoutpost.dexdrip.utilitymodels.Constants.MINUTE_IN_MS;

/**
 * Helps a follower fill interior gaps in received readings.
 *
 * The master's backfill response (bfr -> syncBGTable2) always contains up to 300
 * readings covering the last 24 hours, but is only sent when the master's newest
 * reading is newer than the timestamp the follower reports. Reporting the timestamp
 * of the reading just before an interior gap opens that gate and the standard blob
 * fills the hole - requiring no change on the master.
 */
public class FollowerBackfill {

    private static final String TAG = "FollowerBackfill";
    private static final long GAP_MS = MINUTE_IN_MS * 11;   // more than 2 missed readings
    private static final long WINDOW_MS = HOUR_IN_MS * 24;  // matches master blob coverage
    private static final int MAX_READINGS = 300;            // matches master blob limit

    // Timestamp to report in a bfr request: the reading just before the oldest interior
    // gap within the window if one exists, otherwise the newest reading, 0 if no data.
    public static long effectiveRequestTimestamp() {
        final List<BgReading> readings = BgReading.latestForGraph(MAX_READINGS, JoH.tsl() - WINDOW_MS);
        if (readings == null || readings.isEmpty()) return 0;
        long result = readings.get(0).timestamp; // list is newest first
        for (int i = 0; i < readings.size() - 1; i++) {
            final long newer = readings.get(i).timestamp;
            final long older = readings.get(i + 1).timestamp;
            if (newer - older > GAP_MS) {
                result = older; // report from before the gap - oldest gap wins
            }
        }
        return result;
    }

    // Is there an interior gap in the recent window that a backfill request could fill?
    // Rate limited so unfillable gaps (sensor stopped, warmup) don't cause request spam.
    public static boolean gapRequestDue() {
        final List<BgReading> readings = BgReading.latestForGraph(MAX_READINGS, JoH.tsl() - WINDOW_MS);
        if (readings == null || readings.size() < 2) return false;
        for (int i = 0; i < readings.size() - 1; i++) {
            if (readings.get(i).timestamp - readings.get(i + 1).timestamp > GAP_MS) {
                if (JoH.pratelimit("follower-gap-backfill", 5400)) {
                    UserError.Log.uel(TAG, "Interior reading gap detected before "
                            + JoH.dateTimeText(readings.get(i).timestamp) + " - requesting backfill");
                    return true;
                }
                return false;
            }
        }
        return false;
    }
}
