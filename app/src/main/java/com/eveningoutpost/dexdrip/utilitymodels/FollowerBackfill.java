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
    private static final long GAP_MS = MINUTE_IN_MS * 11;       // more than 2 missed readings
    private static final long WINDOW_MS = HOUR_IN_MS * 24;      // matches master blob coverage
    private static final long LEADING_WINDOW_MS = HOUR_IN_MS * 20; // history shallower than this may exist on master
    private static final long GRACE_MS = MINUTE_IN_MS * 15;     // let the master's own push backfill heal fresh gaps first
    private static final int MAX_READINGS = 300;                // matches master blob limit

    // Timestamp to report in a bfr request: the reading just before the oldest interior
    // gap within the window if one exists, else just before our oldest reading when local
    // history is shallow (fresh install without restore), else the newest reading.
    // 0 (send everything) when there is no local data at all.
    public static long effectiveRequestTimestamp() {
        final List<BgReading> readings = BgReading.latestForGraph(MAX_READINGS, JoH.tsl() - WINDOW_MS);
        if (readings == null || readings.isEmpty()) return 0;
        final long newest = readings.get(0).timestamp; // list is newest first
        long result = newest;
        for (int i = 0; i < readings.size() - 1; i++) {
            final long newer = readings.get(i).timestamp;
            final long older = readings.get(i + 1).timestamp;
            if (newer - older > GAP_MS) {
                result = older; // report from before the gap - oldest gap wins
            }
        }
        if (result == newest) {
            final long oldest = readings.get(readings.size() - 1).timestamp;
            if (JoH.msSince(oldest) < LEADING_WINDOW_MS) {
                result = oldest - 1; // we may be missing history from before our oldest reading
            }
        }
        return result;
    }

    // Is there a gap in local data that a backfill request could fill: an interior gap,
    // a shallow history (fresh install without database restore), or no data at all?
    // Rate limited so unfillable gaps (sensor stopped, warmup) don't cause request spam.
    public static boolean gapRequestDue() {
        final List<BgReading> readings = BgReading.latestForGraph(MAX_READINGS, JoH.tsl() - WINDOW_MS);
        String reason = null;
        if (readings == null || readings.isEmpty()) {
            reason = "no local data";
        } else {
            for (int i = 0; i < readings.size() - 1; i++) {
                if (readings.get(i).timestamp - readings.get(i + 1).timestamp > GAP_MS) {
                    // when a master recovers from a collection outage it backfills followers
                    // by pushing the recovered readings itself - grace time avoids requesting
                    // a redundant blob while that native healing may still be in progress
                    if (JoH.msSince(readings.get(i).timestamp) > GRACE_MS) {
                        reason = "interior gap before " + JoH.dateTimeText(readings.get(i).timestamp);
                    }
                    break; // most recent gap decides; if it is fresh, wait for push healing
                }
            }
            if (reason == null) {
                final long oldest = readings.get(readings.size() - 1).timestamp;
                if (JoH.msSince(oldest) < LEADING_WINDOW_MS) {
                    reason = "local history only reaches back to " + JoH.dateTimeText(oldest);
                }
            }
        }
        if (reason != null && JoH.pratelimit("follower-gap-backfill", 5400)) {
            UserError.Log.uel(TAG, "Backfill due: " + reason);
            return true;
        }
        return false;
    }
}
