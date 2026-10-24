package io.github.valeronm.breadcrumb.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.AndroidDistance
import io.github.valeronm.breadcrumb.data.Cities
import io.github.valeronm.breadcrumb.data.DerivationStore
import io.github.valeronm.breadcrumb.data.JourneyPolylines
import io.github.valeronm.breadcrumb.data.OnlinePlaceSearch
import io.github.valeronm.breadcrumb.data.OnlineRouting
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
import io.github.valeronm.breadcrumb.domain.GapDiagnosis
import io.github.valeronm.breadcrumb.domain.MonthTotals
import io.github.valeronm.breadcrumb.domain.MonthlyTotals
import io.github.valeronm.breadcrumb.domain.PlaceCategory
import io.github.valeronm.breadcrumb.domain.PlaceCategorySuggester
import io.github.valeronm.breadcrumb.domain.PlaceClusterer
import io.github.valeronm.breadcrumb.domain.PlaceOrigin
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TimelineItem
import io.github.valeronm.breadcrumb.domain.TimelineRows
import io.github.valeronm.breadcrumb.domain.TrackMerge
import io.github.valeronm.breadcrumb.domain.TravelDeriver
import io.github.valeronm.breadcrumb.domain.TravelNaming
import io.github.valeronm.breadcrumb.domain.TripChains
import io.github.valeronm.breadcrumb.domain.TripPlaces
import io.github.valeronm.breadcrumb.domain.toTrackEnd
import io.github.valeronm.breadcrumb.location.TrackingStatus
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
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
import kotlinx.coroutines.flow.receiveAsFlow
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

    private val messageQueue = Channel<String>(Channel.UNLIMITED)

    /** Transient messages for the snackbar host, held until the app is on screen. */
    internal val messages: Flow<String> = messageQueue.receiveAsFlow()

    internal fun say(message: String) {
        messageQueue.trySend(message)
    }

    internal val importExport =
        ImportExportController(app, viewModelScope, repository, backupRepositories, ::say)

    /** The language the screens are in, which places are named in. It outlives a language switch,
     *  as this model does, so the screens hand it over ([useLanguage]). */
    private val language = MutableStateFlow(app.resources.configuration.locales[0].language)

    fun useLanguage(code: String) {
        language.value = code
    }

    internal val journeyPolylines = JourneyPolylines(repository)

    // Opening a track re-emits an identical list, since the query leaves out open tracks.
    // Unseeded, since a seed reaching a combine reads there as an empty history.
    private val trackRows: Flow<List<TrackSummary>> = repository.observeSummaries()
        .distinctUntilChanged()
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    val tracks: StateFlow<List<TrackSummary>> = trackRows
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Whether no finished track is kept; null until the first read, which is not an empty history. */
    val historyEmpty: StateFlow<Boolean?> = trackRows
        .map { it.isEmpty() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
        val language: String,
    ) {
        val stays: List<StayDeriver.Stay> = derivation.intervals.filterIsInstance<StayDeriver.Stay>()

        // Over the unsliced stays: after slicePerDay a three-day stay counts as three visits.
        private val clusterPlaces by lazy { PlaceResolver.resolveClusters(stays, derivation.clusters, places, cities) }

        /** `ZoneId.of` parses and allocates on every call. */
        private val zoneByCluster: List<ZoneId> by lazy {
            derivation.clusters.map { zoneOrDevice(cities[it.centroid]?.zoneId) }
        }

        private val zonesByTrack: Map<Long, Clocks> by lazy {
            fun zoneOf(end: Coordinate?) = end?.let(::zoneAt) ?: timelineZone()
            tracks.associate { it.trackId to Clocks(zoneOf(it.start), zoneOf(it.end)) }
        }

        fun placeOfCluster(id: Int?): PlaceResolver.ResolvedStay? = id?.let(clusterPlaces::getOrNull)

        fun zoneOfCluster(id: Int?): ZoneId = id?.let(zoneByCluster::getOrNull) ?: timelineZone()

        /** An end in no cluster is resolved in the atlas on its own ([unclusteredEnds]). */
        private fun zoneAt(end: Coordinate): ZoneId =
            derivation.clusterOfEndpoint[end]?.let(::zoneOfCluster) ?: zoneOrDevice(cities[end]?.zoneId)

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

    /** The language [cityByPoint] was named in. */
    private var cityLanguage: String? = null

    /**
     * Points a place claims are resolved too: a label says what the user calls a spot, not which
     * country it is in or what time it is there.
     */
    private fun citiesOf(
        points: List<Coordinate>,
        language: String,
    ): Map<Coordinate, CityAtlas.City> {
        val previous = if (language == cityLanguage) cityByPoint else emptyMap()
        val resolved = HashMap<Coordinate, CityAtlas.City?>(points.size)
        val found = HashMap<Coordinate, CityAtlas.City>(points.size)
        for (at in points) {
            if (at in resolved) continue
            val city = if (previous.containsKey(at)) previous[at] else cityOf(at, language)
            resolved[at] = city
            city?.let { found[at] = it }
        }
        cityByPoint = resolved
        cityLanguage = language
        return found
    }

    private fun unclusteredEnds(derivation: StayDeriver.Derivation, tracks: List<StayDeriver.TrackEnd>) =
        buildList {
            for (track in tracks) {
                track.start?.takeUnless(derivation.clusterOfEndpoint::containsKey)?.let(::add)
                track.end?.takeUnless(derivation.clusterOfEndpoint::containsKey)?.let(::add)
            }
        }

    private fun cityOf(at: Coordinate, language: String): CityAtlas.City? =
        Cities.atlas(getApplication(), language).naming(at.lat, at.lon, AndroidDistance)

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
        language,
    ) { stored, (_, activeStartedAt), trackEnds, language ->
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
            citiesOf(derivation.clusters.map { it.centroid } + unclusteredEnds(derivation, trackEnds), language),
            language,
        )
    }
        .flowOn(Dispatchers.Default)
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /**
     * A place row written but not yet derived, dressing whatever describes it: the spot it was
     * edited from by key, and once the row has an id, anything already holding that place.
     */
    class PendingPlace internal constructor(private val editing: String, internal val row: Place) {
        private fun describes(key: String, place: Place?) =
            key == editing || (row.id != 0L && place?.id == row.id)

        fun dress(summary: PlaceResolver.PlaceSummary) =
            if (describes(summary.key, summary.place)) summary.withPlace(row) else summary

        fun dress(stay: PlaceResolver.ResolvedStay) =
            if (describes(stay.key, stay.place)) stay.withPlace(row) else stay

        /** An item the row changes nothing on comes back as the same instance. */
        fun dress(item: TimelineItem): TimelineItem = when (item) {
            is TimelineItem.TrackItem, is TimelineItem.RecordingItem -> item
            is TimelineItem.StayItem -> {
                val place = item.place?.let(::dress)
                if (place === item.place) item else item.copy(place = place)
            }
            is TimelineItem.GapItem ->
                dressSides(item.fromPlace, item.toPlace)?.let { (from, to) -> item.copy(fromPlace = from, toPlace = to) }
                    ?: item
        }

        /** A gap's two sides dressed, or null where the row describes neither. */
        internal fun dressSides(
            from: PlaceResolver.ResolvedStay?,
            to: PlaceResolver.ResolvedStay?,
        ): Pair<PlaceResolver.ResolvedStay?, PlaceResolver.ResolvedStay?>? {
            val dressedFrom = from?.let(::dress)
            val dressedTo = to?.let(::dress)
            return if (dressedFrom === from && dressedTo === to) null else dressedFrom to dressedTo
        }
    }

    /**
     * Naming a place re-derives the whole history, and until that lands the stored rows still show
     * the unnamed cluster.
     */
    private val pendingPlace = MutableStateFlow<PendingPlace?>(null)

    val pendingPlaceRow: StateFlow<PendingPlace?> = pendingPlace

    /**
     * The tracks and the derivation as one reading, with what several screens read off them worked
     * out once.
     */
    private class Reading(val summaries: List<TrackSummary>, val d: Derived) {
        val neighbors = neighborsByAnchor(summaries)

        // Decided over the intervals: the timeline's rows are per-day slices, bounded by the display.
        val mergePlans = TrackMerge.plansByAnchor(d.derivation.intervals, neighbors)

        /** Oldest first, as the derivation holds them. */
        val gaps: List<ResolvedGap> = d.derivation.intervals.filterIsInstance<StayDeriver.Gap>().map { gap ->
            ResolvedGap(
                gap = gap,
                fromPlace = d.placeOfCluster(gap.from?.clusterId),
                toPlace = d.placeOfCluster(gap.to?.clusterId),
                clocks = Clocks(d.zoneOfCluster(gap.from?.clusterId), d.zoneOfCluster(gap.to?.clusterId)),
                merge = mergePlans[gap.afterTrackId],
            )
        }
        val gapsByAnchor: Map<Long, ResolvedGap> = gaps.associateBy { it.gap.afterTrackId }
    }

    /** A whole gap with the place and clock at each end, and the merge its tracks allow. */
    private data class ResolvedGap(
        val gap: StayDeriver.Gap,
        val fromPlace: PlaceResolver.ResolvedStay?,
        val toPlace: PlaceResolver.ResolvedStay?,
        val clocks: Clocks,
        val merge: TrackMerge.Plan?,
    ) {
        fun item() = TimelineItem.GapItem(
            gap = gap,
            fromPlace = fromPlace,
            toPlace = toPlace,
            merge = merge,
            departureZone = clocks.start,
            arrivalZone = clocks.end,
        )

        /** A gap no side of which [pending] describes comes back as the same instance. */
        fun dressedBy(pending: PendingPlace): ResolvedGap =
            pending.dressSides(fromPlace, toPlace)?.let { (from, to) -> copy(fromPlace = from, toPlace = to) } ?: this
    }

    // No stop timeout: the downstream stateIns keep five seconds, and one here would add to it.
    private val reading: Flow<Reading> = combine(trackRows, derived, ::Reading)
        .flowOn(Dispatchers.Default)
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(), replay = 1)

    /** [Reading]'s gaps with a place just written drawn onto their sides, for the screens about gaps. */
    private class GapReading(val reading: Reading, val gaps: List<ResolvedGap>) {
        val byAnchor: Map<Long, ResolvedGap> =
            if (gaps === reading.gaps) reading.gapsByAnchor else gaps.associateBy { it.gap.afterTrackId }
    }

    private val gapReading: Flow<GapReading> = combine(reading, pendingPlace) { r, pending ->
        GapReading(r, if (pending == null) r.gaps else r.gaps.map { it.dressedBy(pending) })
    }.flowOn(Dispatchers.Default)
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(), replay = 1)

    /**
     * Shared, so a change to [pendingPlace] costs a map over these rows rather than a walk of the
     * history.
     */
    private val resolvedTimeline: Flow<List<TimelineItem>> = reading.map { r ->
        val d = r.d
        // A gap carries a clock per end, so the slicer cuts a crossing into the day it left and the
        // day it landed.
        val zonesOfInterval = { interval: StayDeriver.Interval ->
            when (interval) {
                is StayDeriver.Stay -> Clocks.both(d.zoneOfCluster(interval.clusterId))
                is StayDeriver.Gap -> r.gapsByAnchor.getValue(interval.afterTrackId).clocks
            }
        }
        TimelineRows.interleave(
            r.summaries,
            TimelineRows.slicePerDay(d.derivation.intervals, zonesOfInterval, d.now),
        ).map { item ->
            when (item) {
                is TimelineItem.TrackItem -> item.copy(
                    zone = d.zonesOfTrack(item.trip.parts.first().id).start,
                    endZone = d.zonesOfTrack(item.trip.parts.last().id).end,
                )
                is TimelineItem.RecordingItem -> item
                // The slice already carries the zones.
                is TimelineItem.GapItem -> r.gapsByAnchor.getValue(item.gap.afterTrackId).let {
                    item.copy(fromPlace = it.fromPlace, toPlace = it.toPlace, merge = it.merge)
                }
                is TimelineItem.StayItem -> item.copy(
                    place = d.placeOfCluster(item.stay.clusterId),
                    merge = r.mergePlans[item.stay.afterTrackId],
                )
            }
        }
    }.flowOn(Dispatchers.Default)
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(), replay = 1)

    fun gapDetail(afterTrackId: Long): Flow<GapState> = gapReading.map { g ->
        val resolved = g.byAnchor[afterTrackId] ?: return@map GapState.Gone
        val neighbors = g.reading.neighbors[afterTrackId] ?: return@map GapState.Gone
        GapDetail(
            resolved.gap,
            neighbors.before,
            neighbors.after,
            fromPlace = resolved.fromPlace,
            toPlace = resolved.toPlace,
            departureZone = resolved.clocks.start,
            arrivalZone = resolved.clocks.end,
            verdict = GapDiagnosis.diagnose(
                resolved.gap, neighbors,
                fromNamed = resolved.fromPlace?.holdsStays == true,
                toNamed = resolved.toPlace?.holdsStays == true,
                distance = AndroidDistance,
            ),
        )
    }.flowOn(Dispatchers.Default).distinctUntilChanged()

    fun stayedHere(
        beforeId: Long,
        afterId: Long,
        side: PlaceResolver.ResolvedStay,
        onDone: (PlaceRepository.StayedHere) -> Unit,
    ) {
        val placeId = side.place?.id ?: return
        viewModelScope.launch {
            placeRepository.stayedHere(beforeId, afterId, placeId)?.let(onDone)
        }
    }

    fun undoStayedHere(done: PlaceRepository.StayedHere) {
        viewModelScope.launch { placeRepository.undoStayedHere(done) }
    }

    val recordingRow: StateFlow<TimelineItem.RecordingItem?> = TrackingStatus.state
        .map { it.openTrack }
        .distinctUntilChanged()
        .map { open -> open?.let { TimelineItem.RecordingItem(it.id, it.label, it.startedAt) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Whether a restore may start, by the query its start is refused on. */
    val historyLoadable: StateFlow<Boolean> = repository.observeHasKeptTracks()
        .map { !it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Whether clearing the history would delete anything. */
    val historyClearable: StateFlow<Boolean> = repository.observeAnyRows()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

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
        TravelNaming.summarize(
            travels,
            timeline,
            TravelNaming.ClusterNames(d.places) { cluster -> d.cities[cluster.centroid] },
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
        PlaceResolver.summarize(d.derivation, d.places, d.now, d.cities)
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

    /** Every gap whole, newest first under the day it began, and how many there are. */
    internal class MissingRecordings(val days: List<DayGroup<TimelineItem.GapItem>>) {
        val count: Int get() = days.sumOf { it.items.size }
    }

    /** Whether the derivation holds any gap; false until the first one lands. */
    val hasMissingRecordings: StateFlow<Boolean> = reading
        .map { it.gaps.isNotEmpty() }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** **Null until the first derivation lands**, as on [timeline]. */
    internal val missingRecordings: StateFlow<MissingRecordings?> = gapReading
        .map { g -> g.gaps.asReversed() }
        // A change elsewhere in the history leaves the gaps equal, and the page as it was.
        .distinctUntilChanged()
        .map { gaps ->
            val dayOf = gaps.associate { it.gap.afterTrackId to it.gap.start.toLocalDate(it.clocks.start) }
            MissingRecordings(groupTimelineByDay(gaps.map { it.item() }) { dayOf.getValue(it.gap.afterTrackId) })
        }
        .flowOn(Dispatchers.Default)
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
        val source = PlaceOrigin.MANUAL.code
        val row = existing?.copy(label = trimmed, lat = pin.lat, lon = pin.lon, radiusM = radiusM, source = source)
            ?: Place(
                label = trimmed,
                lat = pin.lat,
                lon = pin.lon,
                createdAt = System.currentTimeMillis(),
                radiusM = radiusM,
                source = source,
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
                        launch {
                            placeSummaries.first { summaries ->
                                // A created row is in no list until the derivation places it.
                                (pending.row.id == 0L || summaries.any { it.place?.id == pending.row.id }) &&
                                    summaries.all { pending.dress(it) === it }
                            }
                        }
                        launch { resolvedTimeline.first { items -> items.all { pending.dress(it) === it } } }
                    }
                }
            } finally {
                // A later edit's pending row is that write's to clear.
                pendingPlace.compareAndSet(pending, null)
            }
        }
    }

    // Only the latest: one Undo is offered at a time, and a newer removal replaces the older's.
    private var lastRemoval: Pair<Long, Deferred<PlaceRepository.Removal>>? = null

    fun deletePlace(place: Place) {
        lastRemoval = place.id to viewModelScope.async { placeRepository.delete(place) }
    }

    /** Undo a [deletePlace]; the row keeps its id and its stated ends. */
    fun restorePlace(place: Place) {
        val (id, removal) = lastRemoval ?: return
        if (id != place.id) return
        lastRemoval = null
        viewModelScope.launch { placeRepository.restore(removal.await()) }
    }

    private var lastMerge: Pair<Long, Deferred<PlaceRepository.Merge>>? = null

    fun mergePlaces(keep: Place, absorbed: List<Place>) {
        lastMerge = keep.id to viewModelScope.async { placeRepository.merge(keep, absorbed) }
    }

    /** Undo a [mergePlaces] into [keep]; the absorbed rows keep their ids and their stated ends. */
    fun unmergePlaces(keep: Place) {
        val (id, merge) = lastMerge ?: return
        if (id != keep.id) return
        lastMerge = null
        viewModelScope.launch { placeRepository.unmerge(merge.await()) }
    }

    /** Null untags. A category is not a clustering input, so nothing re-derives. */
    fun setPlaceCategory(id: Long, category: PlaceCategory?) {
        viewModelScope.launch { placeRepository.setCategory(id, category) }
    }

    /** Written in one call because each create re-derives the history. */
    private suspend fun createTripPlaces(ends: List<ManualTripEnd>) {
        // Current only while collected, which the add-trip form does.
        val stored = storedPlaces.value
        val plan = TripPlaces.plan(
            ends.map { TripPlaces.End(it.end.at, it.placeName) },
            stored,
            AndroidDistance,
            createdAt = System.currentTimeMillis(),
        )
        if (plan.created.isNotEmpty() || plan.renamed.isNotEmpty()) placeRepository.createAndName(plan.created, plan.renamed)
    }

    /** [placeName] is the name the end was picked by; [TripPlaces.plan] decides what place it makes. */
    class ManualTripEnd(val end: TrackRepository.ManualEnd, val placeName: String?)

    /** A trip as the form states it; [path] is the road shape between the ends, or empty. */
    class ManualTrip(
        val activityType: ActivityType,
        val origin: ManualTripEnd,
        val destination: ManualTripEnd,
        val path: List<Coordinate>,
    )

    /**
     * Insert a manual trip, or rewrite [editing] in place, then create places for the ends picked
     * by name. [onResult] receives the verdict, a refusal included.
     */
    fun saveManualTrack(
        editing: Long?,
        trip: ManualTrip,
        onResult: (TrackRepository.ManualTrackResult) -> Unit,
    ) {
        viewModelScope.launch {
            val result = if (editing == null) {
                repository.insertManualTrack(trip.activityType, trip.origin.end, trip.destination.end, trip.path)
            } else {
                repository.updateManualTrack(editing, trip.activityType, trip.origin.end, trip.destination.end, trip.path)
            }
            if (result is TrackRepository.ManualTrackResult.Saved) {
                createTripPlaces(listOf(trip.origin, trip.destination))
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

    /** What [splitTrack] at [atTs] would leave, for its confirmation to show; [points] are the
     *  track's, good and ignored, as the screen already holds them. */
    suspend fun previewSplit(trackId: Long, atTs: Long, points: List<TrackPoint>): TrackRepository.SplitPreview? =
        repository.previewSplit(trackId, atTs, points)

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
    fun delete(trackIds: List<Long>, onDeleted: () -> Unit) {
        viewModelScope.launch {
            if (repository.deleteTracks(trackIds)) onDeleted()
        }
    }

    fun restoreTrack(trackId: Long) = restoreTracks(listOf(trackId))

    /** Refused, like an import, while an operation on the whole history runs. */
    fun restoreTracks(trackIds: List<Long>) {
        if (importExport.refuseIfBusy()) return
        viewModelScope.launch {
            if (!repository.restoreTracks(trackIds)) {
                say(getApplication<Application>().getString(R.string.discarded_restore_overlaps))
            }
        }
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

    /** The ids of the tracks the timeline holds directly before and after track [trackId]. */
    suspend fun tracksAround(trackId: Long): Pair<Long?, Long?> = repository.keptTracksAround(trackId)

    /** Tracks [beforeId] and [afterId] as a trip between them meets them, the one before first. */
    internal suspend fun touchingTracks(beforeId: Long?, afterId: Long?): Pair<TouchingTrack?, TouchingTrack?> =
        coroutineScope {
            val summaries = trackRows.first()
            val before = beforeId?.let { async { touchingTrack(summaries, it, precedes = true) } }
            val after = afterId?.let { async { touchingTrack(summaries, it, precedes = false) } }
            before?.await() to after?.await()
        }

    private suspend fun touchingTrack(summaries: List<TrackSummary>, trackId: Long, precedes: Boolean): TouchingTrack? {
        val track = repository.track(trackId) ?: return null
        val lat = (if (precedes) track.endLat else track.startLat) ?: return null
        val lon = (if (precedes) track.endLon else track.startLon) ?: return null
        val boundMs = (if (precedes) track.endedAt else track.startedAt) ?: return null
        val placeId = if (precedes) track.endPlaceId else track.startPlaceId
        val parts = TripChains.containing(summaries, trackId)?.parts
        val lines = if (parts == null) {
            listOf(journeyPolylines.lineFor(track))
        } else {
            val at = parts.indexOfFirst { it.id == trackId }
            // Only the parts on the far side of the touching one: a trip being edited is part of its neighbor's trip.
            val shown = if (precedes) parts.take(at + 1) else parts.drop(at)
            coroutineScope { shown.map { async { journeyPolylines.lineFor(it) } }.awaitAll() }
        }
        return TouchingTrack(
            lines = lines,
            activityType = track.activityType,
            touching = Coordinate(lat, lon),
            boundMs = boundMs,
            placeId = placeId,
        )
    }

    /**
     * The clocks a track's ends ran on, from the same derivation its timeline row reads. A track
     * the derivation never saw, such as a discarded one, gets the device's clock.
     */
    suspend fun zonesOfTrack(trackId: Long): Clocks = derived.first().zonesOfTrack(trackId)

    // Off the main thread: an imported place can hold thousands of stated ends to file.
    suspend fun statedEnds(here: Long?, elsewhere: List<Long>): PlaceClusterer.Stated =
        withContext(Dispatchers.Default) { repository.statedEnds(here, elsewhere) }

    /**
     * The containing city, so a spot inside a capital resolves to the capital rather than its
     * district. The first call loads the atlas.
     */
    suspend fun cityAt(at: Coordinate): CityAtlas.City? = withContext(Dispatchers.Default) {
        // Bypasses [cityByPoint], which only the derivation's coroutine may touch.
        cityOf(at, language.value)
    }

    /** The first call builds the atlas's folded-name index. */
    suspend fun searchCities(query: String, limit: Int): List<CityAtlas.Hit> =
        withContext(Dispatchers.Default) {
            Cities.atlas(getApplication(), language.value).searchByName(query, limit)
        }

    /** Empty when the online search is switched off or fails ([OnlinePlaceSearch]). */
    suspend fun searchOnline(query: String, near: Coordinate?, withinM: Double? = null): List<OnlinePlaceSearch.Hit> =
        withContext(Dispatchers.IO) {
            OnlinePlaceSearch.search(getApplication(), query, near, withinM)
        }

    /** Null when the online routing is switched off or fails ([OnlineRouting]). */
    suspend fun routeOnline(profile: OnlineRouting.Profile, from: Coordinate, to: Coordinate): OnlineRouting.Route? =
        withContext(Dispatchers.IO) {
            OnlineRouting.route(getApplication(), profile, from, to)
        }

    private companion object {
        /**
         * Generous against a rebuild; a derivation that never lands must not leave a pending name
         * on screen.
         */
        const val PENDING_PLACE_TIMEOUT_MS = 30_000L
    }
}

// observeSummaries returns newest first.
private fun neighborsByAnchor(summaries: List<TrackSummary>): Map<Long, TrackMerge.Neighbors> =
    summaries.asReversed().zipWithNext().associate { (a, b) -> a.id to TrackMerge.Neighbors(a, b) }
