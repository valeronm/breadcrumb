package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackSummary
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a stretch of tracks came to per activity — a timeline day header's top line and the Record
 * tab's per-period summary, which are the same question over different stretches.
 *
 * Untested while this lived in a screen file, where reaching it meant composing a day header. The
 * cases that matter are the two it is easy to get wrong: a track still recording, and a stored type
 * this build cannot read.
 */
class ActivityTotalsTest {

    private val now = 1_000 * MIN

    private var nextId = 0L

    private fun track(activity: String, startedAt: Long, endedAt: Long?, meters: Double) =
        trackSummary(++nextId, activity, startedAt, endedAt, meters)

    private fun totals(vararg tracks: TrackSummary) = activityTotals(tracks.toList(), now)

    @Test fun `tracks of one activity sum their distance and their time`() {
        val totals = totals(
            track(ActivityType.DRIVING.name, 10 * MIN, 40 * MIN, 12_000.0),
            track(ActivityType.DRIVING.name, 60 * MIN, 80 * MIN, 8_000.0),
        ).single()
        assertEquals(ActivityType.DRIVING.name, totals.activityType)
        assertEquals(20_000.0, totals.meters, 0.0)
        assertEquals(50 * MIN, totals.durationMs)
    }

    @Test fun `a track still recording runs to now`() {
        val totals = totals(track(ActivityType.WALKING.name, 900 * MIN, null, 500.0)).single()
        assertEquals(100 * MIN, totals.durationMs)
    }

    // The same rule the month figures follow: a code this build cannot read is still a distance, and
    // merging every such code into one bucket would add together things that were never the same.
    @Test fun `an unreadable stored type keeps a bucket of its own`() {
        val totals = totals(
            track("SEGWAY", 10 * MIN, 20 * MIN, 1_000.0),
            track("HORSE", 30 * MIN, 40 * MIN, 2_000.0),
        )
        assertEquals(setOf("SEGWAY", "HORSE"), totals.map { it.activityType }.toSet())
    }

    @Test fun `the furthest activity leads`() {
        val totals = totals(
            track(ActivityType.WALKING.name, 10 * MIN, 20 * MIN, 3_000.0),
            track(ActivityType.DRIVING.name, 30 * MIN, 40 * MIN, 40_000.0),
            track(ActivityType.CYCLING.name, 50 * MIN, 60 * MIN, 12_000.0),
        )
        assertEquals(
            listOf(ActivityType.DRIVING.name, ActivityType.CYCLING.name, ActivityType.WALKING.name),
            totals.map { it.activityType },
        )
    }

    @Test fun `nothing recorded is no totals at all`() {
        assertEquals(emptyList<ActivityTotal>(), activityTotals(emptyList(), now))
    }
}
