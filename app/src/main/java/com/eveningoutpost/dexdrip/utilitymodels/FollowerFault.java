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

        // the master specifically, not any device on the channel: with a second follower
        // present, general sync traffic keeps flowing while the master is off
        final long lastRx = GcmListenerSvc.lastMasterMessageReceived;
        final long linkAge = lastRx > 0 ? msSince(lastRx) : -1;
        final boolean linkAlive = linkAge > -1 && linkAge < FRESH_LINK_MS;

        final long statusAge = NanoStatus.getRemoteAgeMs("");
        final String remote = NanoStatus.getRemote("").toString();
        // The master only resends its status when the text changes, so age alone does not
        // make it stale: trust it while the sync channel is alive, and for a fixed window
        // once it is not.
        if (statusAge > -1 && (statusAge < FRESH_STATUS_MS || linkAlive) && remote.length() > 0) {
            // classify from the state token when the master sends one, else match the text
            final String state = freshCollectorState(linkAlive);
            if (NanoStatus.STATE_SENSOR_PROBLEM.equals(state)) {
                return new Verdict(Lane.SENSOR_FAULT,
                        xdrip.gs(R.string.follower_fault_sensor_state, remote, JoH.niceTimeScalar(statusAge)));
            } else if (NanoStatus.STATE_SENSOR_LINK.equals(state)) {
                return new Verdict(Lane.SENSOR_MASTER_LINK,
                        xdrip.gs(R.string.follower_fault_master_cannot_reach_sensor, remote, JoH.niceTimeScalar(statusAge)));
            } else if (NanoStatus.STATE_OK.equals(state)) {
                // sensor side is fine - fall through to the link lanes
            } else {
                if (matchesSensorState(remote)) {
                    return new Verdict(Lane.SENSOR_FAULT,
                            xdrip.gs(R.string.follower_fault_sensor_state, remote, JoH.niceTimeScalar(statusAge)));
                }
                if (remote.startsWith(SEARCHING_FOR_PREFIX)) {
                    return new Verdict(Lane.SENSOR_MASTER_LINK,
                            xdrip.gs(R.string.follower_fault_master_cannot_reach_sensor, remote, JoH.niceTimeScalar(statusAge)));
                }
            }
        }

        if (linkAlive) {
            return new Verdict(Lane.DATA_GAP, xdrip.gs(R.string.follower_fault_no_glucose_data));
        }

        return new Verdict(Lane.MASTER_FOLLOWER_LINK, xdrip.gs(R.string.follower_fault_nothing_from_master,
                linkAge > -1 ? JoH.niceTimeScalar(linkAge) : xdrip.gs(R.string.follower_fault_a_long_time)));
    }

    // Current readings prove the master reached the sensor, so a stored status saying it
    // cannot is an update which never arrived and should not be displayed.
    public static boolean searchStatusObsolete() {
        if (BgReading.getTimeSinceLastReading() >= FollowerCadence.staleMs()) return false;
        return NanoStatus.STATE_SENSOR_LINK.equals(
                NanoStatus.getRemote(NanoStatus.COLLECTOR_STATE_PREFIX).toString())
                || NanoStatus.getRemote("").toString().startsWith(SEARCHING_FOR_PREFIX);
    }

    // Collector state token from a master which sends one, null otherwise. Trusted on the
    // same terms as the display string above.
    private static String freshCollectorState(final boolean linkAlive) {
        final long age = NanoStatus.getRemoteAgeMs(NanoStatus.COLLECTOR_STATE_PREFIX);
        if (age < 0 || (age > FRESH_STATUS_MS && !linkAlive)) return null;
        final String state = NanoStatus.getRemote(NanoStatus.COLLECTOR_STATE_PREFIX).toString();
        return state.length() > 0 ? state : null;
    }

    // Does the master's collector status text indicate a sensor-side problem? Best effort
    // fallback for masters which send no state token: matches the native Dexcom collector's
    // status strings, and yields the generic lane for anything it does not recognise.
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
