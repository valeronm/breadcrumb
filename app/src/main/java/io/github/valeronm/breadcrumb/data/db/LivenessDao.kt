package io.github.valeronm.breadcrumb.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LivenessDao {
    @Insert
    suspend fun insert(event: LivenessEvent): Long

    /** Backup restore: one transaction for the whole list, not one per row. */
    @Insert
    suspend fun insertAll(events: List<LivenessEvent>)

    @Query("SELECT * FROM liveness_events ORDER BY at DESC, id DESC LIMIT 1")
    suspend fun lastEvent(): LivenessEvent?

    @Query("SELECT * FROM liveness_events ORDER BY at ASC, id ASC")
    fun observeAll(): Flow<List<LivenessEvent>>

    @Query("SELECT * FROM liveness_events ORDER BY at ASC, id ASC")
    suspend fun allEvents(): List<LivenessEvent>

    /**
     * Every event that can bear on `[from, until)` — what a reader asking about one stretch of time
     * needs, instead of a log that grows for as long as the app is installed. Three arms, each a
     * case `StayDeriver.summarizeLivenessOver` would otherwise miss: the events inside the window;
     * the last outage before it, which may not have ended when the window opened; and the last arm
     * or disarm before it, which is the whole of the state that fold carries from one event to the
     * next. The arms are disjoint — by time, then by type — so nothing needs deduplicating.
     *
     * **Every arm is a bounded range over an indexed column, and that is the whole point of the
     * query** — the indices on `liveness_events` are what make it so, and they exist for this.
     * Asking instead for the outages that *reach* the window (`until > :from`) reads as the plainer
     * question and is the one thing this must not do: `until` carries no index, so the planner drives
     * that arm off `at < :from` and visits every row before the window — which, a repair sitting at
     * the head of the history, is the entire log it was meant to avoid. The last outage is enough
     * because outages cannot overlap, an invariant of how they are written and stated where they are
     * (`LivenessRepository`). Finding it is a seek on `(type, at)`; against `at` alone it would be a
     * walk back to the nearest earlier outage, which on a log that has never had one is all of it.
     *
     * **Candidates, not a verdict** — what a silence makes of a stay is the domain's to say, and a
     * query that decided it here would be that rule's second author. Reading it back needs
     * `summarizeLivenessOver`'s `earliestAt` too ([earliestEventAt]): a window cannot see the log's
     * own beginning, and an interval before that is unattested however quiet the window was.
     */
    @Query(
        "SELECT * FROM liveness_events WHERE at >= :from AND at < :until " +
            "UNION ALL SELECT * FROM (SELECT * FROM liveness_events WHERE at < :from " +
            "AND type = '" + LivenessEvent.TYPE_OUTAGE + "' ORDER BY at DESC, id DESC LIMIT 1) " +
            "UNION ALL SELECT * FROM (SELECT * FROM liveness_events WHERE at < :from " +
            "AND type != '" + LivenessEvent.TYPE_OUTAGE + "' ORDER BY at DESC, id DESC LIMIT 1) " +
            "ORDER BY at ASC, id ASC",
    )
    suspend fun eventsAround(from: Long, until: Long): List<LivenessEvent>

    /** When the record begins — see [eventsAround], which cannot answer it. */
    @Query("SELECT MIN(at) FROM liveness_events")
    suspend fun earliestEventAt(): Long?
}
