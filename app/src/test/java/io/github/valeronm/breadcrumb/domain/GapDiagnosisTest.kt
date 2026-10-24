package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.domain.GapDiagnosis.Block
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
        afterTrackId = 1,
        from = if (fromKnown) StayDeriver.Gap.Side(at(0.0), clusterId = 0) else null,
        to = apartM?.let { StayDeriver.Gap.Side(at(it), clusterId = 1) },
    )

    private fun diagnose(
        gap: StayDeriver.Gap,
        b: TrackSummary = before,
        a: TrackSummary = after,
        fromNamed: Boolean = false,
        toNamed: Boolean = false,
    ) = GapDiagnosis.diagnose(gap, TrackMerge.Neighbors(b, a), fromNamed, toNamed, flatDistance)

    @Test fun `ends within the stayed-here distance at a named place suggest a stay`() {
        val v = diagnose(gap(apartM = 150.0), fromNamed = true)
        assertEquals(Fix.STAYED_HERE, v.suggested)
        assertEquals(Reason.ENDS_CLOSE, v.reason)
        assertEquals(listOf(Fix.MISSED_START, Fix.ADD_TRIP, Fix.MERGE), v.others)
        assertEquals(emptyMap<Fix, Block>(), v.blocked)
    }

    @Test fun `the stayed-here distance is inclusive`() {
        // flatDistance rounds 200 m to a hair over 200, which would test the float, not the bound.
        val exact = DistanceFn { _, _, _, _ -> 200.0 }
        assertEquals(Fix.STAYED_HERE, GapDiagnosis.diagnose(gap(apartM = 200.0), TrackMerge.Neighbors(before, after), fromNamed = true, toNamed = false, distance = exact).suggested)
    }

    @Test fun `a slow gap before a movement suggests the missed start`() {
        val v = diagnose(gap(apartM = 450.0))
        assertEquals(Fix.MISSED_START, v.suggested)
        assertEquals(Reason.PARKED_THEN_LEFT, v.reason)
        assertEquals(listOf(Fix.ADD_TRIP, Fix.STAYED_HERE, Fix.MERGE), v.others)
        assertEquals(mapOf(Fix.STAYED_HERE to Block.STAY_NO_NAMED_PLACE), v.blocked)
    }

    @Test fun `a fast gap between compatible tracks suggests a merge`() {
        val v = diagnose(gap(apartM = 12_000.0, minutes = 10))
        assertEquals(Fix.MERGE, v.suggested)
        assertEquals(Reason.DROPOUT, v.reason)
        assertEquals(mapOf(Fix.STAYED_HERE to Block.STAY_TOO_FAR), v.blocked)
    }

    @Test fun `a fast gap between different activities suggests adding the trip`() {
        val walk = trackSummary(2, "WALKING", startedAt = 20 * MIN, endedAt = 30 * MIN)
        val v = diagnose(gap(apartM = 12_000.0, minutes = 10), a = walk)
        assertEquals(Fix.ADD_TRIP, v.suggested)
        assertEquals(Reason.TRAVELLED, v.reason)
        assertEquals(
            mapOf(Fix.MERGE to Block.MERGE_ACTIVITIES_DIFFER, Fix.STAYED_HERE to Block.STAY_TOO_FAR),
            v.blocked,
        )
    }

    @Test fun `a side with no fix rules out a stay on that side`() {
        val v = diagnose(gap(apartM = 50.0, fromKnown = false), fromNamed = true)
        assertEquals(Fix.MERGE, v.suggested)
        assertEquals(Reason.NO_POSITION, v.reason)
        assertEquals(mapOf(Fix.STAYED_HERE to Block.STAY_NO_POINT_BEFORE), v.blocked)
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

    @Test fun `close ends between unnamed places offer no stay`() {
        val v = diagnose(gap(apartM = 150.0))
        assertEquals(Fix.MISSED_START, v.suggested)
        assertEquals(Block.STAY_NO_NAMED_PLACE, v.blocked[Fix.STAYED_HERE])
    }

    @Test fun `a slow gap at a named place also offers a stay`() {
        val v = diagnose(gap(apartM = 450.0), fromNamed = true)
        assertEquals(Fix.MISSED_START, v.suggested)
        assertEquals(listOf(Fix.ADD_TRIP, Fix.STAYED_HERE, Fix.MERGE), v.others)
        assertEquals(emptyMap<Fix, Block>(), v.blocked)
    }

    @Test fun `trips of different writers name the writers whatever their activities`() {
        val imported = trackSummary(2, "WALKING", startedAt = 70 * MIN, endedAt = 80 * MIN, source = TrackOrigin.IMPORTED.code)
        assertEquals(Block.MERGE_WRITERS_DIFFER, diagnose(gap(apartM = 450.0), a = imported).blocked[Fix.MERGE])
    }

    @Test fun `a far gap names the distance even with no named place`() {
        assertEquals(Block.STAY_TOO_FAR, diagnose(gap(apartM = 12_000.0, minutes = 10)).blocked[Fix.STAYED_HERE])
    }

    @Test fun `a fast gap at a named place rules out a stay as too far`() {
        val v = diagnose(gap(apartM = 450.0, minutes = 1), fromNamed = true)
        assertEquals(Block.STAY_TOO_FAR, v.blocked[Fix.STAYED_HERE])
    }

    @Test fun `a slow gap before a track starting at a named place suggests the whole trip`() {
        val v = diagnose(gap(apartM = 450.0), toNamed = true)
        assertEquals(Fix.ADD_TRIP, v.suggested)
        assertEquals(Reason.NEXT_AT_NAMED_PLACE, v.reason)
        assertEquals(listOf(Fix.MISSED_START, Fix.STAYED_HERE, Fix.MERGE), v.others)
    }

    @Test fun `a slow gap from a named place to an unnamed one still suggests the missed start`() {
        assertEquals(Fix.MISSED_START, diagnose(gap(apartM = 450.0), fromNamed = true).suggested)
    }
}
