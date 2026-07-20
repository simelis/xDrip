package com.eveningoutpost.dexdrip.utilitymodels;

import com.eveningoutpost.dexdrip.models.BgReading;
import com.eveningoutpost.dexdrip.models.JoH;
import com.eveningoutpost.dexdrip.models.UserError;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.eveningoutpost.dexdrip.utilitymodels.Constants.HOUR_IN_MS;
import static com.eveningoutpost.dexdrip.utilitymodels.Constants.MINUTE_IN_MS;

/**
 * Helps a follower fill interior gaps in received readings.
 *
 * The timestamp in a bfr request tells the master how far the follower's data reaches, and
 * the master answers with its backfill blob whenever it holds anything newer. With an
 * interior gap the report is the last reading before that gap, since local data is only
 * contiguous up to there. The blob covers the master's whole 24 hour window rather than
 * just the reported gap, so a single answered request closes every gap within it.
 */
public class FollowerBackfill {

    private static final String TAG = "FollowerBackfill";
    // gap size threshold is derived from the observed reading cadence, see FollowerCadence
    private static final long WINDOW_MS = HOUR_IN_MS * 24;      // matches master blob coverage
    private static final long LEADING_WINDOW_MS = HOUR_IN_MS * 20; // history shallower than this may exist on master
    private static final long STABLE_MS = MINUTE_IN_MS * 3;     // a replay moves gap boundaries well within this
    private static final int MAX_READINGS = 300;                // matches master blob limit
    private static final int REQUEST_INTERVAL_SECONDS = 900;    // the master limits how often it will answer

    // gap observed on the previous evaluation, for the is-it-shrinking check
    private static volatile String lastGapSignature = null;
    private static volatile long lastGapSignatureAt = 0;

    // requests for the same unchanged gap before giving up on it
    private static final int MAX_ATTEMPTS_PER_GAP = 2;
    private static volatile String lastRequestedSignature = null;
    private static volatile int requestAttempts = 0;

    // gaps the master will not fill, skipped when choosing which gap to ask about;
    // persisted so a restart does not walk through declining them all again
    private static final String DECLINED_STORE = "follower-backfill-declined";
    private static final int MAX_DECLINED = 20;
    private static final Set<String> declined = new LinkedHashSet<>();
    private static volatile boolean declinedLoaded = false;

    private static void loadDeclined() {
        if (declinedLoaded) return;
        synchronized (declined) {
            if (declinedLoaded) return;
            for (final String entry : PersistentStore.getString(DECLINED_STORE).split(",")) {
                if (entry.length() > 0) declined.add(entry);
            }
            declinedLoaded = true;
        }
    }

    private static String signature(final long older, final long newer) {
        return older + ":" + newer;
    }

    // "older:newer" or "older^newer" as times and duration, for logging
    public static String describe(final String range) {
        if (range == null) return "unknown";
        final String[] ends = range.split("[:^]");
        if (ends.length != 2) return range;
        final long older = JoH.tolerantParseLong(ends[0], 0);
        final long newer = JoH.tolerantParseLong(ends[1], 0);
        if (older <= 0 || newer <= older) return range;
        return JoH.dateTimeText(older) + " to " + JoH.hourMinuteString(newer)
                + " (" + JoH.niceTimeScalar(newer - older) + ")";
    }

    private static void decline(final String signature, final String why) {
        loadDeclined();
        synchronized (declined) {
            if (declined.add(signature)) {
                final Iterator<String> oldest = declined.iterator();
                while (declined.size() > MAX_DECLINED && oldest.hasNext()) {
                    oldest.next();
                    oldest.remove();
                }
                PersistentStore.setString(DECLINED_STORE, String.join(",", declined));
                UserError.Log.uel(TAG, "Not asking again about gap " + describe(signature) + ": " + why);
            }
        }
    }

    // Newest interior gap which has not been declined, as "older:newer", or null for none.
    // The newest is the one most likely to still be held by the master, and the answer
    // covers the whole window, so asking about it fills the older gaps as well.
    private static String openGap(final List<BgReading> readings) {
        loadDeclined();
        for (int i = 0; i < readings.size() - 1; i++) {
            final long newer = readings.get(i).timestamp; // list is newest first
            final long older = readings.get(i + 1).timestamp;
            if (newer - older > FollowerCadence.gapMs()) {
                final String signature = signature(older, newer);
                synchronized (declined) {
                    if (!declined.contains(signature)) return signature;
                }
            }
        }
        return null;
    }

    // Payload for a bfr request: the newest local reading, or "older^newer" for an interior
    // gap so a master which understands ranges can say whether it can fill it. A shallow
    // history reports from just before its oldest reading, and no local data reports empty.
    public static String requestPayload() {
        final List<BgReading> readings = BgReading.latestForGraph(MAX_READINGS, JoH.tsl() - WINDOW_MS);
        if (readings == null || readings.isEmpty()) return "";
        final String gap = openGap(readings);
        if (gap != null) return gap.replace(':', '^');
        final long oldest = readings.get(readings.size() - 1).timestamp;
        if (JoH.msSince(oldest) < LEADING_WINDOW_MS) {
            return Long.toString(oldest - 1); // we may be missing history from before our oldest reading
        }
        return Long.toString(readings.get(0).timestamp);
    }

    // Master replied that it holds nothing inside the reported gap ("bfe", echoing the
    // requested range as "older^newer").
    public static void markGapUnfillable(final String range) {
        if (range == null) return;
        decline(range.replace('^', ':'), "master holds nothing inside it");
    }

    // Is there a gap a backfill request could fill: an interior gap, a shallow history, or
    // no data at all? An interior gap counts only once it has stayed unchanged for
    // STABLE_MS while live data keeps arriving, which means no replay is coming for it.
    // Rate limited so an unfillable gap does not cause request spam.
    public static boolean gapRequestDue() {
        final List<BgReading> readings = BgReading.latestForGraph(MAX_READINGS, JoH.tsl() - WINDOW_MS);
        String reason = null;
        String gap = null;
        if (readings == null || readings.isEmpty()) {
            reason = "no local data";
        } else {
            gap = openGap(readings);
            if (gap != null) {
                if (!gap.equals(lastGapSignature)) {
                    // new or still-shrinking gap - start / restart observing it
                    lastGapSignature = gap;
                    lastGapSignatureAt = JoH.tsl();
                } else if (JoH.msSince(lastGapSignatureAt) > STABLE_MS) {
                    reason = "interior gap unchanged for " + JoH.niceTimeScalar(JoH.msSince(lastGapSignatureAt));
                }
            } else {
                lastGapSignature = null;
                final long oldest = readings.get(readings.size() - 1).timestamp;
                if (JoH.msSince(oldest) < LEADING_WINDOW_MS) {
                    reason = "local history only reaches back to " + JoH.dateTimeText(oldest);
                }
            }
        }
        if (reason != null && JoH.pratelimit("follower-gap-backfill", REQUEST_INTERVAL_SECONDS)) {
            if (gap != null) {
                if (gap.equals(lastRequestedSignature)) {
                    if (++requestAttempts >= MAX_ATTEMPTS_PER_GAP) {
                        decline(gap, "unanswered after " + MAX_ATTEMPTS_PER_GAP + " requests");
                    }
                } else {
                    lastRequestedSignature = gap;
                    requestAttempts = 1;
                }
            }
            UserError.Log.uel(TAG, "Backfill due: " + reason);
            return true;
        }
        return false;
    }
}
