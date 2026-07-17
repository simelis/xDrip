package com.eveningoutpost.dexdrip.utilitymodels;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import com.eveningoutpost.dexdrip.GcmListenerSvc;
import com.eveningoutpost.dexdrip.Home;
import com.eveningoutpost.dexdrip.g5model.CalibrationState;
import com.eveningoutpost.dexdrip.models.BgReading;
import com.eveningoutpost.dexdrip.models.JoH;
import com.eveningoutpost.dexdrip.xdrip;

import static com.eveningoutpost.dexdrip.models.JoH.msSince;
import static com.eveningoutpost.dexdrip.utilitymodels.Constants.MINUTE_IN_MS;

/**
 * Classifies the most likely fault source when a follower stops receiving readings:
 * a sensor fault, sensor to master connectivity, master to follower connectivity,
 * or this device being offline.
 *
 * Uses only signals a stock master already emits (nscu collector status and general
 * sync traffic) so it requires no changes on the master device.
 */
public class FollowerFault {

    private static final long STALE_MS = MINUTE_IN_MS * 11;        // more than 2 missed readings
    private static final long FRESH_LINK_MS = MINUTE_IN_MS * 20;   // sync channel considered alive
    private static final long FRESH_STATUS_MS = MINUTE_IN_MS * 25; // remote status considered current

    public enum Lane {
        NONE,
        SENSOR_FAULT,
        SENSOR_MASTER_LINK,
        MASTER_FOLLOWER_LINK,
        FOLLOWER_OFFLINE,
        DATA_GAP
    }

    public static class Verdict {
        public final Lane lane;
        public final String message;

        Verdict(final Lane lane, final String message) {
            this.lane = lane;
            this.message = message;
        }
    }

    public static Verdict classify() {
        if (!Home.get_follower()) return new Verdict(Lane.NONE, "");
        final long dataAge = BgReading.getTimeSinceLastReading();
        if (dataAge < STALE_MS) return new Verdict(Lane.NONE, "");

        if (!anyNetworkConnected()) {
            return new Verdict(Lane.FOLLOWER_OFFLINE, "This device is offline");
        }

        final long statusAge = NanoStatus.getRemoteAgeMs("");
        final String remote = NanoStatus.getRemote("").toString();
        if (statusAge > -1 && statusAge < FRESH_STATUS_MS && remote.length() > 0) {
            if (matchesSensorState(remote)) {
                return new Verdict(Lane.SENSOR_FAULT, "Master reports: " + remote + " (" + JoH.niceTimeScalar(statusAge) + " ago)");
            }
            if (remote.startsWith("Searching for")) {
                return new Verdict(Lane.SENSOR_MASTER_LINK, "Master cannot reach sensor: " + remote + " (" + JoH.niceTimeScalar(statusAge) + " ago)");
            }
        }

        final long lastRx = GcmListenerSvc.lastMessageReceived;
        final long linkAge = lastRx > 0 ? msSince(lastRx) : -1;
        if (linkAge > -1 && linkAge < FRESH_LINK_MS) {
            return new Verdict(Lane.DATA_GAP, "Master reachable but no glucose data - backfill requested");
        }

        return new Verdict(Lane.MASTER_FOLLOWER_LINK, "Nothing from master for "
                + (linkAge > -1 ? JoH.niceTimeScalar(linkAge) : "a long time"));
    }

    // does the master's collector status text indicate a sensor-side problem?
    private static boolean matchesSensorState(final String remote) {
        if (remote.startsWith("Starting Sensor") || remote.startsWith("Stopping Sensor") || remote.startsWith("Sending calibration")) {
            return true;
        }
        for (final CalibrationState state : CalibrationState.values()) {
            if (state == CalibrationState.Ok || state == CalibrationState.Unknown) continue;
            if (remote.contains(state.getText())) return true;
        }
        return false;
    }

    private static boolean anyNetworkConnected() {
        try {
            final ConnectivityManager cm = (ConnectivityManager) xdrip.getAppContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            final NetworkInfo active = cm.getActiveNetworkInfo();
            return active != null && active.isConnected();
        } catch (Exception e) {
            return true; // fail open - never misreport offline
        }
    }
}
