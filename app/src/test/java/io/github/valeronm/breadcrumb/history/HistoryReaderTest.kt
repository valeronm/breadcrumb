package io.github.valeronm.breadcrumb.history

import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.TrackEndpoints
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Clocks
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.MIN
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TimelineItem
import io.github.valeronm.breadcrumb.domain.atlas.CityAtlas
import io.github.valeronm.breadcrumb.domain.flatDistance
import io.github.valeronm.breadcrumb.domain.lonAt
import io.github.valeronm.breadcrumb.domain.toTrackEnd
import io.github.valeronm.breadcrumb.domain.trackEndpoints
import io.github.valeronm.breadcrumb.domain.trackSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryReaderTest {

    private val east = ZoneId.of("Etc/GMT-3")
    private val device = ZoneId.of("Etc/GMT-9")

    private class FakeSnapshot(private val derivation: StayDeriver.Derivation) : StoredSnapshot {
        override val places = emptyList<Place>()
        val reads = mutableListOf<Pair<Long, Long?>>()

        override fun read(nowMs: Long, activeStartedAt: Long?): StayDeriver.Derivation {
            reads += nowMs to activeStartedAt
            return derivation
        }
    }

    private class FakeHistory {
        val tracks = MutableSharedFlow<List<TrackSummary>>(replay = 1)
        val ends = MutableSharedFlow<List<TrackEndpoints>>(replay = 1)
        val snapshots = MutableSharedFlow<FakeSnapshot>(replay = 1)
        val snapshot: FakeSnapshot get() = snapshots.replayCache.single()

        /** One walk per pair of meters east of the origin, from and to, an hour apart. */
        fun write(vararg walks: Pair<Double, Double>) {
            val rows = walks.mapIndexed { i, (from, to) ->
                trackEndpoints(i + 1L, startedAt = i * HOUR, endedAt = i * HOUR + 10 * MIN, from, to)
            }
            snapshots.tryEmit(FakeSnapshot(StayDeriver.derive(rows.map { it.toTrackEnd() }, distance = flatDistance)))
            ends.tryEmit(rows)
            tracks.tryEmit(rows.map { trackSummary(it.id, ActivityType.WALKING.name, it.startedAt, it.endedAt) }.asReversed())
        }
    }

    private val lookedUp = mutableListOf<Coordinate>()

    private fun Coordinate.beyondAtlas() = lon > lonAt(ATLAS_EDGE_M)

    private val lookup: (Coordinate, String) -> CityAtlas.City? = { at, language ->
        lookedUp += at
        if (at.beyondAtlas()) {
            null
        } else {
            CityAtlas.City(name = "$language town", country = "XX", zone = east, population = 1_000, distanceM = 0.0, row = 0)
        }
    }

    private fun TestScope.readerOf(
        vararg walks: Pair<Double, Double>,
        bounds: Flow<RecorderBounds> = MutableStateFlow(RecorderBounds(armed = false, openTrackStart = null)),
        language: Flow<String> = MutableStateFlow("en"),
        now: () -> Long = { NOW },
        scope: CoroutineScope = backgroundScope,
    ): Pair<FakeHistory, HistoryReader> {
        val history = FakeHistory().apply { write(*walks) }
        val reader = HistoryReader(
            HistoryInputs(
                history = StoredHistory(history.tracks, history.ends, history.snapshots),
                recorder = bounds,
                atlas = AtlasLookup(language, lookup),
                distance = flatDistance,
                now = now,
                deviceZone = { device },
            ),
            scope,
            StandardTestDispatcher(testScheduler),
        )
        return history to reader
    }

    private fun <T> TestScope.collecting(flow: Flow<T>): Job =
        backgroundScope.launch { flow.collect {} }.also { runCurrent() }

    private fun <T> TestScope.collected(flow: Flow<T>): List<T> {
        val seen = mutableListOf<T>()
        backgroundScope.launch { flow.collect { seen += it } }
        runCurrent()
        return seen
    }

    @Test fun `travels subscribe to the derivation and not to the track rows`() = runTest {
        val (history, reader) = readerOf(OUT, BACK)
        collecting(reader.travels)

        assertEquals(1, history.snapshots.subscriptionCount.value)
        assertEquals(0, history.tracks.subscriptionCount.value)
    }

    @Test fun `the derivation's inputs stay five seconds after its last collector, and no longer`() = runTest {
        val (history, reader) = readerOf(OUT)
        collecting(reader.places).cancel()

        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(1, history.snapshots.subscriptionCount.value)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(0, history.snapshots.subscriptionCount.value)
    }

    @Test fun `the track rows go with the timeline's last collector`() = runTest {
        val (history, reader) = readerOf(OUT)
        val collector = collecting(reader.timeline)
        assertEquals(1, history.tracks.subscriptionCount.value)

        collector.cancel()
        runCurrent()
        assertEquals(0, history.tracks.subscriptionCount.value)
    }

    @Test fun `an identical track list builds no new timeline`() = runTest {
        val (history, reader) = readerOf(OUT)
        val timelines = collected(reader.timeline)

        history.tracks.tryEmit(history.tracks.replayCache.single().toList())
        runCurrent()
        assertEquals(1, timelines.size)
    }

    @Test fun `resubscribing within five seconds reads and looks up nothing again`() = runTest {
        val (history, reader) = readerOf(OUT, BACK)
        collecting(reader.places).cancel()
        val looked = lookedUp.size
        assertEquals(1, history.snapshot.reads.size)

        advanceTimeBy(1_000)
        collecting(reader.places)
        assertEquals(1, history.snapshot.reads.size)
        assertEquals(looked, lookedUp.size)
    }

    @Test fun `a restart after the timeout reads again but looks up no point it has already asked about`() = runTest {
        val (history, reader) = readerOf(0.0 to BEYOND_ATLAS_M, BEYOND_ATLAS_M to 0.0)
        collecting(reader.places).cancel()
        val looked = lookedUp.size
        assertTrue(lookedUp.any { it.beyondAtlas() })

        advanceTimeBy(6_000)
        collecting(reader.places)
        assertEquals(2, history.snapshot.reads.size)
        assertEquals(looked, lookedUp.size)
    }

    @Test fun `a language change names every point again in that language`() = runTest {
        val language = MutableStateFlow("en")
        val (_, reader) = readerOf(OUT, BACK, language = language)
        val seen = collected(reader.places)
        assertTrue(seen.last().isNotEmpty())
        assertTrue(seen.last().all { it.locality?.name == "en town" })

        language.value = "de"
        runCurrent()
        assertTrue(seen.last().all { it.locality?.name == "de town" })
    }

    @Test fun `recorder bounds that did not move derive nothing`() = runTest {
        val bounds = MutableSharedFlow<RecorderBounds>(replay = 1)
        bounds.tryEmit(RecorderBounds(armed = true, openTrackStart = null))
        val (history, reader) = readerOf(OUT, bounds = bounds)
        collecting(reader.places)
        val reads = history.snapshot.reads.size

        bounds.tryEmit(RecorderBounds(armed = true, openTrackStart = null))
        runCurrent()
        assertEquals(reads, history.snapshot.reads.size)

        bounds.tryEmit(RecorderBounds(armed = false, openTrackStart = null))
        runCurrent()
        assertEquals(reads + 1, history.snapshot.reads.size)
    }

    @Test fun `the snapshot is read at the clock and the open track's start of each pass`() = runTest {
        var now = NOW
        val bounds = MutableStateFlow(RecorderBounds(armed = true, openTrackStart = 7))
        val (history, reader) = readerOf(OUT, bounds = bounds, now = { now })
        collecting(reader.places)
        assertEquals(listOf(NOW to 7L), history.snapshot.reads)

        now = NOW + MIN
        bounds.value = RecorderBounds(armed = true, openTrackStart = 9)
        runCurrent()
        assertEquals(listOf(NOW to 7L, NOW + MIN to 9L), history.snapshot.reads)
    }

    @Test fun `a timeline's months are summed once, whoever collects them`() = runTest {
        val (_, reader) = readerOf(OUT, BACK)
        val first = collected(reader.timeline)
        val second = collected(reader.timeline)

        assertEquals(1, first.size)
        assertEquals(1, second.size)
        assertSame(first.single().months, second.single().months)
    }

    @Test fun `a write reaches the timeline`() = runTest {
        val (history, reader) = readerOf(OUT)
        val rows = collected(reader.timeline)
        assertTrue(rows.last().rows.none { it is TimelineItem.StayItem })

        history.write(OUT, BACK)
        runCurrent()
        assertTrue(rows.last().rows.any { it is TimelineItem.TrackItem && it.trip.main.id == 2L })
        assertTrue(rows.last().rows.any { it is TimelineItem.StayItem && it.stay.afterTrackId == 1L })
    }

    @Test fun `a track's clocks read the atlas zone, not the device's`() = runTest {
        val (_, reader) = readerOf(OUT)

        assertEquals(Clocks.both(east), reader.clocks().ofTrack(1L))
    }

    @Test fun `cancelling the reader's scope lets every input go`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val (history, reader) = readerOf(OUT, scope = scope)
        scope.launch { reader.timeline.collect {} }
        scope.launch { reader.places.collect {} }
        runCurrent()
        assertEquals(1, history.snapshots.subscriptionCount.value)

        scope.cancel()
        runCurrent()
        assertEquals(0, history.snapshots.subscriptionCount.value)
        assertEquals(0, history.ends.subscriptionCount.value)
        assertEquals(0, history.tracks.subscriptionCount.value)
    }

    private companion object {
        const val HOUR = 60 * MIN
        const val NOW = 24 * HOUR
        const val ATLAS_EDGE_M = 1_000.0
        const val BEYOND_ATLAS_M = 2_000.0
        val OUT = 0.0 to 500.0
        val BACK = 500.0 to 0.0
    }
}
