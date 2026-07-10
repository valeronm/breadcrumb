package io.github.valeronm.breadcrumb.data

import android.content.Context
import io.github.valeronm.breadcrumb.data.db.AppDatabase
import io.github.valeronm.breadcrumb.data.db.LivenessEvent
import io.github.valeronm.breadcrumb.util.DebugLog
import kotlinx.coroutines.flow.Flow

private const val TAG = "Breadcrumb"

/**
 * Records recorder-lifecycle evidence for stay derivation. ARMED/DISARMED rows are written on
 * explicit toggles; an OUTAGE row is materialized at restart when the heartbeat (in [Settings])
 * turns out to have gone stale while armed — i.e. the app died rather than being turned off.
 */
class LivenessRepository(context: Context) {

    private val dao = AppDatabase.get(context).livenessDao()

    fun observeEvents(): Flow<List<LivenessEvent>> = dao.observeAll()

    suspend fun recordArmed(now: Long) {
        dao.insert(LivenessEvent(type = LivenessEvent.TYPE_ARMED, at = now))
    }

    suspend fun recordDisarmed(now: Long) {
        dao.insert(LivenessEvent(type = LivenessEvent.TYPE_DISARMED, at = now))
    }

    /**
     * At service start: if the last heartbeat is stale, the app was dead (or the phone off) from
     * [lastHeartbeat] to [now] — record that as an OUTAGE so the deriver doesn't read the silence
     * as a stay. Only applies while the last recorded state was ARMED: an arm after a deliberate
     * disarm must not fabricate an outage over the disarmed period.
     */
    suspend fun materializeOutageIfDead(lastHeartbeat: Long, now: Long, toleranceMs: Long) {
        if (lastHeartbeat <= 0 || now - lastHeartbeat <= toleranceMs) return
        if (dao.lastEvent()?.type != LivenessEvent.TYPE_ARMED) return
        DebugLog.i(TAG, "liveness: outage ${(now - lastHeartbeat) / 1000}s (heartbeat stale)")
        dao.insert(LivenessEvent(type = LivenessEvent.TYPE_OUTAGE, at = lastHeartbeat, until = now))
    }
}
