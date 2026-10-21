package io.github.valeronm.breadcrumb.data

import androidx.room.withTransaction
import io.github.valeronm.breadcrumb.data.db.AppDatabase
import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.data.db.TrackDao
import io.github.valeronm.breadcrumb.domain.TrackSettle
import io.github.valeronm.breadcrumb.util.DebugLog

private const val TAG = "Breadcrumb"

/** Tracks per transaction in a sweep — see [HistorySweeps.resettle]. */
private const val SWEEP_BATCH_TRACKS = 100

/**
 * The standing, versioned walks over the whole history — what `App.onCreate` runs when a rule's
 * version outran the one the rows were computed by. Unlike CLAUDE.md's one-shot backfills these are
 * infrastructure, deliberately: the ignored fixes, the bounds over them and the aggregates are
 * verdicts of rules that keep moving, so don't delete a sweep once it has run; the next rule change
 * needs it.
 */
internal class HistorySweeps(
    private val db: AppDatabase,
    private val dao: TrackDao,
    private val settler: TrackSettler,
) {

    /**
     * Re-derive every kept track's settle flags and clock against the current rules
     * ([TrackSettle.RULE_VERSION] says which produced the stored ones). Self-correcting in
     * both directions, since the plan comes from the raw recording: a rule that now finds less
     * hands the points back, one that finds more takes them, and a crash mid-pass costs only a
     * re-run (the version is stored after, once the derivation has consumed what the sweep moved —
     * the bounds and the first/last good coordinates it rewrites are exactly what a stay is derived
     * from). Points are loaded one track at a time — the whole history is over a million rows and
     * must never be resident at once — and tracks commit in batches: `tracks` is observed, so a
     * commit per track would re-run the timeline's queries and everything they feed thousands of
     * times, while one transaction for the lot would hold every rewritten point row in the journal
     * at once.
     */
    suspend fun resettle(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }) {
        val tracks = dao.exportTracks()
        val changed = sweep(tracks, onProgress) { resettleTrack(it) }
        DebugLog.i(
            TAG,
            "settle sweep (rule v${TrackSettle.RULE_VERSION}) over ${tracks.size} " +
                "tracks: $changed rewritten",
        )
    }

    /**
     * Re-walk every finished track's points and rewrite its aggregates — the standing answer to
     * [TrackStats.RULE_VERSION] moving, in the shape [resettle] uses for its own rule. A stored
     * total is the output of a walk that has since changed, and nothing re-walks a track whose points
     * sat still: the settle sweep skips it, and a track is otherwise re-walked only when finished,
     * merged, imported or retyped. Every track is swept, not just the ones a particular change
     * touched — which tracks a *future* change reaches isn't knowable here, and a walk that skipped
     * some would quietly leave them behind. Discarded tracks are included, unlike the settle
     * sweep's set (Recently deleted shows a distance, and restoring brings the row back as it
     * stands); open tracks are skipped — the recorder owns those columns until it finishes, where
     * the same walk runs anyway. Idempotent: it recomputes from the points, and the version is
     * stored after, as [resettle]'s is, so an interrupted sweep costs a re-run. Points load one
     * track at a time and commit in batches, for the reasons on [resettle] — and a track whose
     * stored columns already agree is left alone: `tracks` is observed, so a needless UPDATE per
     * track re-runs the timeline.
     */
    suspend fun stats(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }) {
        val tracks = dao.finishedTracks()
        val changed = sweep(tracks, onProgress) { track ->
            val stats = TrackStats.of(dao.allPointsFor(track.id))
            if (stats.matches(track)) {
                false
            } else {
                settler.refreshStats(track.id, stats)
                true
            }
        }
        DebugLog.i(
            TAG,
            "stats sweep (rule v${TrackStats.RULE_VERSION}) over ${tracks.size} " +
                "tracks: $changed rewritten",
        )
    }

    /**
     * Re-settle one finished track and, when a point changed hands, the aggregates that follow — the
     * whole of what a stored track needs when a rule, or the tuning its activity selects, has changed
     * under it. Returns whether it wrote anything, which a moved bound counts towards even though it
     * leaves the aggregates alone (see [TrackSettler.Applied.pointsMoved]). An open track is skipped
     * (the recorder is still adding to the edge the rule would cut; finishing runs this itself).
     */
    private suspend fun resettleTrack(track: Track): Boolean {
        val endedAt = track.endedAt ?: return false
        return settler.settleAndRefresh(track, endedAt, dao.allPointsFor(track.id), totalsStale = false).changed
    }

    /**
     * The walk both sweeps share: [rederive] runs per item and returns whether it wrote anything
     * (only the count is reported). The batching and progress cadence are why this is one function —
     * [resettle] says why each is shaped as it is. A sweep that agrees with the stored rows must
     * cost no writes, so a `rederive` decides for itself whether there is anything to store.
     */
    private suspend fun <T> sweep(
        items: List<T>,
        onProgress: (done: Int, total: Int) -> Unit,
        rederive: suspend (T) -> Boolean,
    ): Int {
        var changed = 0
        onProgress(0, items.size)
        for ((batch, chunk) in items.chunked(SWEEP_BATCH_TRACKS).withIndex()) {
            db.withTransaction {
                for ((i, item) in chunk.withIndex()) {
                    // Every 10 items: per item, the count would change faster than it can be read.
                    if (i % 10 == 0) onProgress(batch * SWEEP_BATCH_TRACKS + i, items.size)
                    if (rederive(item)) changed++
                }
            }
        }
        return changed
    }
}
