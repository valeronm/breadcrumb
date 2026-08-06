package io.github.valeronm.breadcrumb.location

import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.IgnoreReason
import io.github.valeronm.breadcrumb.domain.Motion
import io.github.valeronm.breadcrumb.domain.MovementConfirmer
import io.github.valeronm.breadcrumb.domain.ORIGIN_LAT
import io.github.valeronm.breadcrumb.domain.flatDistance
import io.github.valeronm.breadcrumb.domain.lonAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fix path off the device. Everything here used to need a walk with the phone: the rules were
 * each unit-tested, but the loop that sequences them — which gate rejects first, what the witness is
 * fed, what the card ends up showing — was only ever exercised by recording.
 *
 * Fixtures sit at the domain suites' neutral origin and move east in metres, on their same
 * flat-earth stub.
 */
class FixIngestTest {

    private val ingest = FixIngest(flatDistance)

    private fun fix(atSec: Int, eastM: Double, accuracy: Float? = 5f, gnssAgeMs: Long = 0) = Fix(
        latitude = ORIGIN_LAT,
        longitude = lonAt(eastM),
        altitude = null,
        accuracy = accuracy,
        speed = null,
        bearing = null,
        timeMs = T0 + atSec * 1000L,
        verticalAccuracy = null,
        speedAccuracy = null,
        bearingAccuracy = null,
        elapsedRealtimeMs = atSec * 1000L + gnssAgeMs,
    )

    private fun walking(stillParked: Boolean = false) = GateState(ActivityType.WALKING, stillParked)

    private fun feed(
        vararg fixes: Fix,
        gate: GateState = walking(),
        settings: IngestSettings = OPEN,
        gnss: GnssState = SEEN,
    ) = ingest.onFixes(TRACK, fixes.toList(), gate, settings, gnss)

    @Test fun `a walk's fixes are recorded as points on the open track`() {
        ingest.onTrackOpened(ActivityType.WALKING)

        // A metre a second: a pace no ceiling objects to.
        val out = feed(fix(0, 0.0), fix(1, 1.0), fix(2, 2.0), fix(3, 3.0), fix(4, 4.0))

        assertEquals(5, out.points.size)
        assertEquals(5, out.accepted)
        assertTrue("all on the path", out.points.none { it.ignored })
        assertTrue("all on this track", out.points.all { it.trackId == TRACK })
        assertEquals(5, ingest.pointCount)
        assertEquals(4.0, ingest.distanceMeters, 0.5)
    }

    @Test fun `a fix too coarse to trust is stored, flagged, and left out of the distance`() {
        ingest.onTrackOpened(ActivityType.WALKING)

        val out = feed(fix(0, 0.0), fix(1, 1.0, accuracy = 200f), fix(2, 2.0))

        assertEquals("stored, not dropped", 3, out.points.size)
        assertEquals(listOf(null, IgnoreReason.ACCURACY.code, null), out.points.map { it.ignoreReason })
        assertEquals(2, out.accepted)
        // The good pair are 2 m apart; the rejected fix between them is not a waypoint of the path.
        assertEquals(2.0, ingest.distanceMeters, 0.5)
    }

    @Test fun `a teleport past the walking ceiling is flagged a jump`() {
        ingest.onTrackOpened(ActivityType.WALKING)

        // 500 m in a second is 1800 km/h — past anything a body does.
        val out = feed(fix(0, 0.0), fix(1, 500.0))

        assertEquals(IgnoreReason.JUMP.code, out.points.last().ignoreReason)
    }

    @Test fun `the segment break lands on the fix that resumes, and only on a good one`() {
        ingest.onTrackOpened(ActivityType.WALKING)
        feed(fix(0, 0.0), fix(1, 1.0))

        ingest.markSegmentStart()
        // The first fix after the resume is too coarse to keep, so the break waits for the next.
        val out = feed(fix(60, 2.0, accuracy = 200f), fix(61, 3.0))

        assertEquals(listOf(false, true), out.points.map { it.segmentStart })
    }

    @Test fun `ground that outruns the walking ceiling shows as Moving without retyping the track`() {
        ingest.onTrackOpened(ActivityType.WALKING)

        // ~14 m/s — a carrier's pace, and the fixes are accepted because the witness lifts the
        // ceiling to fit the ground it just measured. Long enough for the witness to speak at all:
        // it wants a window's span behind it before it contradicts anything.
        val carried = (0..30).map { fix(it, it * 14.0) }
        val out = ingest.onFixes(TRACK, carried, walking(), OPEN, SEEN)

        assertTrue("the ground is provably moving", out.motion is Motion.Moving)
        assertEquals(
            "the card says Moving",
            ActivityType.UNKNOWN,
            ingest.displayActivity(ActivityType.WALKING, out.motion),
        )
        // Once the witness has spoken the ceiling fits the ground and the fixes are kept. The
        // opening stretch is not: until the window has a verdict there is only the foot label to
        // judge by, and at this pace it calls them teleports. That warm-up is what the finish-time
        // "Moving" retype hands back, and it is why the flags are re-derived rather than final.
        assertTrue("the crossing is kept once proven", out.points.takeLast(5).none { it.ignored })
        assertTrue("its warm-up is flagged", out.points.take(5).any { it.ignored })
    }

    @Test fun `a brisk walk is not called Moving`() {
        ingest.onTrackOpened(ActivityType.WALKING)

        // 1.6 m/s, ~5.8 km/h. Long enough that the witness speaks at all: a walk has to cover the
        // window's displacement floor before it is heard from, which is the same floor that keeps
        // foot journeys from paying for the cross-check. The point is that it *does* say Moving and
        // the display still declines to rename — the margin the jump ceiling keeps is not the
        // display's to spend.
        val out = ingest.onFixes(TRACK, (0..60).map { fix(it, it * 1.6) }, walking(), OPEN, SEEN)

        assertTrue("the witness is speaking", out.motion is Motion.Moving)
        assertEquals(ActivityType.WALKING, ingest.displayActivity(ActivityType.WALKING, out.motion))
    }

    @Test fun `a proven carried walk finishes as Moving`() {
        ingest.onTrackOpened(ActivityType.WALKING)

        // Sustained carrier pace with the gate holding a STILL the ground contradicts — the shape
        // of a crossing: the body parked on a deck, the deck moving.
        for (t in 0..600 step 10) {
            ingest.onFixes(TRACK, listOf(fix(t, t * 14.0)), walking(stillParked = true), OPEN, SEEN)
        }

        assertEquals(ActivityType.UNKNOWN, ingest.renameFor(ActivityType.WALKING))
    }

    @Test fun `an ordinary walk finishes under its own label`() {
        ingest.onTrackOpened(ActivityType.WALKING)
        for (t in 0..600 step 10) {
            ingest.onFixes(TRACK, listOf(fix(t, t * 1.2)), walking(), OPEN, SEEN)
        }

        assertNull(ingest.renameFor(ActivityType.WALKING))
    }

    @Test fun `a fix with no recent satellite backing is refused when the cross-check asks for one`() {
        ingest.onTrackOpened(ActivityType.WALKING)
        val settings = OPEN.copy(requireGnss = true)

        // The receiver locked once, a minute before this fix was taken. Zero would mean it has never
        // locked this session, which is not the same claim and does not reject anything.
        val stale = GnssState(satellitesInFix = 7, cn0Top4 = 30f, lastFixElapsedMs = 1_000L)
        val out = ingest.onFixes(TRACK, listOf(fix(60, 1.0)), walking(), settings, stale)

        assertEquals(IgnoreReason.NO_GNSS.code, out.points.single().ignoreReason)
    }

    @Test fun `a label outside the foot group is never renamed Moving`() {
        // A drive already carries the ceiling and the group the rename would grant, so substituting
        // it would discard what the label knows rather than add to it. Ground moving at a carrier's
        // pace under a driving label is simply a drive.
        val carried = Motion.Moving(20.0)

        for (label in listOf(ActivityType.DRIVING, ActivityType.CYCLING, ActivityType.FERRY)) {
            assertEquals(label, ingest.displayActivity(label, carried))
        }
    }

    @Test fun `the card's last-fix feedback follows the newest fix`() {
        // What the "waiting for GPS" card reads: a run of rejections is the difference between "no
        // signal yet" and "signal, but too coarse to keep", and only these two fields say which.
        ingest.onTrackOpened(ActivityType.WALKING)

        feed(fix(0, 0.0, accuracy = 8f))
        assertEquals(8f, ingest.lastFixAccuracyM)
        assertFalse(ingest.lastFixRejectedByAccuracy)

        feed(fix(1, 1.0, accuracy = 120f))
        assertEquals(120f, ingest.lastFixAccuracyM)
        assertTrue(ingest.lastFixRejectedByAccuracy)

        feed(fix(2, 2.0, accuracy = 9f))
        assertFalse("a good fix clears it rather than latching", ingest.lastFixRejectedByAccuracy)
    }

    @Test fun `a pending segment break does not outlive the track it belonged to`() {
        // A resume arms the break; if the track then finalizes instead of resuming, the break has
        // nothing left to mark, and carrying it would cut the *next* track's opening fix off its own
        // start.
        ingest.onTrackOpened(ActivityType.WALKING)
        ingest.markSegmentStart()
        ingest.onTrackClosed()

        val out = feed(fix(0, 0.0), fix(1, 1.0))

        assertTrue("nothing marked", out.points.none { it.segmentStart })
    }

    @Test fun `re-windowing the witness keeps what it had measured`() {
        ingest.onTrackOpened(ActivityType.WALKING)
        val carried = ingest.onFixes(TRACK, (0..30).map { fix(it, it * 14.0) }, walking(), OPEN, SEEN)
        assertTrue("the witness had a verdict", carried.motion is Motion.Moving)

        // This path runs on every GPS start, and the recorder restarts GPS on every resume — so
        // emptying here discards the ground's evidence moments before a carrier pulls away. Age
        // expiry is what forgets a genuinely older stretch of the journey.
        ingest.reshapeConfirmer(MovementConfirmer.forSampling(minIntervalSec = 1))

        val after = feed(fix(31, 31 * 14.0))
        assertTrue("and still has it", after.motion is Motion.Moving)
    }

    @Test fun `opening a track forgets the one before it`() {
        ingest.onTrackOpened(ActivityType.WALKING)
        feed(fix(0, 0.0), fix(1, 1.0), fix(2, 2.0))

        ingest.onTrackOpened(ActivityType.WALKING)

        assertEquals(0, ingest.pointCount)
        assertEquals(0.0, ingest.distanceMeters, 0.0)
        assertNull(ingest.lastGood)
    }

    private companion object {
        const val TRACK = 7L
        const val T0 = 1_700_000_000_000L

        /** Gates that admit anything: the quality rules have their own suite. */
        val OPEN = IngestSettings(maxAccuracyM = 50f, requireGnss = false)

        /** A receiver that locked at the moment of each fix — see [Fix.elapsedRealtimeMs]. */
        val SEEN = GnssState(satellitesInFix = 9, cn0Top4 = 32f, lastFixElapsedMs = Long.MAX_VALUE / 2)
    }
}
