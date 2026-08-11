package io.github.valeronm.breadcrumb.data

import android.content.Context
import androidx.room.withTransaction
import io.github.valeronm.breadcrumb.data.db.AppDatabase
import io.github.valeronm.breadcrumb.data.db.LivenessEvent
import io.github.valeronm.breadcrumb.util.DebugLog
import kotlinx.coroutines.flow.Flow

private const val TAG = "Breadcrumb"

/**
 * Records recorder-lifecycle evidence for stay derivation. ARMED/DISARMED rows are written on
 * explicit toggles; an OUTAGE row is materialized at restart when the heartbeat (in [Settings])
 * turns out to have gone stale while armed — i.e. the app died rather than being turned off.
 *
 * **Two outages never overlap**, and a reader is entitled to that: [materializeOutageIfDead] writes
 * one only on finding the log ends ARMED, so an earlier outage has been closed and ended before this
 * one began. It is what lets a reader asking about one stretch of time fetch the *last* outage before
 * it rather than every outage that might still be open (`LivenessDao.eventsAround`), which is the
 * difference between a seek and a walk of the whole log. [restoreEvents] is the one writer that does
 * not establish it — it inserts what a backup file holds — and is where it would have to be upheld if
 * a file could ever carry overlapping outages.
 */
class LivenessRepository(context: Context, private val db: AppDatabase = AppDatabase.get(context)) {

    private val dao = db.livenessDao()
    private val derivation = DerivationStore(context, db)

    fun observeEvents(): Flow<List<LivenessEvent>> = dao.observeAll()

    suspend fun allEvents(): List<LivenessEvent> = dao.allEvents()

    /** Backup restore: re-insert exported events under fresh ids (ordering is by time, not id). */
    suspend fun restoreEvents(events: List<LivenessEvent>) = dao.insertAll(events.map { it.copy(id = 0) })

    suspend fun recordArmed(now: Long) {
        dao.insert(LivenessEvent(type = LivenessEvent.TYPE_ARMED, at = now))
    }

    suspend fun recordDisarmed(now: Long) {
        dao.insert(LivenessEvent(type = LivenessEvent.TYPE_DISARMED, at = now))
    }

    /**
     * At service start: a stale heartbeat means the app was dead (or phone off) from [lastHeartbeat]
     * to [now] — recorded as an OUTAGE so the deriver doesn't read the silence as a stay. Only while
     * the last recorded state was ARMED: an arm after a deliberate disarm must not fabricate an
     * outage over the disarmed period.
     *
     * **The one liveness write that reaches back over stays already derived.** Every other kind
     * arrives at the moment it describes, ahead of anything derived from it; an outage is learned
     * only once the app is alive again, so the stored stays across it claim a silence nothing
     * witnessed. Those are re-judged in the same transaction — see
     * [DerivationStore.rejudgeProvenance], which owns what that means.
     */
    suspend fun materializeOutageIfDead(lastHeartbeat: Long, now: Long, toleranceMs: Long) {
        if (lastHeartbeat <= 0 || now - lastHeartbeat <= toleranceMs) return
        if (dao.lastEvent()?.type != LivenessEvent.TYPE_ARMED) return
        DebugLog.i(TAG, "liveness: outage ${(now - lastHeartbeat) / 1000}s (heartbeat stale)")
        db.withTransaction {
            dao.insert(LivenessEvent(type = LivenessEvent.TYPE_OUTAGE, at = lastHeartbeat, until = now))
            derivation.rejudgeProvenance(from = lastHeartbeat, until = now)
        }
    }
}
