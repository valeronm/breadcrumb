package io.github.valeronm.breadcrumb.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.valeronm.breadcrumb.data.AndroidDistance
import io.github.valeronm.breadcrumb.data.Cities
import io.github.valeronm.breadcrumb.data.DerivationStore
import io.github.valeronm.breadcrumb.data.JourneyPolylines
import io.github.valeronm.breadcrumb.data.OnlinePlaceSearch
import io.github.valeronm.breadcrumb.data.PlaceRepository
import io.github.valeronm.breadcrumb.data.Settings
import io.github.valeronm.breadcrumb.data.TrackPoints
import io.github.valeronm.breadcrumb.data.TrackRepository
import io.github.valeronm.breadcrumb.data.db.DiscardedSummary
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.data.export.BackupRepositories
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.CityAtlas
import io.github.valeronm.breadcrumb.domain.Clocks
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.MonthTotals
import io.github.valeronm.breadcrumb.domain.MonthlyTotals
import io.github.valeronm.breadcrumb.domain.PlaceCategory
import io.github.valeronm.breadcrumb.domain.PlaceCategorySuggester
import io.github.valeronm.breadcrumb.domain.PlaceClusterer
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TimelineItem
import io.github.valeronm.breadcrumb.domain.TimelineRows
import io.github.valeronm.breadcrumb.domain.TrackMerge
import io.github.valeronm.breadcrumb.domain.TravelDeriver
import io.github.valeronm.breadcrumb.domain.TravelNaming
import io.github.valeronm.breadcrumb.domain.toTrackEnd
import io.github.valeronm.breadcrumb.location.TrackingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId

class TrackListViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = TrackRepository(app)
    private val placeRepository = PlaceRepository(app)
    private val derivationStore = DerivationStore(app)
    private val backupRepositories =
        BackupRepositories(repository, placeRepository, derivationStore)

    internal val importExport = ImportExportController(app, viewModelScope, repository, backupRepositories)

    internal val journeyPolylines = JourneyPolylines(repository)

    // Opening a track re-emits an identical list, since the query leaves out open tracks.
    // Unseeded, since a seed reaching a combine reads there as an empty history.
    private val trackRows: Flow<List<TrackSummary>> = repository.observeSummaries()
        .distinctUntilChanged()
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    val tracks: StateFlow<List<TrackSummary>> = trackRows
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val discardedTracks: StateFlow<List<DiscardedSummary>> = repository.observeDiscardedSummaries()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * [places] must be the list the clusters were ordered against: [PlaceResolver] matches a
     * cluster to a place by position (`seedIndex`), so after a place is deleted a later list
     * resolves every cluster after it to its neighbor.
     */
    private class Derived(
        val derivation: StayDeriver.Derivation,
        val places: List<Place>,
        val now: Long,
        val tracks: List<StayDeriver.TrackEnd>,
        val cities: Map<Coordinate, CityAtlas.City>,
    ) {
        val stays: List<StayDeriver.Stay> = derivation.intervals.filterIsInstance<StayDeriver.Stay>()

        /** `ZoneId.of` parses and allocates on every call. */
        private val zoneByCluster: List<ZoneId> by lazy {
            derivation.clusters.map { zoneOrDevice(cities[it.centroid]?.zoneId) }
        }

        /**
         * Every track endpoint is a cluster member. A coordinate two clusters share maps to the
         * later one; clusters that close share a clock.
         */
        private val clusterOfEndpoint: Map<Coordinate, Int> by lazy {
            buildMap {
                derivation.clusters.forEachIndexed { index, cluster ->
                    for (member in cluster.members) put(member, index)
                }
            }
        }

        private val zonesByTrack: Map<Long, Clocks> by lazy {
            val zoneAt = { at: Coordinate? -> zoneOfCluster(at?.let(clusterOfEndpoint::get)) }
            tracks.associate { it.trackId to Clocks(zoneAt(it.start), zoneAt(it.end)) }
        }

        fun zoneOfCluster(id: Int?): ZoneId = id?.let(zoneByCluster::getOrNull) ?: timelineZone()

        fun zonesOfTrack(trackId: Long): Clocks =
            zonesByTrack[trackId] ?: Clocks.both(timelineZone())
    }

    private val placeRows: Flow<List<Place>> = placeRepository.observePlaces()
        .distinctUntilChanged()
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /**
     * Replaced each pass rather than grown: every finished track moves a centroid, and the process
     * lives for weeks.
     *
     * A null value means the atlas reaches nothing there. Only the [derived] flow's coroutine may
     * touch it.
     */
    private var cityByPoint: Map<Coordinate, CityAtlas.City?> = emptyMap()

    /**
     * Points a place claims are resolved too: a label says what the user calls a spot, not which
     * country it is in or what time it is there.
     */
    private fun citiesOf(
        points: List<Coordinate>,
    ): Map<Coordinate, CityAtlas.City> {
        val previous = cityByPoint
        val resolved = HashMap<Coordinate, CityAtlas.City?>(points.size)
        val found = HashMap<Coordinate, CityAtlas.City>(points.size)
        for (at in points) {
            if (at in resolved) continue
            val city = if (previous.containsKey(at)) previous[at] else cityOf(at)
            resolved[at] = city
            city?.let { found[at] = it }
        }
        cityByPoint = resolved
        return found
    }

    private fun cityOf(at: Coordinate): CityAtlas.City? =
        Cities.atlas(getApplication()).naming(at.lat, at.lon, AndroidDistance)

    /**
     * The places come from the stored snapshot rather than an arm of their own, which would turn
     * one transaction into two emissions ([DerivationStore.observeStored]).
     *
     * The status arm keeps only fields a new fix never changes.
     *
     * **The armed flag only triggers a re-read**: the trailing stay closes at the disarm time read
     * from Settings, which `handleStart` and `handleStop` write before they flip [TrackingStatus].
     */
    private val derived: Flow<Derived> = combine(
        derivationStore.observeStored(),
        TrackingStatus.state
            .map { it.tracking to it.openTrack?.startedAt }
            .distinctUntilChanged(),
        // Mapped in the arm, so a new derivation or an arm/disarm leaves an unchanged list alone.
        repository.observeEndpoints().distinctUntilChanged().map { ends -> ends.map { it.toTrackEnd() } },
    ) { stored, (_, activeStartedAt), trackEnds ->
        val now = System.currentTimeMillis()
        val derivation = derivationStore.read(
            stored, now, activeStartedAt,
            disarmedSince = Settings.disarmedSinceMs(getApplication()),
        )
        Derived(
            derivation,
            stored.places,
            now,
            trackEnds,
            // Here rather than where the rows are built, which re-runs on writes that move no
            // cluster.
            citiesOf(derivation.clusters.map { it.centroid }),
        )
    }
        .flowOn(Dispatchers.Default)
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    private class PendingPlace(val editing: String, val row: Place) {
        fun dress(summary: PlaceResolver.PlaceSummary) =
            if (summary.key == editing) summary.withPlace(row) else summary

        fun dress(stay: PlaceResolver.ResolvedStay) =
            if (stay.key == editing) stay.withPlace(row) else stay

        /** An item the row changes nothing on comes back as the same instance. */
        fun dress(item: TimelineItem): TimelineItem = when (item) {
            is TimelineItem.TrackItem, is TimelineItem.RecordingItem -> item
            is TimelineItem.StayItem -> {
                val place = item.place?.let(::dress)
                if (place === item.place) item else item.copy(place = place)
            }
            is TimelineItem.GapItem -> {
                val from = item.fromPlace?.let(::dress)
                val to = item.toPlace?.let(::dress)
                if (from === item.fromPlace && to === item.toPlace) item else item.copy(fromPlace = from, toPlace = to)
            }
        }
    }

    /**
     * Naming a place re-derives the whole history, and until that lands the stored rows still show
     * the unnamed cluster.
     */
    private val pendingPlace = MutableStateFlow<PendingPlace?>(null)

    /**
     * Shared, so a change to [pendingPlace] costs a map over these rows rather than a walk of the
     * history.
     */
    private val resolvedTimeline: Flow<List<TimelineItem>> = combine(trackRows, derived) { summaries, d ->
        // Over the unsliced stays: after slicePerDay a three-day stay counts as three visits.
        // Slicing keeps cluster ids, so the sliced items look these up directly.
        val clusterPlaces = PlaceResolver.resolveClusters(d.stays, d.derivation.clusters, d.places, d.cities)
        // observeSummaries returns newest first, so a reversed view is chronological without a
        // sort.
        val neighbors = summaries.asReversed().zipWithNext()
            .associate { (a, b) -> a.id to TrackMerge.Neighbors(a, b) }

        // Decided over the intervals: the rows below are per-day slices, bounded by the display.
        val mergePlans = TrackMerge.plansByAnchor(d.derivation.intervals, neighbors)
        // A gap carries a clock per end, so the slicer cuts a crossing into the day it left and the
        // day it landed.
        val zoneOfCluster = { id: Int? -> d.zoneOfCluster(id) }
        val zonesOfInterval = { interval: StayDeriver.Interval ->
            when (interval) {
                is StayDeriver.Stay -> Clocks.both(zoneOfCluster(interval.clusterId))
                is StayDeriver.Gap ->
                    Clocks(zoneOfCluster(interval.fromClusterId), zoneOfCluster(interval.toClusterId))
            }
        }
        TimelineRows.interleave(
            summaries,
            TimelineRows.slicePerDay(d.derivation.intervals, zonesOfInterval, d.now),
        ).mapNotNull { item ->
            when (item) {
                is TimelineItem.TrackItem -> d.zonesOfTrack(item.summary.id).let {
                    item.copy(zone = it.start, endZone = it.end)
                }
                is TimelineItem.RecordingItem -> item
                // The slice already carries the zones.
                is TimelineItem.GapItem -> item.copy(
                    fromPlace = item.gap.fromClusterId?.let(clusterPlaces::getOrNull),
                    toPlace = item.gap.toClusterId?.let(clusterPlaces::getOrNull),
                    merge = mergePlans[item.gap.afterTrackId],
                )
                // Filtered after `merge` is attached, since [TimelineItem.StayItem.isBareSeam]
                // reads it.
                is TimelineItem.StayItem -> item.copy(
                    place = clusterPlaces.getOrNull(item.stay.clusterId),
                    merge = mergePlans[item.stay.afterTrackId],
                ).takeUnless { it.isBareSeam }
            }
        }
    }.flowOn(Dispatchers.Default)
        // No stop timeout: the downstream stateIns keep five seconds, and one here would add to it.
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(), replay = 1)

    val recordingRow: StateFlow<TimelineItem.RecordingItem?> = TrackingStatus.state
        .map { it.openTrack }
        .distinctUntilChanged()
        .map { open -> open?.let { TimelineItem.RecordingItem(it.id, it.label, it.startedAt) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Tracks interleaved with derived stays and data gaps, newest first, sliced per local day.
     *
     * **Null until the first derivation lands**, which is not an empty history: the first
     * derivation walks the whole history, and after a cold start a full one has no answer until it
     * finishes.
     */
    val timeline: StateFlow<List<TimelineItem>?> = combine(resolvedTimeline, pendingPlace) { items, pending ->
        if (pending == null) items else items.map(pending::dress)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Runs of nights away from home, oldest first. **Null until the first derivation lands**, as on
     * [timeline].
     */
    val travels: StateFlow<List<TravelNaming.Summary>?> = derived.map { d ->
        val timeline = TravelDeriver.Timeline(d.derivation, d.tracks)
        val travels = TravelDeriver.derive(
            timeline,
            TravelDeriver.homeOf(d.places, d.derivation.clusters, d.derivation.intervals),
            d.now,
            AndroidDistance,
        )
        // The atlas is 4 MB of heap.
        if (travels.isEmpty()) return@map emptyList()
        TravelNaming.summarize(
            travels,
            timeline,
            TravelNaming.Atlas(Cities.atlas(getApplication()), d.places, AndroidDistance),
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Each month the history holds anything in, oldest first: distance per activity and time per
     * place category.
     *
     * Summed from the timeline's own rows, so a month's figures are exactly what the Timeline files
     * under it. Taken before [pendingPlace] is drawn over them, since a place write changes no
     * figure.
     *
     * **Null until the first derivation lands**, as on [timeline].
     */
    val monthlyTotals: StateFlow<List<MonthTotals>?> = resolvedTimeline.map(::monthsOf)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Only the [monthlyTotals] flow's coroutine may touch these. */
    private var lastTimeline: List<TimelineItem> = emptyList()
    private var lastMonths: List<MonthTotals> = emptyList()

    /**
     * [MonthlyTotals.derive], skipped for the list instance it last ran on, which
     * [resolvedTimeline] replays whenever [monthlyTotals] is subscribed again.
     *
     * An open stay's minutes count to the clock of the last derivation, not of the last
     * subscription.
     */
    private fun monthsOf(items: List<TimelineItem>): List<MonthTotals> {
        if (items !== lastTimeline) {
            lastMonths = MonthlyTotals.derive(items, System.currentTimeMillis(), timelineZone())
            lastTimeline = items
        }
        return lastMonths
    }

    private val placeSummaries: Flow<List<PlaceResolver.PlaceSummary>> = derived.map { d ->
        PlaceResolver.summarize(d.stays, d.derivation.clusters, d.places, d.now, d.cities)
    }.flowOn(Dispatchers.Default)
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(), replay = 1)

    /**
     * Every cluster's summary, including clusters with no visits, which a gap's ends still open a
     * detail page on. **Null until the first derivation lands**, as on [timeline].
     */
    val places: StateFlow<List<PlaceResolver.PlaceSummary>?> =
        combine(placeSummaries, pendingPlace) { summaries, pending ->
            if (pending == null) summaries else summaries.map(pending::dress)
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The places table as stored, without waiting on the derivation. Seeded empty rather than null,
     * since no places is an ordinary answer here.
     */
    val storedPlaces: StateFlow<List<Place>> = placeRows
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Trained off [placeRows] rather than [derived], which re-emits on every finished track. */
    val categorySuggester: StateFlow<PlaceCategorySuggester.Model> = placeRows
        .map(PlaceCategorySuggester::train)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaceCategorySuggester.Untrained)

    /**
     * Commit a place's name, pin and radius as one row write, creating the place when [editing] has
     * none. A created place is untagged, since its category suggestion is read off the name.
     *
     * Writes nothing when [PlaceResolver.saysSameAs] finds the row unchanged, since a moved pin or
     * radius re-derives the whole history. [pendingPlace] is retired on the same test.
     *
     * [onCreated] receives the inserted id. Creating changes the place's key, and [places] may
     * conflate the dressed emission away, leaving [PlaceResolver.reacquire] to match by a position
     * the new pin may have moved.
     */
    fun savePlace(
        editing: PlaceResolver.PlaceSummary,
        label: String,
        pin: Coordinate,
        radiusM: Double,
        onCreated: (Long) -> Unit,
    ) {
        val existing = editing.place
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return
        val row = existing?.copy(label = trimmed, lat = pin.lat, lon = pin.lon, radiusM = radiusM)
            ?: Place(
                label = trimmed,
                lat = pin.lat,
                lon = pin.lon,
                createdAt = System.currentTimeMillis(),
                radiusM = radiusM,
            )
        if (PlaceResolver.saysSameAs(existing, row)) return
        var pending = PendingPlace(editing.key, row)
        pendingPlace.value = pending
        viewModelScope.launch {
            try {
                if (existing == null) {
                    val id = placeRepository.create(row)
                    // Id-keyed controls on a dressed summary write nowhere until the row has its
                    // id. A write made since owns the pending slot.
                    val identified = PendingPlace(editing.key, row.copy(id = id))
                    if (pendingPlace.compareAndSet(pending, identified)) pending = identified
                    onCreated(id)
                } else {
                    placeRepository.save(row)
                }
                // Room's invalidation is asynchronous, so the row stays pending until both lists
                // the screens draw from already say what was written.
                withTimeoutOrNull(PENDING_PLACE_TIMEOUT_MS) {
                    coroutineScope {
                        launch { placeSummaries.first { summaries -> summaries.all { pending.dress(it) === it } } }
                        launch { resolvedTimeline.first { items -> items.all { pending.dress(it) === it } } }
                    }
                }
            } finally {
                // A later edit's pending row is that write's to clear.
                pendingPlace.compareAndSet(pending, null)
            }
        }
    }

    fun deletePlace(id: Long) {
        viewModelScope.launch { placeRepository.delete(id) }
    }

    /** Undo a [deletePlace]; the row keeps its id. */
    fun restorePlace(place: Place) {
        viewModelScope.launch { placeRepository.restore(place) }
    }

    /** Null untags. A category is not a clustering input, so nothing re-derives. */
    fun setPlaceCategory(id: Long, category: PlaceCategory?) {
        viewModelScope.launch { placeRepository.setCategory(id, category) }
    }

    /**
     * An end inside an existing place's capture radius is that place, since a second row there
     * would split its stays. Accepted ends join the seeds as they are judged, so a round trip's two
     * ends make one place. Written in one call because each create re-derives the history.
     */
    private suspend fun createTripPlaces(named: List<Pair<String, Coordinate>>) {
        // Current only while collected, which the add-trip form does.
        val seeds = PlaceClusterer.seedsOf(storedPlaces.value).toMutableList()
        val now = System.currentTimeMillis()
        val rows = mutableListOf<Place>()
        for ((label, at) in named) {
            val trimmed = label.trim()
            if (trimmed.isEmpty()) continue
            if (PlaceClusterer.nearestSeedIndex(at.lat, at.lon, seeds, AndroidDistance) != null) continue
            val row = Place(
                label = trimmed, lat = at.lat, lon = at.lon,
                createdAt = now, radiusM = PlaceClusterer.DEFAULT_RADIUS_M,
            )
            rows += row
            // Through seedOf, the projection every other seed came through.
            seeds += PlaceClusterer.seedOf(row)
        }
        if (rows.isNotEmpty()) placeRepository.createAll(rows)
    }

    /** [placeName] is non-null exactly when the end should become a place. */
    class ManualTripEnd(val end: TrackRepository.ManualEnd, val placeName: String?)

    /**
     * Insert a manual trip, or rewrite [editing] in place, then create places for the ends picked
     * by name. [onResult] receives the verdict, a refusal included.
     */
    fun saveManualTrack(
        editing: Long?,
        activityType: ActivityType,
        origin: ManualTripEnd,
        destination: ManualTripEnd,
        onResult: (TrackRepository.ManualTrackResult) -> Unit,
    ) {
        viewModelScope.launch {
            val result = if (editing == null) {
                repository.insertManualTrack(activityType, origin.end, destination.end)
            } else {
                repository.updateManualTrack(editing, activityType, origin.end, destination.end)
            }
            if (result is TrackRepository.ManualTrackResult.Saved) {
                createTripPlaces(
                    listOfNotNull(
                        origin.placeName?.let { it to origin.end.at },
                        destination.placeName?.let { it to destination.end.at },
                    ),
                )
            }
            onResult(result)
        }
    }

    /** [onMerged] receives the merged track's id, which [unmergeTracks] takes. */
    fun mergeTracks(plan: TrackMerge.Plan, onMerged: (Long) -> Unit) {
        viewModelScope.launch {
            repository.mergeTracks(plan.earlierId, plan.laterId)?.let(onMerged)
        }
    }

    /** Undo a [mergeTracks]: drop the merged track, bring both originals back. */
    fun unmergeTracks(mergedId: Long, plan: TrackMerge.Plan) {
        viewModelScope.launch {
            repository.unmergeTracks(mergedId, plan.earlierId, plan.laterId)
        }
    }

    /**
     * The track keeps its id as the first half. [onSplit] is not called when the cut is refused.
     */
    fun splitTrack(trackId: Long, atTs: Long, onSplit: (TrackRepository.Split) -> Unit) {
        viewModelScope.launch {
            repository.splitTrack(trackId, atTs)?.let(onSplit)
        }
    }

    /** Undo a [splitTrack]: the second half's fixes go back and its row goes away. */
    fun unsplitTracks(originalId: Long, split: TrackRepository.Split) {
        viewModelScope.launch {
            repository.unsplitTracks(originalId, split)
        }
    }

    /**
     * [onDeleted] runs only if the delete went through: a return inside the stitch window can
     * reopen the track before the tap lands.
     */
    fun delete(trackId: Long, onDeleted: () -> Unit) {
        viewModelScope.launch {
            if (repository.deleteTrack(trackId)) onDeleted()
        }
    }

    fun restoreTrack(trackId: Long) {
        viewModelScope.launch { repository.restoreTrack(trackId) }
    }

    fun purgeDiscarded(through: Long) {
        viewModelScope.launch { repository.purgeDiscarded(through) }
    }

    fun setTrackActivity(trackId: Long, activityType: ActivityType) {
        viewModelScope.launch { repository.setActivityType(trackId, activityType) }
    }

    suspend fun getPoints(trackId: Long): List<TrackPoint> = repository.pointsFor(trackId)

    suspend fun getPointsAfter(trackId: Long, afterId: Long): List<TrackPoint> =
        repository.pointsAfter(trackId, afterId)

    /** The overrun comes from the stored flags rather than a fresh detection. */
    suspend fun getTrackPoints(trackId: Long): TrackPoints = repository.trackPointsFor(trackId)

    /**
     * The clocks a track's ends ran on, from the same derivation its timeline row reads. A track
     * the derivation never saw, such as a discarded one, gets the device's clock.
     */
    suspend fun zonesOfTrack(trackId: Long): Clocks = derived.first().zonesOfTrack(trackId)

    /**
     * The containing city, so a spot inside a capital resolves to the capital rather than its
     * district. The first call loads the atlas.
     */
    suspend fun cityAt(at: Coordinate): CityAtlas.City? = withContext(Dispatchers.Default) {
        // Bypasses [cityByPoint], which only the derivation's coroutine may touch.
        cityOf(at)
    }

    /** The first call builds the atlas's folded-name index. */
    suspend fun searchCities(query: String, limit: Int): List<CityAtlas.Hit> =
        withContext(Dispatchers.Default) {
            Cities.atlas(getApplication()).searchByName(query, limit)
        }

    /** Empty when the online search is switched off or fails ([OnlinePlaceSearch]). */
    suspend fun searchOnline(query: String, near: Coordinate?, withinM: Double? = null): List<OnlinePlaceSearch.Hit> =
        withContext(Dispatchers.IO) {
            OnlinePlaceSearch.search(getApplication(), query, near, withinM)
        }

    private companion object {
        /**
         * Generous against a rebuild; a derivation that never lands must not leave a pending name
         * on screen.
         */
        const val PENDING_PLACE_TIMEOUT_MS = 30_000L
    }
}
