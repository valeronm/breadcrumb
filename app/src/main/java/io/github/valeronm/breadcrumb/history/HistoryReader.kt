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
import io.github.valeronm.breadcrumb.domain.ResolvedTimeline
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TravelNaming
import io.github.valeronm.breadcrumb.domain.VisitedWorld
import io.github.valeronm.breadcrumb.domain.atlas.CityAtlas
import io.github.valeronm.breadcrumb.domain.atlasPoints
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

class AtlasLookup(val language: Flow<String>, val cityAt: (at: Coordinate, language: String) -> CityAtlas.City?)

class HistoryInputs(
    val history: StoredHistory,
    val recorder: Flow<RecorderBounds>,
    val atlas: AtlasLookup,
    val distance: DistanceFn,
    val now: () -> Long,
    val deviceZone: () -> ZoneId,
)

/** A place written but not yet derived shows in no output. */
class HistoryReader(
    private val inputs: HistoryInputs,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /**
     * Replaced each pass rather than grown: every finished track moves a centroid, and the process
     * lives for weeks.
     *
     * A null value means the atlas reaches nothing there. Only the [derived] flow's coroutine may
     * touch it.
     */
    private var cityByPoint: Map<Coordinate, CityAtlas.City?> = emptyMap()

    private var cityLanguage: String? = null

    private fun citiesOf(
        points: List<Coordinate>,
        language: String,
    ): Map<Coordinate, CityAtlas.City> {
        val previous = if (language == cityLanguage) cityByPoint else emptyMap()
        val resolved = HashMap<Coordinate, CityAtlas.City?>(points.size)
        val found = HashMap<Coordinate, CityAtlas.City>(points.size)
        for (at in points) {
            if (at in resolved) continue
            val city = if (previous.containsKey(at)) previous[at] else inputs.atlas.cityAt(at, language)
            resolved[at] = city
            city?.let { found[at] = it }
        }
        cityByPoint = resolved
        cityLanguage = language
        return found
    }

    private val derived: Flow<DerivedHistory> = combine(
        inputs.history.snapshots,
        inputs.recorder.distinctUntilChanged(),
        // Mapped before the combine, so a new snapshot or a bounds move leaves an unchanged list alone.
        inputs.history.ends.distinctUntilChanged().map { ends -> ends.map { it.toTrackEnd() } },
        inputs.atlas.language,
    ) { snapshot, bounds, trackEnds, language ->
        val now = inputs.now()
        val derivation = snapshot.read(now, bounds.openTrackStart)
        DerivedHistory(
            derivation,
            snapshot.places,
            now,
            trackEnds,
            // Here rather than where the rows are built, which re-runs on writes that move no
            // cluster.
            citiesOf(atlasPoints(derivation, trackEnds), language),
            inputs.deviceZone,
        )
    }
        .flowOn(dispatcher)
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    // A stop timeout here would add to whatever timeout a collector keeps.
    private val reading: Flow<HistoryReading> = combine(
        // Opening a track re-emits an identical list, since the query leaves out open tracks.
        inputs.history.tracks.distinctUntilChanged(),
        derived,
        ::HistoryReading,
    )
        .flowOn(dispatcher)
        .shareIn(scope, SharingStarted.WhileSubscribed(), replay = 1)

    /** Shared, so its [ResolvedTimeline.months] are summed once whoever collects it. */
    val timeline: Flow<ResolvedTimeline> = reading.map { r ->
        ResolvedTimeline(r.timelineRows, inputs.now, inputs.deviceZone)
    }.flowOn(dispatcher)
        .shareIn(scope, SharingStarted.WhileSubscribed(), replay = 1)

    val gaps: Flow<HistoryGaps> = reading.map { it.gaps }

    val mergeableAnchors: Flow<Set<Long>> = reading.map { it.mergeableAnchors }.flowOn(dispatcher)

    val places: Flow<List<PlaceResolver.PlaceSummary>> = derived.map { it.placeSummaries() }
        .flowOn(dispatcher)
        .shareIn(scope, SharingStarted.WhileSubscribed(), replay = 1)

    val travels: Flow<List<TravelNaming.Summary>> = derived.map { it.travels(inputs.distance) }
        .flowOn(dispatcher)

    val world: Flow<List<VisitedWorld.Country>> = derived.map { it.world() }.flowOn(dispatcher)

    /** The clocks of the derivation the timeline's rows were built from. */
    suspend fun clocks(): HistoryClocks = derived.first().clocks
}
