package io.github.valeronm.breadcrumb.domain

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
        gpsSuspended: Boolean = false,
        points: Int = 10,
        hasOpenTrack: Boolean = true,
    ) = recordCardState(armed, tracking, recording, gpsSuspended, points, hasOpenTrack)

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
}
