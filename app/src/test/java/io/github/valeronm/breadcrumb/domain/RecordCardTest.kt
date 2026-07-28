package io.github.valeronm.breadcrumb.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Record-tab card decision as a pure priority table. The interesting rows are the no-fix
 * guard's GPS-waiting states: a fresh track shows a waiting card (not an empty map),
 * and a guard suspension mid-track keeps the map because there's real geometry to show.
 */
class RecordCardTest {

    private fun state(
        armed: Boolean = true,
        tracking: Boolean = true,
        recording: Boolean = true,
        paused: Boolean = false,
        gpsSuspended: Boolean = false,
        points: Int = 10,
        hasOpenTrack: Boolean = true,
    ) = recordCardState(armed, tracking, recording, paused, gpsSuspended, points, hasOpenTrack)

    @Test fun `not armed shows stats only, whatever the recorder claims`() {
        assertEquals(RecordCardState.STATS_ONLY, state(armed = false))
        assertEquals(RecordCardState.STATS_ONLY, state(armed = false, recording = true, points = 100))
        assertEquals(RecordCardState.STATS_ONLY, state(armed = false, tracking = false, recording = false))
    }

    @Test fun `armed before the service publishes anything is starting`() {
        assertEquals(RecordCardState.STARTING, state(tracking = false, recording = false, points = 0))
    }

    @Test fun `armed and idle waits for movement`() {
        assertEquals(RecordCardState.WAITING_FOR_MOVEMENT, state(recording = false, points = 0))
    }

    @Test fun `an auto-paused track shows the paused card, not standing by`() {
        assertEquals(RecordCardState.PAUSED, state(recording = false, paused = true, points = 0))
    }

    @Test fun `paused loses to any recording state`() {
        // A stale paused flag must never override an active recording's cards.
        assertEquals(RecordCardState.LIVE_MAP, state(paused = true))
        assertEquals(RecordCardState.WAITING_FOR_GPS, state(paused = true, points = 0))
    }

    @Test fun `a fresh track with no fixes waits for GPS instead of showing an empty map`() {
        assertEquals(RecordCardState.WAITING_FOR_GPS, state(points = 0))
    }

    @Test fun `one point is still not drawable`() {
        assertEquals(RecordCardState.WAITING_FOR_GPS, state(points = 1))
    }

    @Test fun `two points is enough for the live map`() {
        assertEquals(RecordCardState.LIVE_MAP, state(points = MIN_MAP_POINTS))
    }

    @Test fun `guard suspension with no geometry shows the no-signal card`() {
        assertEquals(RecordCardState.NO_GPS_SIGNAL, state(gpsSuspended = true, points = 0))
        assertEquals(RecordCardState.NO_GPS_SIGNAL, state(gpsSuspended = true, points = 1))
    }

    @Test fun `guard suspension mid-track keeps the map — there is a track to show`() {
        assertEquals(RecordCardState.LIVE_MAP, state(gpsSuspended = true, points = 500))
    }

    @Test fun `no open track never shows the map, even with stale point state`() {
        // Around finalization the service can briefly publish recording=true after the track closed.
        assertEquals(RecordCardState.WAITING_FOR_GPS, state(points = 10, hasOpenTrack = false))
    }

    // --- recorderText, as the Record card renders it: live figures, joined to one line ---------

    private val NOW = 1_000_000L
    private val SUSPENDED_AT = 900_000L

    private fun title(
        state: RecordCardState,
        activity: ActivityType? = null,
        pausedActivity: ActivityType? = null,
        pausedUntilMs: Long? = null,
        lastReadingAtMs: Long? = null,
        deaf: Boolean = false,
        lastFixAccuracyM: Float? = null,
        lastFixRejectedByAccuracy: Boolean = false,
        gpsSuspendedSinceMs: Long? = null,
    ) = recorderText(
        state, activity, pausedActivity, deaf,
        live = LiveFigures(
            nowMs = NOW,
            // Renders like the real UI would, but only for the exact inputs the title should pass —
            // anything else fails loudly instead of producing a plausible string.
            render = TimeRenderer(
                clock = { at -> if (at == SUSPENDED_AT) "14:36" else error("unexpected clock($at)") },
                duration = { "${it / 60_000}m" },
            ),
            pausedUntilMs = pausedUntilMs,
            lastReadingAtMs = lastReadingAtMs,
            rejectedAccuracyM = lastFixAccuracyM?.takeIf { lastFixRejectedByAccuracy },
            gpsSuspendedSinceMs = gpsSuspendedSinceMs,
        ),
    ).oneLine()

    @Test fun `idle leads with the recording status`() {
        assertEquals("Idle · nothing to record", title(RecordCardState.WAITING_FOR_MOVEMENT))
    }

    @Test fun `a deaf recorder says so instead of looking like ordinary idleness`() {
        // The service is posting a warning about this; the card must not meanwhile report a
        // benign wait, and must not lead with "Idle" — the state is a fault, not a chosen rest.
        // No time is attached: neither number available means what a reader would take it to mean.
        assertEquals(
            "Detection stalled · restarting the phone usually fixes it",
            title(
                RecordCardState.WAITING_FOR_MOVEMENT,
                lastReadingAtMs = NOW - 17 * 60_000,
                deaf = true,
            ),
        )
    }

    @Test fun `a fresh reading adds nothing — under a minute goes without saying`() {
        assertEquals(
            "Idle · nothing to record",
            title(RecordCardState.WAITING_FOR_MOVEMENT, lastReadingAtMs = NOW - 30_000),
        )
    }

    @Test fun `an aged reading shows how long there has been nothing to record`() {
        assertEquals(
            "Idle · nothing to record for 17m",
            title(RecordCardState.WAITING_FOR_MOVEMENT, lastReadingAtMs = NOW - 17 * 60_000),
        )
    }

    @Test fun `paused says what resumes and the time left`() {
        assertEquals(
            "Paused · walking resumes within 1m 40s",
            title(
                RecordCardState.PAUSED,
                pausedActivity = ActivityType.WALKING,
                pausedUntilMs = NOW + 100_000,
            ),
        )
    }

    @Test fun `a lapsed resume window reads as idle, never as a stuck countdown`() {
        // Past the deadline nothing resumes into the track (the next activity starts a new one),
        // so promising a resume — or showing "0s" while a Doze-deferred timer catches up — would
        // be a lie.
        assertEquals(
            "Idle · nothing to record",
            title(
                RecordCardState.PAUSED,
                pausedActivity = ActivityType.WALKING,
                pausedUntilMs = NOW - 5_000,
            ),
        )
        assertEquals(
            "Idle · nothing to record for 17m",
            title(
                RecordCardState.PAUSED,
                pausedActivity = ActivityType.WALKING,
                pausedUntilMs = NOW - 5_000,
                lastReadingAtMs = NOW - 17 * 60_000,
            ),
        )
    }

    @Test fun `the last second of the window still counts down`() {
        assertEquals(
            "Paused · walking resumes within 1s",
            title(
                RecordCardState.PAUSED,
                pausedActivity = ActivityType.WALKING,
                pausedUntilMs = NOW + 1,
            ),
        )
    }

    @Test fun `paused survives a missing deadline`() {
        assertEquals(
            "Paused · walking",
            title(RecordCardState.PAUSED, pausedActivity = ActivityType.WALKING),
        )
    }

    @Test fun `positioning keeps the recording status in front`() {
        assertEquals(
            "Recording walking · positioning",
            title(RecordCardState.WAITING_FOR_GPS, activity = ActivityType.WALKING),
        )
        // An old accepted fix (not accuracy-rejected) doesn't fake a radius readout.
        assertEquals(
            "Recording walking · positioning",
            title(
                RecordCardState.WAITING_FOR_GPS,
                activity = ActivityType.WALKING,
                lastFixAccuracyM = 12f,
            ),
        )
    }

    @Test fun `an accuracy-rejected fix shows the current radius`() {
        assertEquals(
            "Recording walking · positioning ±78 m",
            title(
                RecordCardState.WAITING_FOR_GPS,
                activity = ActivityType.WALKING,
                lastFixAccuracyM = 78.4f,
                lastFixRejectedByAccuracy = true,
            ),
        )
    }

    @Test fun `no gps shows since when the guard suspended`() {
        assertEquals(
            "Recording driving · no GPS since 14:36",
            title(
                RecordCardState.NO_GPS_SIGNAL,
                activity = ActivityType.DRIVING,
                gpsSuspendedSinceMs = SUSPENDED_AT,
            ),
        )
        assertEquals(
            "Recording driving · no GPS",
            title(RecordCardState.NO_GPS_SIGNAL, activity = ActivityType.DRIVING),
        )
    }

    @Test fun `countdown rounds up and drops the minute part under a minute`() {
        assertEquals("25s", formatCountdown(25_000))
        assertEquals("25s", formatCountdown(24_001))
        assertEquals("1m 0s", formatCountdown(60_000))
        assertEquals("1m 40s", formatCountdown(100_000))
        assertEquals("0s", formatCountdown(0))
    }

    // --- recorderText, as the notification renders it: no live figures, detail on its own line ---
    // Same function and same states as the card above — that is the point. These rows pin the
    // state-only wording, and that the moving figures are absent rather than merely unused: a
    // detail that changed every second would re-post the notification every second.

    private fun notif(
        state: RecordCardState,
        activity: ActivityType? = ActivityType.WALKING,
        pausedActivity: ActivityType? = null,
        deaf: Boolean = false,
    ) = recorderText(state, activity, pausedActivity, deaf, live = null)
        .let { it.title to it.detailLine() }

    @Test fun `a drawable track reports plain progress`() {
        assertEquals(
            "Recording walking" to "Track in progress",
            notif(RecordCardState.LIVE_MAP),
        )
    }

    @Test fun `a track still waiting for its first fix does not claim to be in progress`() {
        // No fix yet is not a track underway; the two states must not share a wording.
        assertEquals(
            "Recording walking" to "Waiting for a GPS fix",
            notif(RecordCardState.WAITING_FOR_GPS),
        )
        assertNotEquals(
            notif(RecordCardState.LIVE_MAP),
            notif(RecordCardState.WAITING_FOR_GPS),
        )
    }

    @Test fun `a suspended guard names the missing signal`() {
        assertEquals(
            "Recording driving" to "No GPS signal — waiting for one",
            notif(RecordCardState.NO_GPS_SIGNAL, activity = ActivityType.DRIVING),
        )
    }

    @Test fun `a pause names the paused activity, not the current reading`() {
        // Pausing sets the reading to STILL while the track belongs to the activity that opened it,
        // so the remembered activity is the one worth naming — "Stationary continues" says nothing.
        assertEquals(
            "Paused" to "Walking continues if you move soon",
            notif(RecordCardState.PAUSED, activity = ActivityType.STILL, pausedActivity = ActivityType.WALKING),
        )
    }

    @Test fun `a pause falls back to the current reading when none was remembered`() {
        assertEquals(
            "Paused" to "Stationary continues if you move soon",
            notif(RecordCardState.PAUSED, activity = ActivityType.STILL),
        )
    }

    @Test fun `idle says there is nothing to record`() {
        assertEquals(
            "Idle" to "Nothing to record",
            notif(RecordCardState.WAITING_FOR_MOVEMENT, activity = ActivityType.STILL),
        )
    }

    @Test fun `a stalled detector is not reported as idleness`() {
        // The card refuses to call this a benign wait; the notification agrees, and carries the
        // same remedy as the alerts notification for the same condition.
        assertEquals(
            "Detection stalled" to "Restarting the phone usually fixes it",
            notif(RecordCardState.WAITING_FOR_MOVEMENT, activity = ActivityType.STILL, deaf = true),
        )
    }

    @Test fun `deafness never overrides an open track's own report`() {
        // A stall is about what happens next; a track in hand is still being recorded.
        for (state in listOf(
            RecordCardState.LIVE_MAP,
            RecordCardState.WAITING_FOR_GPS,
            RecordCardState.NO_GPS_SIGNAL,
        )) {
            assertEquals(notif(state), notif(state, deaf = true))
        }
    }

    @Test fun `every state is worded for both surfaces`() {
        // Totality is the point: a state added to the enum must be worded here, not defaulted into
        // whichever branch happened to be last. STARTING is the one state with no detail to add.
        for (state in RecordCardState.entries) {
            val (title, detail) = notif(state)
            assertTrue("blank title for $state", title.isNotBlank())
            assertTrue("blank card line for $state", title(state).isNotBlank())
            if (state != RecordCardState.STARTING) {
                assertTrue("blank detail for $state", detail.isNotBlank())
            }
        }
    }

    @Test fun `the notification never carries a figure that would re-post it`() {
        // Every state-only detail must be constant for a given state: no clock, no countdown, no
        // radius. Rendered twice with different live inputs available, the text must be identical —
        // which it is by construction, since live = null cannot reach them.
        for (state in RecordCardState.entries) {
            assertEquals(notif(state), notif(state))
        }
        // And the state-only pause detail is settled wording, not the card's countdown — it names
        // what would resume without saying when.
        assertEquals(
            "Paused" to "Walking continues if you move soon",
            notif(RecordCardState.PAUSED, pausedActivity = ActivityType.WALKING),
        )
    }
}
