package io.github.valeronm.breadcrumb.location

import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Motion
import io.github.valeronm.breadcrumb.domain.Speed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whether a reading is acted on when the ground contradicts it, or cannot vouch for it. */
class HeldReadingTest : ActivityIngestFixture() {

    // --- The ground as a second witness -----------------------------------------

    @Test fun `a stop the ground contradicts is held, not applied`() {
        startWalking()
        // A vehicle's speed under a walking label: the body is still, the journey is not.
        feedVehicleSpeedGround()

        val out = reading(ActivityType.STILL, T0 + 30_000)

        assertEquals(listOf(Effect.StampReading(T0 + 30_000)), out)
        assertEquals("held for later", ActivityType.STILL, core.parked)
        assertTrue("the track keeps recording", core.recording)
    }

    @Test fun `the held stop applies once the ground stops contradicting it`() {
        reading(ActivityType.DRIVING, T0)
        feedVehicleSpeedGround()
        reading(ActivityType.STILL, T0 + 30_000)

        val out = core.onMotion(Motion.Stopped, T0 + 60_000, settings)

        assertNull("the slot is emptied", core.parked)
        assertFalse("and the stop lands, closing the track", core.recording)
        assertTrue(out.any { it is Effect.CloseTrack })
    }

    // --- The crossing the cross-check exists for --------------------------------

    @Test fun `a drive Activity Recognition calls stationary is held, not acted on`() {
        reading(ActivityType.DRIVING, T0)
        driveFrom(T0, until = T0 + MINUTE)

        // Aboard a carrier the body genuinely is still while the journey is not. Acting on the
        // label would end the recording mid-drive and turn GPS off for the rest of it.
        val out = reading(ActivityType.STILL, T0 + MINUTE)

        assertEquals("nothing acted on", listOf(Effect.StampReading(T0 + MINUTE)), out)
        assertEquals("the stop is held rather than dropped", ActivityType.STILL, core.parked)
        assertTrue("the drive is still recording", core.recording)
        assertEquals("under its own label", ActivityType.DRIVING, core.confirmed)
    }

    @Test fun `a crossing costs the drive neither a split nor a close`() {
        reading(ActivityType.DRIVING, T0)
        val out = ArrayList<Effect>()
        // Nine minutes of moving ground, with a stationary announcement every third minute — the
        // edge-triggered stream repeating a stop the ground keeps contradicting.
        for (minute in 1..9) {
            driveFrom(T0 + (minute - 1) * MINUTE + 1000L, until = T0 + minute * MINUTE)
            if (minute % 3 == 0) out += reading(ActivityType.STILL, T0 + minute * MINUTE)
        }

        assertTrue("one track throughout", out.none { it is Effect.OpenTrack || it is Effect.CloseTrack })
        assertTrue(core.recording)
        assertEquals(ActivityType.DRIVING, core.confirmed)
    }

    @Test fun `the drive's real end releases the held stop, timed from the release`() {
        reading(ActivityType.DRIVING, T0)
        driveFrom(T0, until = T0 + MINUTE)
        reading(ActivityType.STILL, T0 + MINUTE)

        // The carrier actually stops, so fixes cease and the window ages out to abstention — which
        // releases the hold, overruling the activity stream taking positive evidence to sustain.
        val arrivedAt = T0 + 3 * MINUTE
        val out = core.onMotion(core.motionVerdict(arrivedAt), arrivedAt, settings)

        assertNull("the slot is emptied", core.parked)
        assertFalse("and the stop finally lands", core.recording)
        // At the drive's last good fix, which is where the carrier came to rest — not at the
        // release, minutes later, which the phone spent parked.
        assertEquals(
            Effect.CloseTrack(endedAt = T0 + MINUTE, renameTo = null),
            out.first { it is Effect.CloseTrack },
        )
    }

    @Test fun `a moment's abstention under a contradicted stop does not end the walk`() {
        startWalking()
        feedVehicleSpeedGround()
        reading(ActivityType.STILL, T0 + 30_000)

        val out = core.onMotion(Motion.Unknown, T0 + 40_000, settings) +
            core.onMotion(Motion.Moving(VEHICLE), T0 + 45_000, settings) +
            core.onMotion(Motion.Moving(VEHICLE), T0 + 40_000 + HOLD_CAP_MS, settings)

        assertTrue(out.none { it is Effect.CloseTrack })
        assertTrue(core.recording)
        assertEquals(ActivityType.STILL, core.parked)
    }

    @Test fun `a hold contradicted past its cap is capped from the last contradiction`() {
        reading(ActivityType.DRIVING, T0)
        feedVehicleSpeedGround()
        reading(ActivityType.STILL, T0 + 30_000)
        val lastMovingAt = T0 + 30_000 + 2 * HOLD_CAP_MS
        core.onMotion(Motion.Moving(VEHICLE), lastMovingAt, settings)

        assertTrue(core.onMotion(Motion.Unknown, lastMovingAt + 1000, settings).isEmpty())
        assertTrue(core.onMotion(Motion.Unknown, lastMovingAt + HOLD_CAP_MS - 1, settings).isEmpty())
        assertTrue(core.onMotion(Motion.Unknown, lastMovingAt + HOLD_CAP_MS, settings).any { it is Effect.CloseTrack })
    }

    @Test fun `a revisit long after the ground fell silent lands the hold at once`() {
        startWalking()
        feedVehicleSpeedGround()
        reading(ActivityType.STILL, T0 + 30_000)

        val out = core.onMotion(Motion.Unknown, T0 + 30_000 + 10 * MINUTE, settings)

        assertTrue(out.any { it is Effect.CloseTrack })
        assertNull(core.parked)
    }

    // --- a stop the ground never vouched for ------------------------------------------------------

    /**
     * The failure this exists for: a carrier pulls away seconds after a resume, the witness's window
     * has not refilled, and a STILL applied on that silence turns GPS off for the rest of the
     * journey. Silence is not consent to stop.
     */
    @Test fun `a stop the ground cannot vouch for is held rather than applied`() {
        startWalking()

        val out = reading(ActivityType.STILL, T0 + MINUTE)

        assertEquals("nothing but the delivery stamp", listOf(Effect.StampReading(T0 + MINUTE)), out)
        assertTrue("the track keeps recording", core.recording)
        assertEquals(ActivityType.STILL, core.parked)
    }

    @Test fun `the cap lands it, closing the track at its last good fix`() {
        startWalking()
        reading(ActivityType.STILL, T0 + MINUTE)

        val out = core.onMotion(Motion.Unknown, T0 + MINUTE + HOLD_CAP_MS, settings)

        // The hold is the recorder waiting, not evidence the stop had yet to begin, so it must not
        // push the track's boundary later than the last thing actually measured.
        assertTrue(out.contains(Effect.CloseTrack(endedAt = T0, renameTo = null)))
        assertFalse(core.recording)
    }

    /**
     * Field shape this exists for: indoors with no fix arriving, Play Services alternates STILL and
     * WALKING every half-minute or so. Each flip used to stop GPS, schedule a wake, re-anchor the
     * departure watch, then start GPS again — dozens of times across one stationary stretch that
     * finished as a discarded track. A return inside the hold restores the activity the gate never
     * left, so the whole cycle is now nothing at all.
     */
    @Test fun `a return inside the hold costs the track nothing`() {
        startWalking()
        reading(ActivityType.STILL, T0 + MINUTE)

        val returnedAt = T0 + MINUTE + HOLD_CAP_MS - 1
        val out = reading(ActivityType.WALKING, returnedAt)

        assertEquals("the delivery stamp and nothing else", listOf(Effect.StampReading(returnedAt)), out)
        assertNull("the held stop is dropped, not left to land later", core.parked)
        assertTrue(core.recording)
    }

    @Test fun `it is not landed a moment early`() {
        startWalking()
        reading(ActivityType.STILL, T0 + MINUTE)

        assertTrue(core.onMotion(Motion.Unknown, T0 + MINUTE + HOLD_CAP_MS - 1, settings).isEmpty())
        assertTrue(core.recording)
    }

    /** The verdict the cap exists to wait for — and the one job [Motion.Stopped] has. */
    @Test fun `ground that confirms the standstill lands it at once`() {
        startWalking()
        reading(ActivityType.STILL, T0 + MINUTE)

        val out = core.onMotion(Motion.Stopped, T0 + MINUTE + 1, settings)

        assertTrue(out.contains(Effect.StopGps))
        assertFalse(core.recording)
    }

    @Test fun `a contradicted hold is not subject to the cap`() {
        reading(ActivityType.DRIVING, T0)
        feedVehicleSpeedGround()
        reading(ActivityType.STILL, T0 + 30_000)
        assertEquals(ActivityType.STILL, core.parked)

        val tooSoonToBeSilent = core.onMotion(Motion.Moving(VEHICLE), T0 + 30_000 + HOLD_CAP_MS, settings)

        assertTrue("the ground is still moving, cap or no cap", tooSoonToBeSilent.isEmpty())
        assertEquals(ActivityType.STILL, core.parked)
    }

    // --- a foot reading at carrier speed ----------------------------------------------------------
    //
    // Every speed here is a motorway speed, because that is the whole of what the rule covers — the
    // bar and why it cannot come down are argued once, in [ActivityGate.tooFastFor].

    @Test fun `a walking reading at motorway speed does not split the drive`() {
        reading(ActivityType.DRIVING, T0)
        feedVehicleSpeedGround()

        val out = reading(ActivityType.WALKING, T0 + 30_000)

        assertTrue("nothing closed", out.none { it is Effect.CloseTrack })
        assertTrue("and nothing opened", out.none { it is Effect.OpenTrack })
        assertEquals(ActivityType.WALKING, core.parked)
        assertEquals(ActivityType.DRIVING, core.confirmed)
    }

    /** …and the drive continues through the vehicle reading that follows, as one track. */
    @Test fun `the drive that jittered is still one track when the vehicle reading returns`() {
        reading(ActivityType.DRIVING, T0)
        feedVehicleSpeedGround()
        reading(ActivityType.WALKING, T0 + 30_000)

        val out = reading(ActivityType.DRIVING, T0 + 60_000)

        assertTrue("no split", out.none { it is Effect.CloseTrack || it is Effect.OpenTrack })
        assertNull("and the stale foot reading is dropped, not left to land later", core.parked)
    }

    /**
     * The bound on the hold, and the answer to the objection that killed this rule before: the
     * walk that follows every drive is delayed only until the trailing window slows to a human
     * pace, not until it falls silent.
     */
    @Test fun `the held walk lands as soon as the ground slows to a human pace`() {
        reading(ActivityType.DRIVING, T0)
        feedVehicleSpeedGround()
        reading(ActivityType.WALKING, T0 + 30_000)

        val out = core.onMotion(Motion.Moving(WALKING), T0 + 40_000, settings)

        assertTrue("the walk opens its own track", out.any { it is Effect.OpenTrack })
        assertEquals(ActivityType.WALKING, core.confirmed)
    }

    @Test fun `a walk beginning from a standstill is never delayed`() {
        val out = reading(ActivityType.WALKING, T0)

        assertTrue(out.any { it is Effect.OpenTrack })
        assertNull(core.parked)
    }

    // --- …and where the rule deliberately does not reach -------------------------------------------

    /**
     * **The urban split stands, and this pins that it does** — not an oversight, and not fixable by
     * lowering the bar; see [ActivityGate.tooFastFor] for why the ground cannot tell this reading
     * from the genuine drive-to-walk that ends every trip. Repaired afterwards by merging, which is
     * visible and undoable.
     */
    @Test fun `a walking reading at a crawl still splits the drive, as it must`() {
        reading(ActivityType.DRIVING, T0)
        groundAt(CRAWL, T0, until = T0 + 30_000)

        val out = reading(ActivityType.WALKING, T0 + 30_000)

        assertNull("nothing is held", core.parked)
        assertTrue("the drive is closed", out.any { it is Effect.CloseTrack })
        assertTrue("and a foot track opened in its place", out.any { it is Effect.OpenTrack })
    }

    private companion object {
        /** A car in city traffic — under the foot rule's bar, and the speed a real drive-to-walk
         *  arrives at, which is why nothing may be held there. */
        val CRAWL = Speed.kmh(22.0)
    }
}
