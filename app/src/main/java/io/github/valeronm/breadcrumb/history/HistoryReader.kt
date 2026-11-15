package io.github.valeronm.breadcrumb.history

import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.TrackEndpoints
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.DerivedHistory
import io.github.valeronm.breadcrumb.domain.DistanceFn
import io.github.valeronm.breadcrumb.domain.HistoryClocks
import io.github.valeronm.breadcrumb.domain.HistoryGaps
import io.github.valeronm.breadcrumb.domain.HistoryReading
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.ResolvedStayInterval
import io.github.valeronm.breadcrumb.domain.ResolvedTimeline
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TravelNaming
import io.github.valeronm.breadcrumb.domain.VisitedWorld
import io.github.valeronm.breadcrumb.domain.atlas.CityAtlas
import io.github.valeronm.breadcrumb.domain.toTrackEnd
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong

/**
 * [places] must be the list the clusters [read] returns were ordered against, and [tracks] and
 * [ends] the finished tracks those clusters were derived over.
 */
interface StoredSnapshot {
    /** Newest first. */
    val tracks: List<TrackSummary>

    /** Oldest first. */
    val ends: List<TrackEndpoints>

    val places: List<Place>

    /**
     * Two calls with the same arguments can differ: the trailing stay also closes at a time the
     * implementation reads itself.
     */
    fun read(nowMs: Long, activeStartedAt: Long?): StayDeriver.Derivation
}

/**
 * Holds only fields a recorded fix never changes. [armed] only triggers a re-read: the trailing stay
 * closes at the disarm time [StoredSnapshot.read] reads.
 */
data class RecorderBounds(val armed: Boolean, val openTrackStart: Long?)

typealias CityLookup = (at: Coordinate, language: String) -> CityAtlas.City?

class AtlasLookup(val language: Flow<String>, val cityAt: CityLookup)

class HistoryInputs(
    val snapshots: Flow<StoredSnapshot>,
    val recorder: Flow<RecorderBounds>,
    val atlas: AtlasLookup,
    val distance: DistanceFn,
    val now: () -> Long,
    val deviceZone: () -> ZoneId,
)

/**
 * [generation] numbers the derivation [value] was read from, and a later derivation's is higher.
 * [places] are the rows that derivation was read against.
 */
class Stamped<out T>(val generation: Long, val places: List<Place>, val value: T) {
    fun <R> map(transform: (T) -> R) = Stamped(generation, places, transform(value))
}

/** A place written but not yet derived shows in no output. */
class HistoryReader(
    private val inputs: HistoryInputs,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val cityCache = CityCache(inputs.atlas.cityAt)

    private val generations = AtomicLong()

    private class Derived(val tracks: List<TrackSummary>, val history: DerivedHistory)

    private val derived: Flow<Stamped<Derived>> = combine(
        inputs.snapshots,
        inputs.recorder.distinctUntilChanged(),
        inputs.atlas.language,
    ) { snapshot, bounds, language ->
        // Numbered before it is computed, so one under way when a place is written is older than the write.
        val generation = generations.incrementAndGet()
        val now = inputs.now()
        val derivation = snapshot.read(now, bounds.openTrackStart)
        val trackEnds = snapshot.ends.map { it.toTrackEnd() }
        val cities = cityCache.citiesAt(derivation, trackEnds, language)
        Stamped(
            generation,
            snapshot.places,
            Derived(snapshot.tracks, DerivedHistory(derivation, snapshot.places, now, trackEnds, cities, inputs.deviceZone)),
        )
    }
        .flowOn(dispatcher)
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /** The number of the latest derivation begun; zero before the first. */
    fun latestGeneration(): Long = generations.get()

    // A stop timeout here would add to whatever timeout a collector keeps.
    private val reading: Flow<Stamped<HistoryReading>> =
        derived.map { read -> read.map { HistoryReading(it.tracks, it.history) } }
            .flowOn(dispatcher)
            .shareIn(scope, SharingStarted.WhileSubscribed(), replay = 1)

    /** Shared, so its [ResolvedTimeline.months] are summed once whoever collects it. */
    val timeline: Flow<Stamped<ResolvedTimeline>> = reading.map { read ->
        read.map { ResolvedTimeline(it.timelineRows, inputs.now, inputs.deviceZone) }
    }.flowOn(dispatcher)
        .shareIn(scope, SharingStarted.WhileSubscribed(), replay = 1)

    val gaps: Flow<Stamped<HistoryGaps>> = reading.map { read -> read.map { it.gaps } }

    fun stay(afterTrackId: Long): Flow<Stamped<ResolvedStayInterval?>> =
        reading.map { read -> read.map { it.stay(afterTrackId) } }

    val mergeableAnchors: Flow<Set<Long>> = reading.map { it.value.mergeableAnchors }.flowOn(dispatcher)

    val places: Flow<Stamped<List<PlaceResolver.PlaceSummary>>> =
        derived.map { read -> read.map { it.history.placeSummaries() } }
            .flowOn(dispatcher)
            .shareIn(scope, SharingStarted.WhileSubscribed(), replay = 1)

    val travels: Flow<List<TravelNaming.Summary>> = derived.map { it.value.history.travels(inputs.distance) }
        .flowOn(dispatcher)

    val world: Flow<List<VisitedWorld.Country>> = derived.map { it.value.history.world() }.flowOn(dispatcher)

    /** The latest derivation's clocks; the timeline's rows can lag it by one emission. */
    suspend fun clocks(): HistoryClocks = derived.first().value.history.clocks
}
