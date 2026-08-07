package io.github.valeronm.breadcrumb.location

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.location.LocationManager
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import io.github.valeronm.breadcrumb.util.DebugLog
import io.github.valeronm.breadcrumb.util.backgroundGranted

/**
 * One geofence around the spot the recorder last stopped at, so leaving it is heard even when
 * Activity Recognition has nothing to say — which aboard a train is everything it has to say. The
 * recorder decides *when* to watch ([Effect.ArmDepartureFence] / [Effect.DisarmDepartureFence]);
 * this owns only the registration, and reports nothing about what a departure is worth.
 *
 * Google Play Services evaluates the fence against its own fused position, not our GPS, which is
 * the point: underground there are no fixes to have, and the cell environment still moves. It is a
 * wake, never a data source — nothing here reaches a track.
 *
 * Wraps final platform classes, so it is host-untestable by construction and deliberately holds no
 * decisions.
 */
class DepartureFence(private val context: Context) {

    private val client: GeofencingClient by lazy { LocationServices.getGeofencingClient(context) }

    private val pendingIntent: PendingIntent by lazy {
        GmsCalls.broadcastPendingIntent<GeofenceReceiver>(
            context,
            GeofenceReceiver.ACTION_DEPARTED,
            REQUEST_CODE,
        )
    }

    /**
     * When the live fence was registered, or null if none is. **Not the latency an exit is reported
     * against** — a fence re-armed on a fresher position is still watching the same stop, so that
     * number comes from when watching began (`ActivityIngest.watchStartedAtMs`). This says only
     * whether there is a registration to tear down.
     */
    @Volatile
    var armedAtMs: Long? = null
        private set

    /**
     * Watch [latitude]/[longitude], replacing whatever was being watched — one fence, keyed on a
     * constant id, so re-arming at a new stop cannot leave the old one live.
     */
    @SuppressLint("MissingPermission")
    fun arm(latitude: Double, longitude: Double) {
        // Background location is what makes a fence deliver while the screen is off; without it the
        // request is accepted and then silently starves.
        if (!context.backgroundGranted()) return
        val fence = Geofence.Builder()
            .setRequestId(FENCE_ID)
            .setCircularRegion(latitude, longitude, RADIUS_M)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
            // Left unset, Play Services batches exits to save power and a departure from a phone
            // that has been still a while can take minutes to surface — the one number this whole
            // trigger is judged on. Bought back explicitly, in the open.
            .setNotificationResponsiveness(RESPONSIVENESS_MS)
            .build()
        val request = GeofencingRequest.Builder()
            // No initial trigger: arming happens at the stop, where the phone is inside the fence
            // by construction, and an INITIAL_TRIGGER_EXIT would fire on a fix that merely wandered.
            .setInitialTrigger(0)
            .addGeofence(fence)
            .build()
        GmsCalls.chain { client.addGeofences(request, pendingIntent) }
            .addOnSuccessListener {
                // Stamped where the registration actually lands, so a latency is never reported
                // against a fence Play Services refused.
                armedAtMs = System.currentTimeMillis()
                DebugLog.i(TAG, "departure fence armed (r=${RADIUS_M.toInt()}m, resp=${RESPONSIVENESS_MS / 1000}s)")
            }
            .addOnFailureListener { DebugLog.w(TAG, "departure fence arm failed: ${it.message}") }
    }

    /**
     * Watch wherever the platform last saw this phone, for the case a pause cannot cover: the
     * recorder is armed and idle, so GPS is off and there is no fix of our own to anchor on, and
     * "wait for the first good fix" resolves to never. Arming, a reboot and an app update all land
     * here, and the last two matter because Play Services drops every geofence across them.
     *
     * **The age is logged because it is the whole question.** A fence dropped where the phone used
     * to be is never entered and so never reports leaving, while still occupying the one slot — worse
     * than no fence, and indistinguishable from one in the log unless the staleness is written down.
     * Whether that age needs a ceiling (and a one-shot fix when it is exceeded) is a field question,
     * and this is what answers it.
     */
    @SuppressLint("MissingPermission")
    fun armFromLastKnown() {
        if (!context.backgroundGranted()) return
        val manager = context.getSystemService(LocationManager::class.java) ?: return
        val known = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
        if (known == null) {
            DebugLog.i(TAG, "departure fence: no last-known location to arm from")
            return
        }
        // **Wall clock here, and monotonic in [DepartureProbe]** — the two "Ns old" numbers in this
        // log are measured differently on purpose. A reboot is one of the cases this exists for,
        // and it resets the monotonic clock, so a position stamped before it has an elapsed-realtime
        // that means nothing while its wall time still does. The probe never sees a position older
        // than the request that asked for it, so it has no such case and takes the clock no step can
        // corrupt.
        val ageS = (System.currentTimeMillis() - known.time) / 1000
        DebugLog.i(TAG, "departure fence: arming from last known (${known.provider}, ${ageS}s old)")
        arm(known.latitude, known.longitude)
    }

    fun disarm() {
        // Nothing registered, nothing to tear down — and building the PendingIntent to remove a
        // fence that was never added is an IPC for no reason, on every track that opens.
        if (armedAtMs == null) return
        armedAtMs = null
        GmsCalls.chain { client.removeGeofences(pendingIntent) }
            .addOnSuccessListener { DebugLog.i(TAG, "departure fence disarmed") }
            .addOnFailureListener { DebugLog.w(TAG, "departure fence disarm failed: ${it.message}") }
    }

    private companion object {
        const val TAG = "Breadcrumb"
        const val FENCE_ID = "departure"
        const val REQUEST_CODE = 4001

        /**
         * Android's own documented floor: below roughly this, the error in a Wi-Fi-derived position
         * dominates the radius and exits either fire spuriously or never arrive at all.
         */
        const val RADIUS_M = 100f

        /** How long Play Services may sit on an exit before delivering it. */
        const val RESPONSIVENESS_MS = 30_000
    }
}
