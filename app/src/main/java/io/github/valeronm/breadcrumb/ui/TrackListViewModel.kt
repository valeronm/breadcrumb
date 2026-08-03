package io.github.valeronm.breadcrumb.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.valeronm.breadcrumb.data.AndroidDistance
import io.github.valeronm.breadcrumb.data.Cities
import io.github.valeronm.breadcrumb.data.LivenessRepository
import io.github.valeronm.breadcrumb.data.OnlinePlaceSearch
import io.github.valeronm.breadcrumb.data.PlaceRepository
import io.github.valeronm.breadcrumb.data.TrackPoints
import io.github.valeronm.breadcrumb.data.TrackRepository
import io.github.valeronm.breadcrumb.data.db.DiscardedSummary
import io.github.valeronm.breadcrumb.data.db.LivenessEvent
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.TrackEndpoints
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.data.export.BackupRepositories
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.CityAtlas
import io.github.valeronm.breadcrumb.domain.PlaceCategory
import io.github.valeronm.breadcrumb.domain.PlaceCategorySuggester
import io.github.valeronm.breadcrumb.domain.PlaceClusterer
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TimelineItem
import io.github.valeronm.breadcrumb.domain.TrackMerge
import io.github.valeronm.breadcrumb.domain.TravelDeriver
import io.github.valeronm.breadcrumb.domain.TravelNaming
import io.github.valeronm.breadcrumb.location.TrackingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
import java.time.ZoneId

/**
 * The places table, admitted only when a reading would cluster differently from the last one —
 * compared through [PlaceClusterer.seedsOf], so the rule is stated in the type the clusterer
 * actually consumes and a new seed field cannot be forgotten in a comparison kept somewhere else.
 * A rename or a tag projects to the same seeds and is dropped here.
 *
 * This is the gate on the most expensive computation in the app: whatever survives it re-clusters
 * the entire history, and the user waits behind that. Rows rather than seeds come out, because the
 * clustering has to keep the exact list it was built from — see [TrackListViewModel.Clustered].
 * Extracted so the rule is reachable by a test without a database — see `PlaceDerivationGateTest`.
 */
internal fun pinnedRows(rows: Flow<List<Place>>): Flow<List<Place>> = rows
    .distinctUntilChanged { before, after ->
        PlaceClusterer.seedsOf(before) == PlaceClusterer.seedsOf(after)
    }

class TrackListViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = TrackRepository(app)
    private val livenessRepository = LivenessRepository(app)
    private val placeRepository = PlaceRepository(app)
    private val backupRepositories = BackupRepositories(repository, placeRepository, livenessRepository)

    /** GPX import/export/share and full backup/restore — the transfer half of this screen's API. */
    internal val importExport = ImportExportController(app, viewModelScope, repository, backupRepositories)

    // These read `tracks` only, so a live recording's points can't wake them (see TrackDao) — the
    // distinctUntilChanged calls are for the writes that do: opening a track re-emits a list that
    // doesn't contain it (endedAt IS NOT NULL), and they stop that identical re-emission from
    // re-running the derivation downstream.
    //
    // Shared unseeded, and [timeline] combines *this* rather than the StateFlow below: a seeded
    // StateFlow emits its `emptyList()` at once, which would let the combine produce a real, empty
    // timeline before the first query returned — the very "a full history reads as empty" the null
    // initial exists to prevent, re-entering through the other input.
    private val trackRows: Flow<List<TrackSummary>> = repository.observeSummaries()
        .distinctUntilChanged()
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    val tracks: StateFlow<List<TrackSummary>> = trackRows
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Soft-deleted tracks (deleted, filtered, merged), for the Recently deleted screen. */
    val discardedTracks: StateFlow<List<DiscardedSummary>> = repository.observeDiscardedSummaries()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * A clustering run, the clock it was taken against, and **the exact places list it was built
     * from**. That list is not incidental: [PlaceResolver] resolves a cluster to a place by position
     * (`seedIndex`), so a derivation is only meaningful beside the reading that seeded it. Pairing
     * one with a later reading is what deleting a place would otherwise do — every row after the
     * gap shifts, and clusters resolve to their neighbours.
     */
    private class Clustered(
        val derivation: StayDeriver.Derivation,
        val places: List<Place>,
        val now: Long,
        /** The same track bounds the derivation ran on — kept because a night spent moving is
         *  covered by no interval, and only a track can place it ([TravelDeriver]). */
        val tracks: List<StayDeriver.TrackEnd>,
        /** Where each cluster's centroid sits, as the gazetteer has it — see [citiesOf]. */
        val cities: Map<StayDeriver.Endpoint, CityAtlas.City>,
    )

    /** One derivation run's inputs and outputs, shared by [timeline] and [places]. */
    private class Derived(
        val derivation: StayDeriver.Derivation,
        val places: List<Place>,
        val now: Long,
        val tracks: List<StayDeriver.TrackEnd>,
        val cities: Map<StayDeriver.Endpoint, CityAtlas.City>,
    ) {
        /** The unsliced stays, extracted once — every downstream flow needs them. */
        val stays: List<StayDeriver.Stay> = derivation.intervals.filterIsInstance<StayDeriver.Stay>()

        /**
         * The clock each cluster runs on, in the clustering's order. Resolved once per derivation
         * rather than per lookup: `ZoneId.of` parses and allocates, the timeline asks per interval
         * *and* again per emitted slice, and every row of a history then holds its own equal-but-
         * distinct instance. Lazy because only the timeline asks.
         */
        private val zoneByCluster: List<ZoneId> by lazy {
            derivation.clusters.map { zoneOrDevice(cities[it.centroid]?.zoneId) }
        }

        /**
         * Which cluster each endpoint fell into. **A track's start is already a member of one** —
         * the derivation clusters every track endpoint — so a track reads its clock off the cluster
         * that claimed it rather than paying a fresh gazetteer walk per track, which for a
         * mostly-imported history is thousands of walks for answers already in hand.
         */
        private val clusterOfEndpoint: Map<StayDeriver.Endpoint, Int> by lazy {
            buildMap {
                derivation.clusters.forEachIndexed { index, cluster ->
                    for (member in cluster.members) put(member, index)
                }
            }
        }

        /** Each track's two ends, on their own clocks — a track can cross a border. */
        private val zonesByTrack: Map<Long, Pair<ZoneId, ZoneId>> by lazy {
            val zoneAt = { at: StayDeriver.Endpoint? -> zoneOfCluster(at?.let(clusterOfEndpoint::get)) }
            tracks.associate { it.trackId to (zoneAt(it.start) to zoneAt(it.end)) }
        }

        fun zoneOfCluster(id: Int?): ZoneId = id?.let(zoneByCluster::getOrNull) ?: timelineZone()

        fun zonesOfTrack(trackId: Long): Pair<ZoneId, ZoneId> =
            zonesByTrack[trackId] ?: (timelineZone() to timelineZone())
    }

    /** The places table, read once for every reader below rather than observed per consumer. */
    private val placeRows: Flow<List<Place>> = placeRepository.observePlaces()
        .distinctUntilChanged()
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    // The stay/place derivation is the most expensive pure computation in the app, so it runs once
    // here and both screens map from it. Of the live status only the active track's start matters
    // (constant per track) — distinctUntilChanged keeps per-fix status emissions from re-running
    // the clustering.
    private val clustered: Flow<Clustered> = combine(
        repository.observeEndpoints().distinctUntilChanged(),
        livenessRepository.observeEvents().distinctUntilChanged(),
        pinnedRows(placeRows),
        TrackingStatus.state.map { if (it.recording) it.startedAtMillis else null }.distinctUntilChanged(),
    ) { endpoints, events, places, activeStartedAt ->
        val now = System.currentTimeMillis()
        val trackEnds = endpoints.map { it.toTrackEnd() }
        val derivation = StayDeriver.derive(
            tracks = trackEnds,
            liveness = events.mapNotNull { it.toLiveness() },
            nowMs = now,
            activeTrack = activeStartedAt?.let { StayDeriver.ActiveTrack(it) },
            distance = AndroidDistance,
            placePins = PlaceClusterer.seedsOf(places),
        )
        Clustered(derivation, places, now, trackEnds, citiesOf(derivation.clusters.map { it.centroid }))
    }.flowOn(Dispatchers.Default)

    /**
     * Last pass's answers, so a re-clustering only pays for the coordinates that moved. Rebuilt from
     * the clustering each pass rather than added to: a cluster's centroid is the mean of its
     * members, so every finished track nudges one, and a memo that only grew would collect an entry
     * per nudge for as long as the process lives — which is weeks.
     *
     * A null value is an answer (nothing in the gazetteer reaches there), not a miss, so the lookup
     * below asks [Map.containsKey]. Written only from the [clustered] flow, which is one coroutine;
     * nothing else may touch it.
     */
    private var cityByPoint: Map<StayDeriver.Endpoint, CityAtlas.City?> = emptyMap()

    /**
     * Where each of [points] sits, for the readers that need a name or a clock off it. Cluster
     * centroids go in claimed or not: a label says what the user calls a spot, never which country
     * it is in or what time it is there, and what a label *does* outrank is [PlaceResolver]'s to
     * decide.
     *
     * Runs beside the clustering rather than where the rows are built: the resolvers re-run on
     * writes that leave the clustering untouched — a rename, a track's activity retyped — and a
     * lookup is three walks of a 160,000-row table.
     */
    private fun citiesOf(
        points: List<StayDeriver.Endpoint>,
    ): Map<StayDeriver.Endpoint, CityAtlas.City> {
        val previous = cityByPoint
        val resolved = HashMap<StayDeriver.Endpoint, CityAtlas.City?>(points.size)
        val found = HashMap<StayDeriver.Endpoint, CityAtlas.City>(points.size)
        for (at in points) {
            if (at in resolved) continue
            val city = if (previous.containsKey(at)) previous[at] else cityOf(at)
            resolved[at] = city
            city?.let { found[at] = it }
        }
        cityByPoint = resolved
        return found
    }

    /** The gazetteer's reading of one coordinate — the single spelling of it in this file. */
    private fun cityOf(at: StayDeriver.Endpoint): CityAtlas.City? =
        Cities.atlas(getApplication()).naming(at.lat, at.lon, AndroidDistance)

    // Freshening the rows *over* the clustering's own is what makes a tag cheap: the pins are
    // unchanged, so the cached derivation is reused and only what reads a label or category runs
    // again. Matched by id onto the list the clustering was built from — never replaced by it — so
    // the positional contract holds whatever the newer reading added or removed; a place deleted
    // while a re-clustering is in flight stays until that lands, which is correct, because it is
    // still in the clustering.
    private val derived: Flow<Derived> = combine(clustered, placeRows) { clustering, fresh ->
        val byId = fresh.associateBy { it.id }
        Derived(
            clustering.derivation,
            clustering.places.map { byId[it.id] ?: it },
            clustering.now,
            clustering.tracks,
            clustering.cities,
        )
    }
        // A places write that moved a pin re-emits both sides; the first carries a list the patch
        // above has just restored to what the previous emission held, and re-deriving the timeline
        // off it would walk the whole history for an identical answer.
        .distinctUntilChanged { before, after ->
            before.derivation === after.derivation && before.places == after.places
        }
        .flowOn(Dispatchers.Default)
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /**
     * Tracks interleaved with derived stays and data gaps, newest first, sliced per local day.
     *
     * **Null until the first derivation lands**, which is not the same answer as an empty list and
     * must not be collapsed into one: the derivation walks the whole history, so on a cold start
     * there is a window where a full history reads as empty. The Timeline's empty state offers a
     * backup restore — an offer that is only safe *because* there is nothing to merge with — so a
     * reader that can't tell the two apart makes that offer over the user's data.
     */
    val timeline: StateFlow<List<TimelineItem>?> = combine(trackRows, derived) { summaries, d ->
        // Resolve places over the UNSLICED stays — after slicePerDay a 3-day stay would count
        // as 3 visits. Cluster ids survive the slicing copies, so items look up directly.
        val clusterPlaces =
            PlaceResolver.resolveClusters(d.stays, d.derivation.clusters, d.places, d.cities)
        // Each track paired with its chronological successor, keyed by the track an interval
        // follows — what merging the two tracks around a short interval needs. observeSummaries
        // returns newest first, so chronological order is a reversed *view*: no re-sort of the
        // whole history on every emission.
        val neighbors = summaries.asReversed().zipWithNext()
            .associate { (a, b) -> a.id to (a to b) }

        // Stays and short gaps merge on the same rule, decided over the intervals as derived —
        // the rows below are per-day slices, whose bounds are the display's, not the stop's.
        val mergePlans = TrackMerge.plansByAnchor(d.derivation.intervals, neighbors)
        // A stay's two ends are one place, so it answers with one clock twice. A gap answers with
        // the clock at each end, which is what lets the slicer cut an unrecorded crossing into the
        // day it left and the day it landed — and stamp each half with the end it speaks for, so
        // nothing downstream has to work that out again.
        val zoneOfCluster = { id: Int? -> d.zoneOfCluster(id) }
        val zonesOfInterval = { interval: StayDeriver.Interval ->
            when (interval) {
                is StayDeriver.Stay -> zoneOfCluster(interval.clusterId).let { it to it }
                is StayDeriver.Gap ->
                    zoneOfCluster(interval.fromClusterId) to zoneOfCluster(interval.toClusterId)
            }
        }
        StayDeriver.interleave(
            summaries,
            StayDeriver.slicePerDay(d.derivation.intervals, zonesOfInterval, d.now),
        ).map { item ->
            when (item) {
                is TimelineItem.TrackItem -> d.zonesOfTrack(item.summary.id).let { (from, to) ->
                    item.copy(zone = from, endZone = to)
                }
                // The zones already rode in on the slice; only what the places table says is added.
                is TimelineItem.GapItem -> item.copy(
                    fromPlace = item.gap.fromClusterId?.let(clusterPlaces::getOrNull),
                    toPlace = item.gap.toClusterId?.let(clusterPlaces::getOrNull),
                    merge = mergePlans[item.gap.afterTrackId],
                )
                is TimelineItem.StayItem -> item.copy(
                    place = clusterPlaces.getOrNull(item.stay.clusterId),
                    merge = mergePlans[item.stay.afterTrackId],
                )
            }
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The runs of nights spent away from home, oldest first — what the Timeline marks its days with.
     * Rides the shared derivation rather than collecting anything of its own, and costs one sample
     * per night in the history, which is nothing beside the clustering it maps off.
     *
     * **Null until the first derivation lands**, for the reason given on [timeline]: empty is a real
     * answer — a history with no tagged home has no journeys — and a screen that cannot tell it from
     * "not yet" tells the user they have never travelled while their history is still being read.
     */
    val travels: StateFlow<List<TravelNaming.Summary>?> = derived.map { d ->
        val timeline = TravelDeriver.Timeline(d.derivation, d.tracks)
        val travels = TravelDeriver.derive(
            timeline,
            TravelDeriver.homeOf(d.places, d.derivation.clusters, d.derivation.intervals),
            d.now,
            AndroidDistance,
        )
        // The gazetteer is 4 MB of heap; a history with no travels in it never asks for one.
        if (travels.isEmpty()) return@map emptyList()
        // One naming pass for the whole emission: the same hotel names every journey that stayed
        // there, and a fortnight in one city asks about hundreds of track ends that resolve to the
        // same handful of coordinates.
        TravelNaming.summarize(
            travels,
            timeline,
            TravelNaming.Gazetteer(Cities.atlas(getApplication()), d.places, AndroidDistance),
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Every cluster's aggregate stats — visited places for the Places screen plus zero-visit
     * pass-through clusters so gap sides always have a detail page to open (the Places tab
     * filters the zero-visit rows out at display time). Idle unless a subscriber screen is open.
     *
     * **Null until the first derivation lands**, for the reason given on [timeline]: a screen that
     * reads "not yet" as "nothing" tells the user their history is empty while it is being read.
     */
    val places: StateFlow<List<PlaceResolver.PlaceSummary>?> = derived.map { d ->
        PlaceResolver.summarize(d.stays, d.derivation.clusters, d.places, d.now, d.cities)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The places table as stored — what the user *said* about places, with nothing derived around
     * it. Not a leaner [places]: that one answers "which spots does this history hold, and what is
     * known about each", and waits on the clustering to do it, while this answers "where are the
     * user's pins" off a table read. A screen with a map and no interest in visits (a track's own,
     * which annotates a route with the places it ran through) wants the second question, and asking
     * the first would leave it blank behind the most expensive computation in the app.
     *
     * Seeded empty rather than null, unlike [places]: no pin is a real and ordinary answer here, and
     * nothing reads it as evidence the history is empty.
     */
    val storedPlaces: StateFlow<List<Place>> = placeRows
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * What the user's own naming says about a place's category, retrained whenever the places table
     * changes — a rename or a fresh tag is exactly the evidence this learns from, so there is
     * nothing to invalidate by hand. Reads [placeRows] rather than [derived]: retraining owes
     * nothing to the clustering, and waiting on it would retrain on every finished track.
     */
    val categorySuggester: StateFlow<PlaceCategorySuggester.Model> = placeRows
        .map(PlaceCategorySuggester::train)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaceCategorySuggester.Untrained)

    /**
     * Everything the place editor decides, committed as **one** row write: a name, where the place
     * sits and how far it reaches. [existing] null creates the place — always untagged, because
     * naming and categorizing are separate steps and the category suggestion is read off the name.
     *
     * An unchanged place writes nothing. That guard belongs here rather than at the button: every
     * write invalidates `places` and re-runs the derivation each screen reads, and a pin or a radius
     * moving re-clusters the whole history — so what counts as "changed" is a data-layer question,
     * not something a Done tap should be trusted to have asked.
     *
     * [onCreated] gets the inserted row's id, and only on a create. Creating is the one act that
     * changes a place's key, and until a derivation has run that id is all that identifies the row:
     * [PlaceResolver.reacquire] can otherwise only follow a named cluster by position, which a
     * hand-placed pin has just moved. Handed to the caller rather than broadcast, so the screen that
     * asked is the screen that follows it.
     */
    fun savePlace(
        existing: Place?,
        label: String,
        pin: StayDeriver.Endpoint,
        radiusM: Double,
        onCreated: (Long) -> Unit,
    ) {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return
        val unchanged = existing != null &&
            trimmed == existing.label &&
            pin.lat == existing.lat &&
            pin.lon == existing.lon &&
            radiusM == existing.radiusM
        if (unchanged) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            if (existing == null) {
                onCreated(placeRepository.create(trimmed, pin.lat, pin.lon, now, radiusM))
            } else {
                placeRepository.save(existing.id, trimmed, pin.lat, pin.lon, radiusM)
            }
        }
    }

    fun deletePlace(id: Long) {
        viewModelScope.launch { placeRepository.delete(id) }
    }

    /** Undo a [deletePlace] — the row comes back with its id, pin and radius intact. */
    fun restorePlace(place: Place) {
        viewModelScope.launch { placeRepository.restore(place) }
    }

    /**
     * Tag what a place is for, or untag it with null. Nothing re-derives — a category is metadata
     * the timeline reads back, not an input to clustering.
     */
    fun setPlaceCategory(id: Long, category: PlaceCategory?) {
        viewModelScope.launch { placeRepository.setCategory(id, category) }
    }

    /**
     * Places for the trip ends the form picked *by name* — created only where no existing place
     * already claims the spot: a pin inside a place's capture radius is that place
     * ([PlaceClusterer.nearestSeedIndex], the rule's one author), and a second row there would
     * split its stays. Sequential, with each create joining the seed list, so a round trip's two
     * identical ends yield one place. Default radius, untagged — naming and categorizing stay
     * separate steps, and the editor is where a circle gets judged.
     */
    private suspend fun createTripPlaces(named: List<Pair<String, StayDeriver.Endpoint>>) {
        // The rows the screen is already collecting — warm by the time a trip commits.
        val seeds = PlaceClusterer.seedsOf(storedPlaces.value).toMutableList()
        for ((label, at) in named) {
            val trimmed = label.trim()
            if (trimmed.isEmpty()) continue
            if (PlaceClusterer.nearestSeedIndex(at.lat, at.lon, seeds, AndroidDistance) != null) continue
            placeRepository.create(
                trimmed, at.lat, at.lon,
                System.currentTimeMillis(), PlaceClusterer.DEFAULT_RADIUS_M,
            )
            seeds += PlaceClusterer.Seed(at, PlaceClusterer.DEFAULT_RADIUS_M)
        }
    }

    /** One trip end as the form commits it: the typed end, and the name it was picked by —
     *  non-null exactly when the pin came from a named search hit and should become a place. */
    class ManualTripEnd(val end: TrackRepository.ManualEnd, val placeName: String?)

    /**
     * Insert the trip the add-trip form describes; an end picked by name becomes a place once the
     * insert lands — the policy lives with the commit, not in a button. [onResult] gets the
     * repository's verdict either way — the form stays open on a refusal, so it must hear about
     * one.
     */
    fun addManualTrack(
        activityType: ActivityType,
        origin: ManualTripEnd,
        destination: ManualTripEnd,
        onResult: (TrackRepository.ManualInsertResult) -> Unit,
    ) {
        viewModelScope.launch {
            val result = repository.insertManualTrack(activityType, origin.end, destination.end)
            if (result is TrackRepository.ManualInsertResult.Inserted) {
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

    /**
     * Merge the two tracks bracketing a short same-activity stay or gap (closing it). [onMerged] gets
     * the new track's id — the undo snackbar needs it to unmerge.
     */
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
     * Cut a track in two at [atTs] (the point picked on the track screen's graph) — the track keeps
     * its id as the first half. [onSplit] gets what the undo snackbar needs to reverse it, and is
     * not called when the cut is refused.
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

    fun delete(trackId: Long) {
        viewModelScope.launch { repository.deleteTrack(trackId) }
    }

    /** Restore a discarded track (deleted, keep-threshold-filtered, or merge original). */
    fun restoreTrack(trackId: Long) {
        viewModelScope.launch { repository.restoreTrack(trackId) }
    }

    /** Permanently delete everything in Recently deleted. */
    fun purgeAllDiscarded() {
        viewModelScope.launch { repository.purgeAllDiscarded() }
    }

    fun setTrackActivity(trackId: Long, activityType: ActivityType) {
        viewModelScope.launch { repository.setActivityType(trackId, activityType) }
    }

    suspend fun getPoints(trackId: Long): List<TrackPoint> = repository.pointsFor(trackId)

    /** Points newer than [afterId] — the live preview's incremental reload. */
    suspend fun getPointsAfter(trackId: Long, afterId: Long): List<TrackPoint> =
        repository.pointsAfter(trackId, afterId)

    /** Everything the track screen draws — the path, the bad fixes marked on it, and the overrun
     *  grayed off its ends. The overrun is read back from the rows, never re-detected: the screen
     *  shows what the track says it is. */
    suspend fun getTrackPoints(trackId: Long): TrackPoints = repository.trackPointsFor(trackId)

    /**
     * The clocks a track's two ends ran on — **the same answer its timeline row reads**, taken off
     * the same derivation rather than resolved again. A screen that resolved the coordinates for
     * itself would agree almost always and disagree at a boundary, and a track whose times change
     * on the way from the row into its own screen is the thing this exists to prevent.
     *
     * Reads the shared derivation's replay rather than starting one: the timeline is collected above
     * every overlay, so there is always a subscriber and this returns without suspending. A track
     * the derivation never saw — a discarded one, which the endpoint query filters out — answers
     * with the device's clock, which is the right answer rather than a missing one.
     */
    suspend fun zonesOfTrack(trackId: Long): Pair<ZoneId, ZoneId> = derived.first().zonesOfTrack(trackId)

    /**
     * Which city a coordinate sits in — the containing one, so a place inside a capital says the
     * capital rather than its arrondissement. The first caller pays for reading the gazetteer, hence
     * a suspend function off the main thread rather than a value a composable can simply read.
     */
    suspend fun cityAt(at: StayDeriver.Endpoint): CityAtlas.City? = withContext(Dispatchers.Default) {
        // Past [cityByCentroid] deliberately: a screen asks from its own coroutine, and that memo
        // belongs to the derivation's. One lookup for one open screen is not worth sharing state
        // across threads for.
        cityOf(at)
    }

    /**
     * Gazetteer cities matching a typed name — the add-trip form's pin search. The first call pays
     * for the atlas's folded-name index on top of the atlas itself, hence suspend and off-main.
     */
    suspend fun searchCities(query: String, limit: Int): List<CityAtlas.Hit> =
        withContext(Dispatchers.Default) {
            Cities.atlas(getApplication()).searchByName(query, limit)
        }

    /**
     * Online geocoder results for the same search — hotels, addresses, the names no bundled data
     * carries. The Privacy switch and the failure-is-absence contract are [OnlinePlaceSearch]'s
     * own; this only moves the blocking fetch off the caller's dispatcher.
     */
    suspend fun searchOnline(query: String, near: StayDeriver.Endpoint?): List<OnlinePlaceSearch.Hit> =
        withContext(Dispatchers.IO) {
            OnlinePlaceSearch.search(getApplication(), query, near)
        }
}

private fun TrackEndpoints.toTrackEnd() = StayDeriver.TrackEnd(
    trackId = id,
    startedAt = startedAt,
    endedAt = endedAt,
    start = if (startLat != null && startLon != null) StayDeriver.Endpoint(startLat, startLon) else null,
    end = if (endLat != null && endLon != null) StayDeriver.Endpoint(endLat, endLon) else null,
)

private fun LivenessEvent.toLiveness(): StayDeriver.Liveness? = when (type) {
    LivenessEvent.TYPE_ARMED -> StayDeriver.Armed(at)
    LivenessEvent.TYPE_DISARMED -> StayDeriver.Disarmed(at)
    LivenessEvent.TYPE_OUTAGE -> until?.let { StayDeriver.Outage(at, it) }
    else -> null
}
