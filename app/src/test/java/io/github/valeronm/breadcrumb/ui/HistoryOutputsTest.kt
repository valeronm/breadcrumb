package io.github.valeronm.breadcrumb.ui

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.data.DerivedConsistency
import io.github.valeronm.breadcrumb.data.PlaceRepository
import io.github.valeronm.breadcrumb.data.SETTLE_MS
import io.github.valeronm.breadcrumb.data.TestDb
import io.github.valeronm.breadcrumb.data.TrackRepository
import io.github.valeronm.breadcrumb.data.awaitFirst
import io.github.valeronm.breadcrumb.data.awaitQuiet
import io.github.valeronm.breadcrumb.data.awaitUntil
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.data.observe
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Clocks
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.MIN
import io.github.valeronm.breadcrumb.domain.PlaceCategory
import io.github.valeronm.breadcrumb.domain.PlaceCircle
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.TimelineItem
import io.github.valeronm.breadcrumb.domain.TravelDeriver
import io.github.valeronm.breadcrumb.domain.Trip
import io.github.valeronm.breadcrumb.domain.atlas.TestCity
import io.github.valeronm.breadcrumb.domain.atlas.atlasOf
import io.github.valeronm.breadcrumb.domain.atlas.localNamesOf
import io.github.valeronm.breadcrumb.domain.flatDistance
import io.github.valeronm.breadcrumb.domain.trackSummary
import io.github.valeronm.breadcrumb.history.CityLookup
import io.github.valeronm.breadcrumb.location.TrackingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryOutputsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val test = TestDb()
    private val places = PlaceRepository(app, test.db)

    // Robolectric runs each test on the main looper's thread, which runBlocking holds.
    private val main = Dispatchers.Default.limitedParallelism(1)
    private val collectors = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val store = ViewModelStore()

    private val homeZone = ZoneId.of("Etc/GMT-3")
    private val awayZone = ZoneId.of("Etc/GMT+5")
    private val atlas = atlasOf(
        TestCity(1.0, LINE_LON, 50, "XX", homeZone.id, "Fixture Town"),
        TestCity(1.0, LINE_LON + AWAY_OFFSET, 50, "XX", awayZone.id, "Other Town"),
    )

    private val now = System.currentTimeMillis()

    // The trailing stay after the last walk runs to now.
    private val t0 = now - 12 * HOUR

    private val models = mutableListOf<TrackListViewModel>()
    private lateinit var viewModel: TrackListViewModel

    private fun model(key: String, factory: ViewModelProvider.Factory) =
        ViewModelProvider(store, factory)[key, TrackListViewModel::class.java].also(models::add)

    private fun model(key: String, cityLookup: CityLookup) =
        model(key, viewModelFactory { initializer { TrackListViewModel(app, test.db, cityLookup) } })

    @Before fun setUp() {
        Dispatchers.setMain(main)
        test.keepEveryTrack()
        viewModel = model("shown") { at, _ -> atlas.naming(at.lat, at.lon, flatDistance) }
    }

    @After fun tearDown() {
        val unwinding = models.map { it.viewModelScope.coroutineContext.job } + collectors.coroutineContext.job
        collectors.cancel()
        store.clear()
        // A cancelled coroutine still reads Dispatchers.Main while it unwinds.
        runBlocking { awaitUntil { unwinding.all { it.isCompleted } } }
        Dispatchers.resetMain()
        TrackingStatus.reset()
        test.close()
    }

    @Test fun `the default view-model factory still builds the screen model`() {
        model("default", ViewModelProvider.AndroidViewModelFactory.getInstance(app))
    }

    @Test fun `every history output is null until the first derivation, then empty for an empty history`() = runBlocking {
        assertNull(viewModel.timeline.value)
        assertNull(viewModel.places.value)
        assertNull(viewModel.travels.value)
        assertNull(viewModel.world.value)
        assertNull(viewModel.monthlyTotals.value)
        assertNull(viewModel.missingRecordings.value)

        assertEquals(emptyList<TimelineItem>(), loaded(viewModel.timeline))
        assertEquals(0, loaded(viewModel.places).size)
        assertEquals(0, loaded(viewModel.travels).size)
        assertEquals(0, loaded(viewModel.world).size)
        assertEquals(0, loaded(viewModel.monthlyTotals).size)
        assertEquals(0, loaded(viewModel.missingRecordings).count)
    }

    @Test fun `the timeline shows the stay with its place and zone, and the gap`() = runBlocking {
        val walks = history()

        val rows = loaded(viewModel.timeline) { rows -> rows.any { it is TimelineItem.GapItem } }
        assertEquals(setOf(walks.out, walks.back, walks.across), rows.flatMap { it.trackIds }.toSet())
        val cafeStay = rows.filterIsInstance<TimelineItem.StayItem>().filter { it.stay.afterTrackId == walks.out }
        assertTrue(cafeStay.isNotEmpty())
        assertTrue(cafeStay.all { it.place?.place?.label == "Cafe" && it.zone == homeZone })
        assertEquals(listOf(walks.back), rows.filterIsInstance<TimelineItem.GapItem>().map { it.gap.afterTrackId }.distinct())
        assertDerivationConsistent()
    }

    @Test fun `a visited place reaches the places list with its locality`() = runBlocking {
        history()

        val cafe = loaded(viewModel.places) { it.named("Cafe").isNotEmpty() }.named("Cafe").single()
        assertEquals(1, cafe.visitCount)
        assertEquals("Fixture Town", cafe.locality?.name)
    }

    @Test fun `a gap reaches the missing recordings and its detail reads the zone at each end`() = runBlocking {
        val walks = history()

        assertEquals(1, loaded(viewModel.missingRecordings) { it.count > 0 }.count)
        viewModel.hasMissingRecordings.awaitFirst { it }

        val detail = viewModel.gapDetail(walks.back).awaitFirst() as GapDetail
        assertEquals(walks.back, detail.before.id)
        assertEquals(walks.across, detail.after.id)
        assertEquals(homeZone, detail.departureZone)
        assertEquals(awayZone, detail.arrivalZone)
    }

    @Test fun `the world lists the country and the cities visited`() = runBlocking {
        history()

        val world = loaded(viewModel.world) { it.isNotEmpty() }
        assertEquals(listOf("XX"), world.map { it.code })
        assertEquals(setOf("Fixture Town", "Other Town"), world.single().cities.map { it.city.name }.toSet())
    }

    @Test fun `the monthly totals add up the distance walked`() = runBlocking {
        history()

        val walked = settledTracks().sumOf { it.distanceMeters }
        assertTrue(walked > 0.0)
        val monthly = loaded(viewModel.monthlyTotals) { it.isNotEmpty() }
            .sumOf { it.totals.activities[ActivityType.WALKING.name]?.meters ?: 0.0 }
        assertEquals(walked, monthly, 0.5)
    }

    @Test fun `nights away from home are one journey, named from the atlas`() = runBlocking {
        val offset = TravelDeriver.solarOffsetOf(LINE_LON)
        // The trailing stay at home runs to now.
        val base = LocalDate.now(offset).minusDays(7)
        fun at(day: Long, hour: Int) = base.plusDays(day).atTime(hour, 0).toInstant(offset).toEpochMilli()
        // Wide enough to hold both ends of a walk along the fixture's line.
        places.create(test.place("Home", 1.0025, LINE_LON, radiusM = 500.0).copy(category = PlaceCategory.HOME.code))
        test.walk(at(0, 10), 5, 0)
        test.walk(at(1, 9), 0, 5, lonOffset = AWAY_OFFSET)
        test.walk(at(3, 9), 5, 0, lonOffset = AWAY_OFFSET)
        test.walk(at(4, 9), 5, 0)

        val journey = loaded(viewModel.travels) { it.isNotEmpty() }.single()
        assertEquals(base.plusDays(2), journey.travel.firstNight)
        assertEquals(base.plusDays(3), journey.travel.lastNight)
        assertEquals(listOf("Other Town"), journey.destinations)
        assertEquals(setOf("XX"), journey.countries)
        assertDerivationConsistent()
    }

    @Test fun `finishing a track while the timeline is observed adds its row`() = runBlocking {
        val walks = history()
        val rows = collectors.observe(viewModel.timeline.filterNotNull())
        rows.awaitLast { it.shows(walks.across) }

        val later = test.walk(t0 + 6 * HOUR, 5, 0, lonOffset = AWAY_OFFSET)
        rows.awaitLast { it.shows(later) }
        assertDerivationConsistent()
    }

    @Test fun `deleting a track while observed takes its rows and its gap away`() = runBlocking {
        val walks = history()
        val rows = collectors.observe(viewModel.timeline.filterNotNull())
        val detail = collectors.observe(viewModel.gapDetail(walks.back))
        rows.awaitLast { it.shows(walks.across) }
        detail.awaitLast { it is GapDetail }
        assertTrue(rows.last().hasGapAfter(walks.back))

        val deleted = AtomicBoolean()
        viewModel.delete(listOf(walks.across)) { deleted.set(true) }
        rows.awaitLast { deleted.get() && !it.shows(walks.across) }
        detail.awaitLast { it == GapState.Gone }
        assertFalse(rows.last().hasGapAfter(walks.back))
        assertDerivationConsistent()
    }

    @Test fun `a gap new since a gap page was last open opens on its detail, not gone`() = runBlocking {
        val walks = history()
        val firstVisit = collectors.launch { viewModel.gapDetail(walks.back).collect {} }
        viewModel.gapDetail(walks.back).awaitFirst { it is GapDetail }
        firstVisit.cancelAndJoin()

        val rows = collectors.observe(viewModel.timeline.filterNotNull())
        test.walk(t0 + 6 * HOUR, 5, 0)
        rows.awaitLast { it.hasGapAfter(walks.across) }
        awaitQuiet { rows.size }

        assertTrue(viewModel.gapDetail(walks.across).first() is GapDetail)
    }

    @Test fun `a second manual save while one runs writes nothing`() = runBlocking {
        val results = CopyOnWriteArrayList<TrackRepository.ManualTrackResult>()
        viewModel.saveManualTrack(null, manualTrip(t0), results::add)
        viewModel.saveManualTrack(null, manualTrip(t0 + 3 * HOUR), results::add)
        delay(SETTLE_MS)

        assertEquals(1, results.size)
        assertFalse(viewModel.savingManualTrack.value)
    }

    @Test fun `a refused manual save lets the next one run`() = runBlocking {
        history()
        val refused = AtomicBoolean()
        viewModel.saveManualTrack(null, manualTrip(t0)) { refused.set(it is TrackRepository.ManualTrackResult.Overlapping) }
        viewModel.savingManualTrack.awaitFirst { !it }
        val saved = AtomicBoolean()
        viewModel.saveManualTrack(null, manualTrip(t0 + 8 * HOUR)) { saved.set(it is TrackRepository.ManualTrackResult.Saved) }
        awaitUntil { saved.get() }

        assertTrue(refused.get())
    }

    @Test fun `a place named while observed keeps its name from the first list that shows it`() = runBlocking {
        history()
        val summaries = collectors.observe(viewModel.places.filterNotNull())
        val pending = collectors.observe(viewModel.pendingPlaceRow)
        val unnamedVisit = { summary: PlaceResolver.PlaceSummary -> summary.place == null && summary.visitCount > 0 }
        summaries.awaitLast { it.any(unnamedVisit) }
        val unnamed = summaries.last().first(unnamedVisit)

        viewModel.createPlace(unnamed, "Home", PlaceCircle(unnamed.anchor, unnamed.radiusM), stayClaim = null) {}
        awaitUntil { pending.any { it != null } }
        awaitUntil { pending.last() == null }

        val first = summaries.indexOfFirst { it.named("Home").isNotEmpty() }
        assertTrue("the name showed", first >= 0)
        assertTrue("and never went away", summaries.drop(first).all { it.named("Home").isNotEmpty() })
        assertTrue("the row has its id", summaries.last().named("Home").single().place!!.id != 0L)
        assertDerivationConsistent()
    }

    @Test fun `a place named at a gap's side shows on the gap screens before its derivation, and stays`() = runBlocking {
        val walks = history()
        val details = collectors.observe(viewModel.gapDetail(walks.back))
        val gaps = collectors.observe(viewModel.missingRecordings.filterNotNull())
        val pending = collectors.observe(viewModel.pendingPlaceRow)
        details.awaitLast { it is GapDetail }
        gaps.awaitLast { it.count > 0 }
        val side = (details.last() as GapDetail).fromPlace!!
        val unnamed = loaded(viewModel.places) { list -> list.any { it.key == side.key } }.first { it.key == side.key }
        val createdId = AtomicLong()

        viewModel.createPlace(unnamed, "Home", PlaceCircle(unnamed.anchor, unnamed.radiusM), stayClaim = null) { createdId.set(it) }
        awaitUntil { pending.any { it != null } }
        awaitUntil { pending.last() == null }
        details.awaitLast { it.departure().isHome() }
        gaps.awaitLast { it.departure(walks.back).isHome() }
        awaitQuiet { details.size + gaps.size }

        val onDetail = details.firstKept("the name on the detail") { it.departure().isHome() }
        val onGaps = gaps.firstKept("the name on the missing recordings") { it.departure(walks.back).isHome() }
        assertEquals("on the detail before the derivation", side.key, onDetail.departure()?.key)
        assertEquals("on the missing recordings before it", side.key, onGaps.departure(walks.back)?.key)
        assertEquals("the derived detail", PlaceResolver.keyOf(createdId.get()), details.last().departure()?.key)
        assertEquals("the derived gaps", PlaceResolver.keyOf(createdId.get()), gaps.last().departure(walks.back)?.key)
        assertDerivationConsistent()
    }

    @Test fun `a status update that moves only the live figures derives nothing`() = runBlocking {
        history()
        val months = collectors.observe(viewModel.monthlyTotals.filterNotNull())
        TrackingStatus.update { it.copy(tracking = true) }
        months.awaitLast { it.isNotEmpty() }
        awaitQuiet(months::size)
        val settled = viewModel.monthlyTotals.value

        repeat(10) { i -> TrackingStatus.update { it.copy(points = i, distanceMeters = i * 10.0, speedMps = 1.4f) } }
        delay(SETTLE_MS)
        // The items are plain classes, so a derivation run again shows as a new list.
        assertSame(settled, viewModel.monthlyTotals.value)
    }

    @Test fun `a track and a trip read the atlas zone at their ends`() = runBlocking {
        val walks = history()
        val summaries = settledTracks()
        fun trip(id: Long) = Trip(summaries.single { it.id == id })
        assertEquals(Clocks.both(homeZone), viewModel.clocksOfTrip(trip(walks.out)).ends)
        assertEquals(Clocks.both(awayZone), viewModel.clocksOfTrip(trip(walks.across)).ends)
        assertEquals(
            Clocks.both(ZoneId.systemDefault()),
            viewModel.clocksOfTrip(Trip(trackSummary(-1L, ActivityType.WALKING.name, 0, MIN))).ends,
        )
    }

    @Test fun `a language the screens hand over names the places in it`() = runBlocking<Unit> {
        history()
        val german = atlas.withNames(localNamesOf(2, mapOf(0 to "Fixture Stadt")))
        val named = model("language") { at, language ->
            (if (language == "de") german else atlas).naming(at.lat, at.lon, flatDistance)
        }
        fun cafeTown(list: List<PlaceResolver.PlaceSummary>) = list.named("Cafe").singleOrNull()?.locality?.name

        named.useLanguage("en")
        loaded(named.places) { cafeTown(it) == "Fixture Town" }
        named.useLanguage("de")
        loaded(named.places) { cafeTown(it) == "Fixture Stadt" }
    }

    private suspend fun <T : Any> loaded(flow: StateFlow<T?>, until: (T) -> Boolean = { true }): T =
        flow.filterNotNull().awaitFirst(until)

    private suspend fun settledTracks() = viewModel.tracks.awaitFirst { it.size == WALKS }

    private suspend fun assertDerivationConsistent() = DerivedConsistency.assertMatchesFreshDerive(test.db, now)

    private suspend fun <T> List<T>.awaitLast(condition: (T) -> Boolean) =
        awaitUntil { lastOrNull()?.let(condition) == true }

    private fun manualTrip(startMs: Long) = TrackListViewModel.ManualTrip(
        ActivityType.WALKING,
        TrackListViewModel.ManualTripEnd(TrackRepository.ManualEnd(Coordinate(1.0, LINE_LON), startMs), null),
        TrackListViewModel.ManualTripEnd(TrackRepository.ManualEnd(Coordinate(1.01, LINE_LON), startMs + HOUR / 2), null),
        emptyList(),
    )

    private class Walks(val out: Long, val back: Long, val across: Long)

    private suspend fun history(): Walks {
        places.create(test.place("Cafe", 1.005, LINE_LON))
        val out = test.walk(t0, 0, 5)
        val back = test.walk(t0 + 2 * HOUR, 5, 0)
        val across = test.walk(t0 + 4 * HOUR, 0, 5, lonOffset = AWAY_OFFSET)
        return Walks(out, back, across)
    }

    private fun List<TimelineItem>.shows(trackId: Long) = any { trackId in it.trackIds }

    private fun List<TimelineItem>.hasGapAfter(trackId: Long) =
        any { it is TimelineItem.GapItem && it.gap.afterTrackId == trackId }

    private fun List<PlaceResolver.PlaceSummary>.named(label: String) = filter { it.place?.label == label }

    private fun GapState.departure() = (this as? GapDetail)?.fromPlace

    private fun TrackListViewModel.MissingRecordings.departure(afterTrackId: Long) =
        days.flatMap { it.items }.single { it.gap.afterTrackId == afterTrackId }.fromPlace

    private fun PlaceResolver.ResolvedStay?.isHome() = this?.place?.label == "Home"

    private fun <T> List<T>.firstKept(what: String, shown: (T) -> Boolean): T {
        val first = indexOfFirst(shown)
        assertTrue("$what showed", first >= 0)
        assertTrue("$what went away", drop(first).all(shown))
        return this[first]
    }

    private val TimelineItem.trackIds: List<Long>
        get() = (this as? TimelineItem.TrackItem)?.trip?.parts?.map(TrackSummary::id).orEmpty()

    private companion object {
        const val HOUR = 60 * 60_000L
        const val WALKS = 3
        const val LINE_LON = -2.0
        const val AWAY_OFFSET = 0.05
    }
}
