package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.domain.GapDiagnosis.Fix
import io.github.valeronm.breadcrumb.domain.GapDiagnosis.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GapDiagnosisTest {

    private val before = trackSummary(1, "DRIVING", startedAt = 0, endedAt = 10 * MIN)
    private val after = trackSummary(2, "DRIVING", startedAt = 70 * MIN, endedAt = 80 * MIN)

    private fun gap(apartM: Double?, minutes: Long = 60, fromKnown: Boolean = true) = StayDeriver.Gap(
        start = 10 * MIN,
        end = 10 * MIN + minutes * MIN,
        reason = if (apartM == null || !fromKnown) StayDeriver.GapReason.UNKNOWN_ENDPOINT else StayDeriver.GapReason.MOVED_UNRECORDED,
        afterTrackId = 1,
        from = if (fromKnown) at(0.0) else null,
        to = apartM?.let(::at),
    )

    private fun diagnose(
        gap: StayDeriver.Gap,
        b: TrackSummary = before,
        a: TrackSummary = after,
    ) = GapDiagnosis.diagnose(gap, b, a, flatDistance)

    @Test fun `ends within the stayed-here distance suggest a stay`() {
        val v = diagnose(gap(apartM = 150.0))
        assertEquals(Fix.STAYED_HERE, v.suggested)
        assertEquals(Reason.ENDS_CLOSE, v.reason)
        assertEquals(listOf(Fix.MISSED_START, Fix.ADD_TRIP, Fix.MERGE), v.others)
    }

    @Test fun `the stayed-here distance is inclusive`() {
        // flatDistance rounds 200 m to a hair over 200, which would test the float, not the bound.
        val exact = DistanceFn { _, _, _, _ -> 200.0 }
        assertEquals(Fix.STAYED_HERE, GapDiagnosis.diagnose(gap(apartM = 200.0), before, after, exact).suggested)
    }

    @Test fun `a slow gap before a movement suggests the missed start`() {
        val v = diagnose(gap(apartM = 450.0))
        assertEquals(Fix.MISSED_START, v.suggested)
        assertEquals(Reason.PARKED_THEN_LEFT, v.reason)
        assertEquals(listOf(Fix.ADD_TRIP, Fix.MERGE), v.others)
    }

    @Test fun `a slow gap before a still track does not suggest the missed start`() {
        val still = trackSummary(2, "STILL", startedAt = 70 * MIN, endedAt = 80 * MIN)
        val v = diagnose(gap(apartM = 450.0), a = still)
        assertEquals(Fix.ADD_TRIP, v.suggested)
        assertFalse(Fix.MISSED_START in v.others)
    }

    @Test fun `a fast gap between compatible tracks suggests a merge`() {
        val v = diagnose(gap(apartM = 12_000.0, minutes = 10))
        assertEquals(Fix.MERGE, v.suggested)
        assertEquals(Reason.DROPOUT, v.reason)
        assertEquals(listOf(Fix.ADD_TRIP), v.others)
    }

    @Test fun `a fast gap between different activities suggests adding the trip`() {
        val walk = trackSummary(2, "WALKING", startedAt = 20 * MIN, endedAt = 30 * MIN)
        val v = diagnose(gap(apartM = 12_000.0, minutes = 10), a = walk)
        assertEquals(Fix.ADD_TRIP, v.suggested)
        assertEquals(Reason.TRAVELLED, v.reason)
        assertEquals(emptyList<Fix>(), v.others)
    }

    @Test fun `a side with no fix never offers a stay or the missed start`() {
        val v = diagnose(gap(apartM = 50.0, fromKnown = false))
        assertEquals(Fix.MERGE, v.suggested)
        assertEquals(Reason.NO_POSITION, v.reason)
        assertEquals(listOf(Fix.ADD_TRIP), v.others)
    }

    @Test fun `a side with no fix between incompatible tracks suggests adding the trip`() {
        val walk = trackSummary(2, "WALKING", startedAt = 70 * MIN, endedAt = 80 * MIN)
        val v = diagnose(gap(apartM = 50.0, fromKnown = false), a = walk)
        assertEquals(Fix.ADD_TRIP, v.suggested)
        assertEquals(Reason.NO_POSITION, v.reason)
    }

    @Test fun `the speed is a floor over the straight line`() {
        val v = diagnose(gap(apartM = 600.0, minutes = 10))
        assertEquals(1.0, v.atLeastMps!!, 1e-9)
    }

    @Test fun `an instant gap has no speed`() {
        val v = diagnose(gap(apartM = 450.0, minutes = 0))
        assertNull(v.atLeastMps)
        assertFalse(v.suggested == Fix.MISSED_START)
    }
}
