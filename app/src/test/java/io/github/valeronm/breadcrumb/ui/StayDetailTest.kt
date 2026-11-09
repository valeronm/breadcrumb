package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.domain.Clocks
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.HiddenStay
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.ResolvedStayInterval
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TrackMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset

class StayDetailTest {
    private fun stay(
        afterId: Long? = null,
        place: PlaceResolver.ResolvedStay? = null,
        hideableHere: List<HiddenStay> = emptyList(),
        merge: TrackMerge.Plan? = null,
        end: Long? = null,
    ) = ResolvedStayInterval(
        stay = StayDeriver.Stay(0L, end, afterTrackId = BEFORE, clusterId = 0),
        place = place,
        clocks = Clocks.both(ZoneOffset.UTC),
        hide = afterId?.let { HiddenStay(BEFORE, it) },
        arrival = Coordinate(0.0, 0.0),
        departure = null,
        hideableHere = hideableHere,
        merge = merge,
    )

    private fun claim(afterId: Long?, recording: StayClaim.Recording?, end: Long? = STAY_END) =
        StayClaim.of(stay(afterId, end = end), recording)

    private fun detailAt(
        place: PlaceResolver.ResolvedStay?,
        afterId: Long? = null,
        hideableHere: List<HiddenStay> = emptyList(),
        merge: TrackMerge.Plan? = null,
    ): StayDetail {
        val resolved = stay(afterId, place, hideableHere, merge)
        return StayDetail(resolved, emptyList(), placeDistanceM = null, claim = StayClaim.of(resolved, null))
    }

    private val unnamedStop = GapFixtures.side(place = null, visits = 3)

    private val named = GapFixtures.side(GapFixtures.place(id = 7, label = "Home"), visits = 3)

    @Test fun `a stay with a finished track after claims that track's start`() {
        val claim = claim(afterId = 2, recording = StayClaim.Recording(3, STAY_END))
        assertEquals(BEFORE, claim.beforeId)
        assertEquals(2L, claim.afterId)
    }

    @Test fun `the stay ending where an open recording starts claims the recording's start`() {
        assertEquals(3L, claim(afterId = null, recording = StayClaim.Recording(3, STAY_END)).afterId)
    }

    @Test fun `a stay ending elsewhere than the recording's start claims the arrival alone`() {
        assertNull(claim(afterId = null, recording = StayClaim.Recording(3, STAY_END + 1)).afterId)
    }

    @Test fun `a stay still going on is not claimed by a recording`() {
        assertNull(claim(afterId = null, recording = StayClaim.Recording(3, STAY_END), end = null).afterId)
    }

    @Test fun `the stay going on now with nothing recording claims the arrival alone`() {
        assertNull(claim(afterId = null, recording = null, end = null).afterId)
    }

    @Test fun `a recording that reopened the arriving track is not claimed`() {
        assertNull(claim(afterId = null, recording = StayClaim.Recording(BEFORE, STAY_END)).afterId)
    }

    @Test fun `a stay at a stop nobody named offers to name that stop`() {
        val stop = GapFixtures.side(place = null, visits = 3)
        assertEquals(stop.key, detailAt(stop).unnamedSpot?.key)
    }

    @Test fun `a stay at a place row without a name offers to name that row`() {
        val row = GapFixtures.side(GapFixtures.place(id = 7, label = null), visits = 3)
        assertEquals(PlaceResolver.keyOf(7), detailAt(row).unnamedSpot?.key)
    }

    @Test fun `a stay at a named place offers no naming`() {
        assertNull(detailAt(GapFixtures.side(GapFixtures.place(id = 7, label = "Home"), visits = 3)).unnamedSpot)
    }

    @Test fun `a stay at no place offers no naming`() {
        assertNull(detailAt(null).unnamedSpot)
    }

    private val plan = TrackMerge.Plan(BEFORE, 2)

    private val hide = StayAction.Hide(HiddenStay(BEFORE, 2))

    private val severalHere = listOf(HiddenStay(BEFORE, 2), HiddenStay(3, 4))

    @Test fun `a finished stay at an unnamed spot offers to set its place, then to hide it`() {
        assertEquals(listOf(StayAction.SetPlace, hide), detailAt(unnamedStop, afterId = 2).actions)
    }

    @Test fun `an unnamed spot leads with the merge where one is planned`() {
        assertEquals(listOf(StayAction.Merge(plan), StayAction.SetPlace, hide), detailAt(unnamedStop, afterId = 2, merge = plan).actions)
    }

    @Test fun `the stay going on now offers only to set its place`() {
        assertEquals(listOf(StayAction.SetPlace), detailAt(unnamedStop, afterId = null).actions)
    }

    @Test fun `an unnamed spot holding several finished stays offers to hide them all, last`() {
        assertEquals(
            listOf(StayAction.SetPlace, hide, StayAction.HideAll(severalHere)),
            detailAt(unnamedStop, afterId = 2, hideableHere = severalHere).actions,
        )
    }

    @Test fun `an unnamed spot holding one finished stay offers only its own hide`() {
        assertEquals(listOf(StayAction.SetPlace, hide), detailAt(unnamedStop, afterId = 2, hideableHere = listOf(HiddenStay(BEFORE, 2))).actions)
    }

    @Test fun `a named place leads with changing the place, then the merge and the hide, not every stay there`() {
        assertEquals(
            listOf(StayAction.ChangePlace, StayAction.Merge(plan), hide),
            detailAt(named, afterId = 2, hideableHere = severalHere, merge = plan).actions,
        )
    }

    @Test fun `a named place with no merge and no track after offers only to change the place`() {
        assertEquals(listOf(StayAction.ChangePlace), detailAt(named, afterId = null, hideableHere = severalHere).actions)
    }

    @Test fun `a stay at no place is offered as a named place's is`() {
        assertEquals(listOf(StayAction.ChangePlace, StayAction.Merge(plan), hide), detailAt(null, afterId = 2, merge = plan).actions)
    }

    private companion object {
        const val BEFORE = 1L
        const val STAY_END = 60_000L
    }
}
