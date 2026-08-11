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

    /** A value that moves whenever a row is written and costs nothing to read — see
     *  [LivenessRepository.observeChanges], which is what wants it and why nothing observes the
     *  rows themselves. */
    @Query("SELECT MAX(id) FROM liveness_events")
    fun observeLatestId(): Flow<Long?>

    @Query("SELECT * FROM liveness_events ORDER BY at ASC, id ASC")
    suspend fun allEvents(): List<LivenessEvent>

    /**
     * Every event that can bear on `[from, until]` — what a reader asking about one stretch of time
     * needs, instead of a log that grows for as long as the app is installed.
     *
     * **`StayDeriver.bearingOn` is the rule; this is a read an index can serve that is guaranteed to
     * *contain* it.** It owes a superset and nothing finer, because its caller passes what comes back
     * through that rule before using it — so an arm may be generous (the last outage before the
     * window comes back whether or not it was still open) and only a *missing* event would be a
     * fault. `LivenessWindowTest` is where that containment is held. It rests on outages never
     * overlapping — the rule wants every outage still open when the window began, and taking the
     * last one alone finds them all only because an earlier one has closed before it started, which
     * is an invariant of how they are written and stated where they are (`LivenessRepository`). The
     * arms are disjoint — by time, then by type — so nothing needs deduplicating.
     *
     * **Every arm is a bounded range over an indexed column, and that is the whole point of the
     * query** — the indices on `liveness_events` are what make it so, and they exist for this.
     * Asking instead for the outages that *reach* the window (`until > :from`) reads as the plainer
     * question and is the one thing this must not do: `until` carries no index, so the planner drives
     * that arm off `at < :from` and visits every row before the window — which, a repair sitting at
     * the head of the history, is the entire log it was meant to avoid. Taking the last outage
     * instead is a seek on `(type, at)`; against `at` alone it would be a walk back to the nearest
     * earlier outage, which on a log that has never had one is all of it.
     *
     * **Candidates, not a verdict** — what a silence makes of a stay is the domain's to say, and a
     * query that decided it here would be that rule's second author. Reading it back needs
     * `summarizeLivenessOver`'s `earliestAt` too ([earliestEventAt]): a window cannot see the log's
     * own beginning, and an interval before that is unattested however quiet the window was.
     */
    @Query(
        // `at <= :until`, not `<`: the window this answers for is closed at both ends, and an event
        // landing exactly on its far bound is one the rule keeps.
        "SELECT * FROM liveness_events WHERE at >= :from AND at <= :until " +
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

    /**
     * When the app last stopped attesting with nothing since — the instant the trailing stay closes
     * at, or null while armed.
     *
     * **Two seeks rather than a fold over the log**, which is the whole reason it is a query: the
     * last arm, then the earliest disarm after it. Reading it off a windowed reduction is the one
     * thing that cannot work, a window holding the last unclosed disarm it happened to contain
     * rather than the first of the run — see `StayDeriver.tail`, which takes the two apart.
     *
     * `MAX`/`MIN` over `(type, at)` are covering-index seeks, so this is constant whatever the log
     * has grown to. The `IFNULL` is what makes a log with no ARMED row in it answer as the fold
     * does — every disarm is then unclosed, and the earliest opens the run — rather than as SQL's
     * `at > NULL` would, which matches nothing and would report a disarmed app as armed.
     */
    @Query(
        "SELECT MIN(at) FROM liveness_events WHERE type = '" + LivenessEvent.TYPE_DISARMED + "' " +
            "AND at > IFNULL((SELECT MAX(at) FROM liveness_events " +
            "WHERE type = '" + LivenessEvent.TYPE_ARMED + "'), -9223372036854775808)",
    )
    suspend fun disarmedSince(): Long?
}
