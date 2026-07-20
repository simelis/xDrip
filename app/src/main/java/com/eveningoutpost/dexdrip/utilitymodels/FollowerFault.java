package com.eveningoutpost.dexdrip.utilitymodels;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import com.eveningoutpost.dexdrip.GcmListenerSvc;
import com.eveningoutpost.dexdrip.Home;
import com.eveningoutpost.dexdrip.R;
import com.eveningoutpost.dexdrip.g5model.CalibrationState;
import com.eveningoutpost.dexdrip.models.BgReading;
import com.eveningoutpost.dexdrip.models.JoH;
import com.eveningoutpost.dexdrip.models.UserError;
import com.eveningoutpost.dexdrip.xdrip;

import static com.eveningoutpost.dexdrip.models.JoH.msSince;
import static com.eveningoutpost.dexdrip.utilitymodels.Constants.MINUTE_IN_MS;

/**
 * Classifies the most likely fault source when a follower stops receiving readings:
 * a sensor fault, sensor to master connectivity, master to follower connectivity,
 * or this device being offline.
 *
 * Uses the signals every master already emits (nscu collector status and general
 * sync traffic), so classification is independent of the master's app version.
 */
public class FollowerFault {

    private static final String TAG = "FollowerFault";

    // staleness is derived from the observed reading cadence, see FollowerCadence
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

    private static volatile Lane lastLoggedLane = Lane.NONE;

    public static Verdict classify() {
        final Verdict v = classifyInternal();
        if (v.lane != lastLoggedLane) {
            lastLoggedLane = v.lane;
            UserError.Log.uel(TAG, "Fault classification changed: " + v.lane
                    + (v.message.length() > 0 ? " - " + v.message : ""));
        }
        return v;
    }

    private static Verdict classifyInternal() {
        if (!Home.get_follower()) return new Verdict(Lane.NONE, "");
        final long dataAge = BgReading.getTimeSinceLastReading();
        if (dataAge < FollowerCadence.staleMs()) return new Verdict(Lane.NONE, "");

        if (!anyNetworkConnected()) {
            return new Verdict(Lane.FOLLOWER_OFFLINE, xdrip.gs(R.string.follower_fault_device_offline));
        }

        final long statusAge = NanoStatus.getRemoteAgeMs("");
        final String remote = NanoStatus.getRemote("").toString();
        if (statusAge > -1 && statusAge < FRESH_STATUS_MS && remote.length() > 0) {
            if (matchesSensorState(remote)) {
                // no "master reports" prefix: the same string is also shown verbatim when
                // readings are still arriving, and on a follower a sensor state can only
                // have come from the master
                return new Verdict(Lane.SENSOR_FAULT,
                        xdrip.gs(R.string.follower_fault_sensor_state, remote, JoH.niceTimeScalar(statusAge)));
            }
            if (remote.startsWith(SEARCHING_FOR_PREFIX)) {
                return new Verdict(Lane.SENSOR_MASTER_LINK,
                        xdrip.gs(R.string.follower_fault_master_cannot_reach_sensor, remote, JoH.niceTimeScalar(statusAge)));
            }
        }

        final long lastRx = GcmListenerSvc.lastMessageReceived;
        final long linkAge = lastRx > 0 ? msSince(lastRx) : -1;
        if (linkAge > -1 && linkAge < FRESH_LINK_MS) {
            return new Verdict(Lane.DATA_GAP, xdrip.gs(R.string.follower_fault_no_glucose_data));
        }

        return new Verdict(Lane.MASTER_FOLLOWER_LINK, xdrip.gs(R.string.follower_fault_nothing_from_master,
                linkAge > -1 ? JoH.niceTimeScalar(linkAge) : xdrip.gs(R.string.follower_fault_a_long_time)));
    }

    // Does the master's collector status text indicate a sensor-side problem?
    //
    // Best effort only: the master sends a preformatted status string rather than a
    // structured state, so this recognises the states of the native Dexcom collector
    // (CalibrationState plus the transitional strings it emits). A master collecting
    // from another sensor family simply does not match here and its follower falls back
    // to the generic DATA_GAP lane, which is still correct, just less specific. These
    // literals track untranslated strings in the collector; should those ever become
    // localised, matching degrades to the generic lane rather than misreporting.
    private static final String SEARCHING_FOR_PREFIX = "Searching for";

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
