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

/** [places] must be the list the clusters [read] returns were ordered against. */
interface StoredSnapshot {
    val places: List<Place>

    /**
     * Two calls with the same arguments can differ: the trailing stay also closes at a time the
     * implementation reads itself.
     */
    fun read(nowMs: Long, activeStartedAt: Long?): StayDeriver.Derivation
}

/** [tracks] must be unseeded: a seed reaching the reading reads as an empty history. */
class StoredHistory(
    val tracks: Flow<List<TrackSummary>>,
    val ends: Flow<List<TrackEndpoints>>,
    val snapshots: Flow<StoredSnapshot>,
)

/**
 * Holds only fields a recorded fix never changes. [armed] only triggers a re-read: the trailing stay
 * closes at the disarm time [StoredSnapshot.read] reads.
 */
data class RecorderBounds(val armed: Boolean, val openTrackStart: Long?)

typealias CityLookup = (at: Coordinate, language: String) -> CityAtlas.City?

class AtlasLookup(val language: Flow<String>, val cityAt: CityLookup)

class HistoryInputs(
    val history: StoredHistory,
    val recorder: Flow<RecorderBounds>,
    val atlas: AtlasLookup,
    val distance: DistanceFn,
    val now: () -> Long,
    val deviceZone: () -> ZoneId,
)

/** [generation] numbers the derivation [value] was read from, and a later derivation's is higher. */
class Stamped<out T>(val generation: Long, val value: T)

/** A place written but not yet derived shows in no output. */
class HistoryReader(
    private val inputs: HistoryInputs,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val cityCache = CityCache(inputs.atlas.cityAt)

    private val generations = AtomicLong()

    private val derived: Flow<Stamped<DerivedHistory>> = combine(
        inputs.history.snapshots,
        inputs.recorder.distinctUntilChanged(),
        // Mapped before the combine, so a new snapshot or a bounds move leaves an unchanged list alone.
        inputs.history.ends.distinctUntilChanged().map { ends -> ends.map { it.toTrackEnd() } },
        inputs.atlas.language,
    ) { snapshot, bounds, trackEnds, language ->
        val now = inputs.now()
        val derivation = snapshot.read(now, bounds.openTrackStart)
        Stamped(
            generations.incrementAndGet(),
            DerivedHistory(
                derivation,
                snapshot.places,
                now,
                trackEnds,
                // Here rather than where the rows are built, which re-runs on writes that move no
                // cluster.
                cityCache.citiesAt(derivation, trackEnds, language),
                inputs.deviceZone,
            ),
        )
    }
        .flowOn(dispatcher)
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    // A stop timeout here would add to whatever timeout a collector keeps.
    private val reading: Flow<Stamped<HistoryReading>> = combine(
        // Opening a track re-emits an identical list, since the query leaves out open tracks.
        inputs.history.tracks.distinctUntilChanged(),
        derived,
    ) { summaries, history -> Stamped(history.generation, HistoryReading(summaries, history.value)) }
        .flowOn(dispatcher)
        .shareIn(scope, SharingStarted.WhileSubscribed(), replay = 1)

    /** Shared, so its [ResolvedTimeline.months] are summed once whoever collects it. */
    val timeline: Flow<Stamped<ResolvedTimeline>> = reading.map { r ->
        Stamped(r.generation, ResolvedTimeline(r.value.timelineRows, inputs.now, inputs.deviceZone))
    }.flowOn(dispatcher)
        .shareIn(scope, SharingStarted.WhileSubscribed(), replay = 1)

    val gaps: Flow<Stamped<HistoryGaps>> = reading.map { Stamped(it.generation, it.value.gaps) }

    fun stay(afterTrackId: Long): Flow<Stamped<ResolvedStayInterval?>> =
        reading.map { Stamped(it.generation, it.value.stay(afterTrackId)) }

    val mergeableAnchors: Flow<Set<Long>> = reading.map { it.value.mergeableAnchors }.flowOn(dispatcher)

    val places: Flow<Stamped<List<PlaceResolver.PlaceSummary>>> =
        derived.map { Stamped(it.generation, it.value.placeSummaries()) }
            .flowOn(dispatcher)
            .shareIn(scope, SharingStarted.WhileSubscribed(), replay = 1)

    val travels: Flow<List<TravelNaming.Summary>> = derived.map { it.value.travels(inputs.distance) }
        .flowOn(dispatcher)

    val world: Flow<List<VisitedWorld.Country>> = derived.map { it.value.world() }.flowOn(dispatcher)

    /** The latest derivation's clocks; the timeline's rows can lag it by one emission. */
    suspend fun clocks(): HistoryClocks = derived.first().value.clocks
}
