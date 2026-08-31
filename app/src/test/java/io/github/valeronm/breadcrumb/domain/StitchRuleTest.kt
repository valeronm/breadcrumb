package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the last track takes a returning stretch's fixes. Every disqualification is a case here —
 * this is where the outcomes are pinned, so the Room suite is left asking only what was written.
 */
class StitchRuleTest {

    private fun track(
        activityType: String = ActivityType.WALKING.name,
        endedAt: Long? = ENDED,
        source: String? = TrackOrigin.RECORDED.code,
        discardedAt: Long? = null,
        discardReason: String? = null,
    ) = Track(
        id = 7,
        activityType = activityType,
        startedAt = ENDED - 600_000,
        endedAt = endedAt,
        source = source,
        discardedAt = discardedAt,
        discardReason = discardReason,
    )

    private fun label(
        last: Track = track(),
        lastPointMs: Long? = ENDED,
        opening: ActivityType = ActivityType.WALKING,
        nowMs: Long = ENDED + 60_000,
        windowMs: Long = WINDOW,
    ) = StitchRule.continuedLabel(last, lastPointMs, opening, nowMs, windowMs)

    private fun continues(
        last: Track = track(),
        lastPointMs: Long? = ENDED,
        opening: ActivityType = ActivityType.WALKING,
        nowMs: Long = ENDED + 60_000,
        windowMs: Long = WINDOW,
    ) = label(last, lastPointMs, opening, nowMs, windowMs) != null

    @Test fun `a prompt return in the same family continues the last track`() {
        assertTrue(continues())
    }

    @Test fun `what comes back is the row's label, not the one returning`() {
        // The ceilings the fixes are gated by, and the rename verdict at the next close, both read
        // it — so a run continuing a walk records under WALKING.
        assertEquals(ActivityType.WALKING, label(opening = ActivityType.RUNNING))
    }

    // --- The window ----------------------------------------------------------

    @Test fun `at the window exactly, it is over`() {
        // Strictly greater, so the boundary belongs to the new track.
        assertFalse(continues(nowMs = ENDED + WINDOW))
        assertTrue(continues(nowMs = ENDED + WINDOW - 1))
    }

    @Test fun `a zero window never continues anything`() {
        assertFalse(continues(nowMs = ENDED, windowMs = 0))
    }

    @Test fun `the window runs from the last point, not from the row's end`() {
        // The overrun rule pulls endedAt back to the last good fix, so a track whose parked tail was
        // trimmed still has points well after it — and those are what date the window.
        val trimmed = track(endedAt = ENDED - 90_000)
        assertTrue(continues(last = trimmed, lastPointMs = ENDED, nowMs = ENDED + 60_000))
    }

    @Test fun `a track with no points has nothing to date the window from`() {
        assertFalse(continues(lastPointMs = null))
    }

    // --- What the row is -----------------------------------------------------

    @Test fun `an open row is never continued`() {
        assertFalse(continues(last = track(endedAt = null)))
    }

    @Test fun `only the recorder's own tracks continue`() {
        assertFalse(continues(last = track(source = TrackOrigin.IMPORTED.code)))
        assertFalse(continues(last = track(source = TrackOrigin.MANUAL.code)))
        assertFalse(continues(last = track(source = null)))
    }

    @Test fun `a track filed by the keep thresholds is continued, and comes back with it`() {
        val filed = track(discardedAt = ENDED, discardReason = Track.REASON_FILTERED)
        assertTrue(continues(last = filed))
    }

    @Test fun `a discard the user made is not resurrected`() {
        assertFalse(continues(last = track(discardedAt = ENDED, discardReason = Track.REASON_DELETED)))
        assertFalse(continues(last = track(discardedAt = ENDED, discardReason = Track.REASON_MERGED)))
    }

    @Test fun `a discarded row whose reason no longer reads is refused`() {
        // Rows discarded before reasons were tracked, and rows whose code has been retired: nothing
        // says whose decision it was, so it is not the app's to undo.
        assertFalse(continues(last = track(discardedAt = ENDED, discardReason = null)))
    }

    @Test fun `an unreadable stored label is refused`() {
        assertFalse(continues(last = track(activityType = "SEGWAY")))
    }

    // --- The motion family ---------------------------------------------------

    @Test fun `a cross-family return starts fresh`() {
        assertFalse(continues(opening = ActivityType.DRIVING))
    }

    @Test fun `a carrier-renamed track is not continued by the label it used to carry`() {
        // The finish rewrites a proven-carried foot track to UNKNOWN, whose group shares with
        // nothing — and its fixes were kept under that ceiling, so a walk returning is a new track.
        val renamed = track(activityType = ActivityType.UNKNOWN.name)
        assertFalse(continues(last = renamed, opening = ActivityType.WALKING))
        assertTrue(continues(last = renamed, opening = ActivityType.UNKNOWN))
    }

    private companion object {
        const val ENDED = 1_000_000L
        const val WINDOW = 180_000L
    }
}
