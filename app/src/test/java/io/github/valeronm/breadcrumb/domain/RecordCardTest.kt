package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Record-tab card decision as a pure priority table. The interesting rows are the GPS-waiting
 * states introduced with the no-fix guard: a fresh track shows a waiting card (not an empty map),
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

    // --- recorderCardTitle -----------------------------------------------------

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
    ) = recorderCardTitle(
        state, NOW, activity, pausedActivity, pausedUntilMs, lastReadingAtMs, deaf,
        lastFixAccuracyM, lastFixRejectedByAccuracy,
        gpsSuspendedSinceMs = gpsSuspendedSinceMs,
        // Renders like the real UI would, but only for the exact inputs the title should pass —
        // anything else fails loudly instead of producing a plausible string.
        formatClock = { at -> if (at == SUSPENDED_AT) "14:36" else error("unexpected clock($at)") },
        formatDuration = { "${it / 60_000}m" },
    )

    @Test fun `idle leads with the recording status`() {
        assertEquals("Idle · nothing to record", title(RecordCardState.WAITING_FOR_MOVEMENT))
    }

    @Test fun `a deaf recorder says so instead of looking like ordinary idleness`() {
        // The service is posting a warning about this; the card must not meanwhile report a
        // benign wait, and must not lead with "Idle" — the state is a fault, not a chosen rest.
        // No time is attached: neither number available means what a reader would take it to mean.
        assertEquals(
            "Detection stalled",
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
}
