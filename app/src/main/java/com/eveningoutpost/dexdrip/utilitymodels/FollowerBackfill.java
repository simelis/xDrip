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
 * The timestamp in a bfr request tells the master how far the follower's data reaches;
 * the master answers with its standard backfill blob (up to 300 readings covering the
 * last 24 hours) whenever it holds anything newer. With an interior gap the truthful
 * report is the last reading before that gap - local data is only contiguous up to
 * there - and the blob then covers the hole. Only the existing request semantics are
 * used, so this works with masters of any app version.
 */
public class FollowerBackfill {

    private static final String TAG = "FollowerBackfill";
    // gap size threshold is derived from the observed reading cadence, see FollowerCadence
    private static final long WINDOW_MS = HOUR_IN_MS * 24;      // matches master blob coverage
    private static final long LEADING_WINDOW_MS = HOUR_IN_MS * 20; // history shallower than this may exist on master
    private static final long STABLE_MS = MINUTE_IN_MS * 3;     // an active queue replay moves gap boundaries well within this
    private static final int MAX_READINGS = 300;                // matches master blob limit

    // gap observed on the previous evaluation, for the is-it-shrinking check
    private static volatile String lastGapSignature = null;
    private static volatile long lastGapSignatureAt = 0;

    // give up on a gap the master demonstrably cannot fill (e.g. sensor errors mean the
    // master has the identical hole): after this many requests with the gap unchanged,
    // stay silent until the gap signature changes
    private static final int MAX_ATTEMPTS_PER_GAP = 2;
    private static volatile String lastRequestedSignature = null;
    private static volatile int requestAttempts = 0;

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
            if (newer - older > FollowerCadence.gapMs()) {
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
    //
    // Interior gaps are only requested once they are observed UNCHANGED for STABLE_MS
    // while live data keeps arriving: an active replay from the master's resend queue
    // moves the gap boundaries within seconds, so a static gap is direct evidence that
    // no native recovery is coming (queue expired, master restarted, or the item was
    // dequeued by another follower's ack). This avoids both racing the native replay
    // and waiting a fixed grace time for data that was never going to arrive.
    // Rate limited so unfillable gaps (sensor stopped, warmup) don't cause request spam.
    public static boolean gapRequestDue() {
        final List<BgReading> readings = BgReading.latestForGraph(MAX_READINGS, JoH.tsl() - WINDOW_MS);
        String reason = null;
        if (readings == null || readings.isEmpty()) {
            reason = "no local data";
        } else {
            String gapSignature = null;
            for (int i = 0; i < readings.size() - 1; i++) {
                final long newer = readings.get(i).timestamp;
                final long older = readings.get(i + 1).timestamp;
                if (newer - older > FollowerCadence.gapMs()) {
                    gapSignature = older + ":" + newer; // oldest gap wins, matching effectiveRequestTimestamp
                }
            }
            if (gapSignature != null) {
                if (!gapSignature.equals(lastGapSignature)) {
                    // new or still-shrinking gap - start / restart observing it
                    lastGapSignature = gapSignature;
                    lastGapSignatureAt = JoH.tsl();
                } else if (JoH.msSince(lastGapSignatureAt) > STABLE_MS) {
                    if (gapSignature.equals(lastRequestedSignature) && requestAttempts >= MAX_ATTEMPTS_PER_GAP) {
                        if (requestAttempts == MAX_ATTEMPTS_PER_GAP) {
                            requestAttempts++; // log the give-up exactly once
                            UserError.Log.uel(TAG, "Giving up on gap (" + gapSignature + ") after "
                                    + MAX_ATTEMPTS_PER_GAP + " unanswered backfills - master appears to have the same hole");
                        }
                    } else {
                        reason = "interior gap unchanged for " + JoH.niceTimeScalar(JoH.msSince(lastGapSignatureAt));
                    }
                }
            } else {
                lastGapSignature = null;
                final long oldest = readings.get(readings.size() - 1).timestamp;
                if (JoH.msSince(oldest) < LEADING_WINDOW_MS) {
                    reason = "local history only reaches back to " + JoH.dateTimeText(oldest);
                }
            }
        }
        if (reason != null && JoH.pratelimit("follower-gap-backfill", 5400)) {
            if (lastGapSignature != null && reason.startsWith("interior gap")) {
                if (lastGapSignature.equals(lastRequestedSignature)) {
                    requestAttempts++;
                } else {
                    lastRequestedSignature = lastGapSignature;
                    requestAttempts = 1;
                }
            }
            UserError.Log.uel(TAG, "Backfill due: " + reason);
            return true;
        }
        return false;
    }
}
