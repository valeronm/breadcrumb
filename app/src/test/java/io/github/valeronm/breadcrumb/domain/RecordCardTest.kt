package io.github.valeronm.breadcrumb.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shared with [TestWords] below, which is a top-level object and cannot see the suite's fields. */
private const val TEST_NOW = 1_000_000L
private const val TEST_SUSPENDED_AT = 900_000L

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
        gpsSuspended: Boolean = false,
        points: Int = 10,
        hasOpenTrack: Boolean = true,
    ) = recordCardState(armed, tracking, recording, gpsSuspended, points, hasOpenTrack)

    /** The card's view: the recorder's own state with what setup still owes laid over it. */
    private fun card(
        setupComplete: Boolean,
        armed: Boolean = true,
        recording: Boolean = true,
        gpsSuspended: Boolean = false,
        points: Int = 10,
    ) = cardStateWithSetup(
        state(armed = armed, recording = recording, gpsSuspended = gpsSuspended, points = points),
        setupComplete,
    )

    @Test fun `not armed shows stats only, whatever the recorder claims`() {
        assertEquals(RecordCardState.STATS_ONLY, state(armed = false))
        assertEquals(RecordCardState.STATS_ONLY, state(armed = false, recording = true, points = 100))
        assertEquals(RecordCardState.STATS_ONLY, state(armed = false, tracking = false, recording = false))
    }

    @Test fun `the recorder never reports setup, whatever it is doing`() {
        // The notification words every state this returns, and has nothing to say about setup.
        assertNotEquals(RecordCardState.SETUP, state(armed = false, recording = false, points = 0))
        assertNotEquals(RecordCardState.SETUP, state(recording = false, points = 0))
    }

    @Test fun `setup owing outranks every state but a drawable track`() {
        // Nothing can be armed while a requirement is missing, so the unarmed row is the one that
        // matters: read off the recorder alone this is STATS_ONLY, and the card would never appear.
        assertEquals(RecordCardState.SETUP, card(setupComplete = false, armed = false))
        assertEquals(RecordCardState.SETUP, card(setupComplete = false, recording = false, points = 0))
        assertEquals(
            RecordCardState.SETUP,
            card(setupComplete = false, gpsSuspended = true, points = 0),
        )
    }

    @Test fun `a drawable track outranks setup, for a requirement revoked mid-recording`() {
        assertEquals(RecordCardState.LIVE_MAP, card(setupComplete = false, points = 10))
        // Only while there is something to draw: a track without geometry loses to the notice.
        assertEquals(RecordCardState.SETUP, card(setupComplete = false, points = 1))
    }

    @Test fun `a finished setup changes nothing about the recorder's own state`() {
        for (armed in listOf(true, false)) {
            for (points in listOf(0, 10)) {
                val recorder = state(armed = armed, points = points)
                assertEquals(recorder, cardStateWithSetup(recorder, setupComplete = true))
            }
        }
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

    // --- recorderText, as the Record card renders it: live figures, joined to one line ---------

    private val NOW = TEST_NOW
    private val SUSPENDED_AT = TEST_SUSPENDED_AT

    private fun title(
        state: RecordCardState,
        activity: ActivityType? = null,
        lastReadingAtMs: Long? = null,
        deaf: Boolean = false,
        lastFixAccuracyM: Float? = null,
        lastFixRejectedByAccuracy: Boolean = false,
        gpsSuspendedSinceMs: Long? = null,
    ) = TestWords.recorderText(
        state, activity, deaf,
        live = LiveFigures(
            nowMs = NOW,
            lastReadingAtMs = lastReadingAtMs,
            rejectedAccuracyM = lastFixAccuracyM?.takeIf { lastFixRejectedByAccuracy },
            gpsSuspendedSinceMs = gpsSuspendedSinceMs,
        ),
    ).oneLine()

    @Test fun `idle leads with the recording status`() {
        assertEquals("idle · nothingToRecord(-)", title(RecordCardState.WAITING_FOR_MOVEMENT))
    }

    @Test fun `a deaf recorder says so instead of looking like ordinary idleness`() {
        // The service is posting a warning about this; the card must not meanwhile report a
        // benign wait, and must not lead with "Idle" — the state is a fault, not a chosen rest.
        // No time is attached: neither number available means what a reader would take it to mean.
        assertEquals(
            "detectionStalled · restartAdvice",
            title(
                RecordCardState.WAITING_FOR_MOVEMENT,
                lastReadingAtMs = NOW - 17 * 60_000,
                deaf = true,
            ),
        )
    }

    @Test fun `a fresh reading adds nothing — under a minute goes without saying`() {
        assertEquals(
            "idle · nothingToRecord(-)",
            title(RecordCardState.WAITING_FOR_MOVEMENT, lastReadingAtMs = NOW - 30_000),
        )
    }

    @Test fun `an aged reading shows how long there has been nothing to record`() {
        assertEquals(
            "idle · nothingToRecord(17m)",
            title(RecordCardState.WAITING_FOR_MOVEMENT, lastReadingAtMs = NOW - 17 * 60_000),
        )
    }

    @Test fun `positioning keeps the recording status in front`() {
        assertEquals(
            "recording(WALKING) · positioning(-)",
            title(RecordCardState.WAITING_FOR_GPS, activity = ActivityType.WALKING),
        )
        // An old accepted fix (not accuracy-rejected) doesn't fake a radius readout.
        assertEquals(
            "recording(WALKING) · positioning(-)",
            title(
                RecordCardState.WAITING_FOR_GPS,
                activity = ActivityType.WALKING,
                lastFixAccuracyM = 12f,
            ),
        )
    }

    @Test fun `an accuracy-rejected fix shows the current radius`() {
        assertEquals(
            "recording(WALKING) · positioning(78)",
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
            "recording(DRIVING) · noGps(14:36)",
            title(
                RecordCardState.NO_GPS_SIGNAL,
                activity = ActivityType.DRIVING,
                gpsSuspendedSinceMs = SUSPENDED_AT,
            ),
        )
        assertEquals(
            "recording(DRIVING) · noGps(-)",
            title(RecordCardState.NO_GPS_SIGNAL, activity = ActivityType.DRIVING),
        )
    }

    // --- recorderText, as the notification renders it: no live figures, detail on its own line ---
    // Same function and same states as the card above — that is the point. These rows pin the
    // state-only wording, and that the moving figures are absent rather than merely unused: a
    // detail that changed every second would re-post the notification every second.

    private fun notif(
        state: RecordCardState,
        activity: ActivityType? = ActivityType.WALKING,
        deaf: Boolean = false,
    ) = TestWords.recorderText(state, activity, deaf, live = null)
        .let { it.title to it.detailLine() }

    @Test fun `a drawable track reports plain progress`() {
        assertEquals(
            "recording(WALKING)" to "TrackInProgress",
            notif(RecordCardState.LIVE_MAP),
        )
    }

    @Test fun `a track still waiting for its first fix does not claim to be in progress`() {
        // No fix yet is not a track underway; the two states must not share a wording.
        assertEquals(
            "recording(WALKING)" to "WaitingForFix",
            notif(RecordCardState.WAITING_FOR_GPS),
        )
        assertNotEquals(
            notif(RecordCardState.LIVE_MAP),
            notif(RecordCardState.WAITING_FOR_GPS),
        )
    }

    @Test fun `a suspended guard names the missing signal`() {
        assertEquals(
            "recording(DRIVING)" to "NoGpsSettled",
            notif(RecordCardState.NO_GPS_SIGNAL, activity = ActivityType.DRIVING),
        )
    }

    @Test fun `idle says there is nothing to record`() {
        assertEquals(
            "idle" to "NothingToRecord(-)",
            notif(RecordCardState.WAITING_FOR_MOVEMENT, activity = ActivityType.STILL),
        )
    }

    @Test fun `a stalled detector is not reported as idleness`() {
        // The card refuses to call this a benign wait; the notification agrees, and carries the
        // same remedy as the alerts notification for the same condition.
        assertEquals(
            "detectionStalled" to "RestartAdvice",
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
        // A moving figure in the shade costs a wakelock and an IPC every time it changes. Each state
        // must therefore have a figure-less phrasing to fall back on, and reach for it when handed
        // no live values — the markers render an absent argument as "-", so a digit here is a state
        // that quotes something it was never given.
        for (state in RecordCardState.entries) {
            val (title, detail) = notif(state)
            assertFalse("$state: $title · $detail", (title + detail).any { it.isDigit() })
        }
    }
}

/**
 * **Markers, not words.** Each answer names the call the state machine made and what it passed, so
 * an expectation above reads as "this state reached for *this* phrase with *this* argument" — which
 * is the whole of what this file decides. The wording itself is `strings_recorder.xml`'s and a
 * translator's to change; a fake spelling the English out would look like it pinned that wording
 * while pinning nothing, and would go quietly stale the first time a resource was reworded.
 *
 * Markers lead lowercase so the notification's own capitalization stays visible in the expectations:
 * [RecorderText.detailLine] upper-cases what the card leaves alone.
 *
 * [clock] answers only for the one instant a title should ever pass it — anything else fails loudly
 * rather than producing a plausible string.
 */
private object TestWords : RecorderVocabulary {
    private fun clock(atMs: Long) =
        if (atMs == TEST_SUSPENDED_AT) "14:36" else error("unexpected clock($atMs)")

    private fun duration(ms: Long) = "${ms / 60_000}m"

    /** An argument inside a marker; `-` where the state machine passed nothing. */
    private fun Any?.arg() = this?.toString() ?: "-"

    override fun recording(activity: ActivityType?) = "recording(${activity.arg()})"

    override fun idle() = "idle"

    override fun detectionStalled() = "detectionStalled"

    override fun starting() = "starting"

    override fun trackInProgress() = "trackInProgress"

    override fun noGps(sinceMs: Long?) = "noGps(${sinceMs?.let(::clock).arg()})"

    override fun noGpsSettled() = "noGpsSettled"

    override fun positioning(accuracyM: Float?) = "positioning(${accuracyM?.toInt().arg()})"

    override fun waitingForFix() = "waitingForFix"

    override fun nothingToRecord(quietMs: Long?) =
        "nothingToRecord(${quietMs?.let(::duration).arg()})"

    override fun restartAdvice() = "restartAdvice"
}
