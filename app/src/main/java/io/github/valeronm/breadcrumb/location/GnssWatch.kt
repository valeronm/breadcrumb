package io.github.valeronm.breadcrumb.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.GnssStatusCompat
import androidx.core.location.LocationManagerCompat
import io.github.valeronm.breadcrumb.domain.GnssSnapshot
import io.github.valeronm.breadcrumb.util.isGranted

/**
 * Watches the satellites alongside the location stream, for two things the fix stream alone can't
 * say: whether a reported position is actually backed by a satellite fix (a provider may
 * dead-reckon through a tunnel and report optimistic accuracy that slips the accuracy gate), and
 * the per-point quality metadata each fix is stored with. Registered whenever GPS is on, regardless
 * of the cross-check toggle — that only gates *rejection*.
 *
 * [onTick] fires on every status report, roughly once a second while the engine is searching, which
 * is why the no-fix guard needs no timer of its own and can't be Doze-deferred while GPS is off.
 *
 * **[state] is written on the callback's thread and read on the recorder's**, hence the volatility.
 * It is published as one value rather than a field per reading so a batch can't pick up a satellite
 * count from one report and a fix timestamp from the next.
 */
class GnssWatch(private val context: Context, private val onTick: () -> Unit) {

    @Volatile
    var state: GnssState = EMPTY
        private set

    private val locations by lazy { context.getSystemService(LocationManager::class.java) }
    private var callback: GnssStatusCompat.Callback? = null

    @SuppressLint("MissingPermission")
    fun register() {
        if (callback != null) return
        if (!context.isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) return
        val lm = locations ?: return
        val registered = object : GnssStatusCompat.Callback() {
            // Reused across callbacks — the reduction must stay allocation-free at ~1/s. Safe to
            // share: the main executor delivers status updates one at a time.
            private val snapshot = GnssSnapshot()

            override fun onSatelliteStatusChanged(status: GnssStatusCompat) {
                snapshot.reset()
                for (i in 0 until status.satelliteCount) {
                    snapshot.add(status.usedInFix(i), status.getCn0DbHz(i))
                }
                state = GnssState(
                    satellitesInFix = snapshot.usedInFix,
                    cn0Top4 = snapshot.topCn0Mean(),
                    // Only a report that counts as a real fix moves the clock the backing check
                    // measures against; a searching engine leaves the last real one standing.
                    lastFixElapsedMs = if (snapshot.usedInFix >= MIN_SATELLITES_IN_FIX) {
                        SystemClock.elapsedRealtime()
                    } else {
                        state.lastFixElapsedMs
                    },
                )
                onTick()
            }
        }
        callback = registered
        LocationManagerCompat.registerGnssStatusCallback(lm, ContextCompat.getMainExecutor(context), registered)
    }

    fun unregister() {
        val cb = callback ?: return
        locations?.let { LocationManagerCompat.unregisterGnssStatusCallback(it, cb) }
        callback = null
        // Don't carry stale satellite metadata into the next track's first fixes. The fix clock
        // survives: it is monotonic evidence about the sky, which turning the receiver off doesn't
        // refute, and a fresh reading is stale on its own terms within seconds.
        state = state.copy(satellitesInFix = null, cn0Top4 = null)
    }

    private companion object {
        val EMPTY = GnssState(satellitesInFix = null, cn0Top4 = null, lastFixElapsedMs = 0L)

        // How many satellites make a status report count as a real fix at all — four is the minimum
        // for a genuine 3D one; below that the position isn't independently satellite-determined.
        // How *stale* such a fix may be and still back a location is the rule's own, and lives with
        // it in [FixIngest.GNSS_FIX_MAX_AGE_MS].
        const val MIN_SATELLITES_IN_FIX = 4
    }
}
