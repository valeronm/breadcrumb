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
import io.github.valeronm.breadcrumb.data.StoredDerivation
import io.github.valeronm.breadcrumb.data.TrackPoints
import io.github.valeronm.breadcrumb.data.TrackRepository
import io.github.valeronm.breadcrumb.data.db.AppDatabase
import io.github.valeronm.breadcrumb.data.db.DiscardedSummary
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.data.export.BackupRepositories
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.GapDiagnosis
import io.github.valeronm.breadcrumb.domain.HiddenStay
import io.github.valeronm.breadcrumb.domain.HistoryGaps
import io.github.valeronm.breadcrumb.domain.MonthTotals
import io.github.valeronm.breadcrumb.domain.NearbyPlaces
import io.github.valeronm.breadcrumb.domain.PlaceCategory
import io.github.valeronm.breadcrumb.domain.PlaceCategorySuggester
import io.github.valeronm.breadcrumb.domain.PlaceCircle
import io.github.valeronm.breadcrumb.domain.PlaceClusterer
import io.github.valeronm.breadcrumb.domain.PlaceOrigin
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.TimelineItem
import io.github.valeronm.breadcrumb.domain.TrackMerge
import io.github.valeronm.breadcrumb.domain.TravelNaming
import io.github.valeronm.breadcrumb.domain.Trip
import io.github.valeronm.breadcrumb.domain.TripChains
import io.github.valeronm.breadcrumb.domain.TripClocks
import io.github.valeronm.breadcrumb.domain.TripPlaces
import io.github.valeronm.breadcrumb.domain.VisitedWorld
import io.github.valeronm.breadcrumb.domain.atlas.CityAtlas
import io.github.valeronm.breadcrumb.domain.atlas.SpotCity
import io.github.valeronm.breadcrumb.history.AtlasLookup
import io.github.valeronm.breadcrumb.history.CityLookup
import io.github.valeronm.breadcrumb.history.HistoryInputs
import io.github.valeronm.breadcrumb.history.HistoryReader
import io.github.valeronm.breadcrumb.history.RecorderBounds
import io.github.valeronm.breadcrumb.history.StoredHistory
import io.github.valeronm.breadcrumb.history.StoredSnapshot
import io.github.valeronm.breadcrumb.location.TrackingStatus
import io.github.valeronm.breadcrumb.util.DebugLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
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
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "Breadcrumb"

private fun cityOf(at: Coordinate, atlas: CityAtlas): CityAtlas.City? = atlas.naming(at.lat, at.lon, AndroidDistance)

private class RoomSnapshot(
    private val stored: StoredDerivation,
    private val store: DerivationStore,
    private val app: Application,
) : StoredSnapshot {
    override val places: List<Place> = stored.places

    // A start or a stop writes the disarm time before it moves TrackingStatus.
    override fun read(nowMs: Long, activeStartedAt: Long?) =
        store.read(stored, nowMs, activeStartedAt, disarmedSince = Settings.disarmedSinceMs(app))
}

class TrackListViewModel @JvmOverloads constructor(
    app: Application,
    db: AppDatabase = AppDatabase.get(app),
    cityLookup: CityLookup = { at, language ->
        cityOf(at, Cities.atlas(app, language))
    },
) : AndroidViewModel(app) {

    private val repository = TrackRepository(app, db)
    private val placeRepository = PlaceRepository(app, db)
    private val derivationStore = DerivationStore(app, db)
    private val backupRepositories =
        BackupRepositories(repository, placeRepository, derivationStore)

    private val messageQueue = Channel<String>(Channel.UNLIMITED)

    /** Transient messages for the snackbar host, held until the app is on screen. */
    internal val messages: Flow<String> = messageQueue.receiveAsFlow()

    internal fun say(message: String) {
        messageQueue.trySend(message)
    }

    /**
     * [viewModelScope] has no exception handler, so a write launched there that throws ends the
     * process.
     */
    private val scope = viewModelScope + CoroutineExceptionHandler { _, e ->
        DebugLog.e(TAG, "ui write failed", e)
        say(app.getString(R.string.common_failed))
    }

    internal val importExport =
        ImportExportController(app, scope, repository, backupRepositories, ::say)

    /** The language the screens are in, which places are named in. It outlives a language switch,
     *  as this model does, so the screens hand it over ([useLanguage]). */
    private val language = MutableStateFlow(app.resources.configuration.locales[0].language)

    fun useLanguage(code: String) {
        language.value = code
    }

    internal val journeyPolylines = JourneyPolylines(repository)

    private val trackRows: Flow<List<TrackSummary>> = repository.observeSummaries()
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    private val history = HistoryReader(
        HistoryInputs(
            history = StoredHistory(
                tracks = trackRows,
                ends = repository.observeEndpoints(),
                snapshots = derivationStore.observeStored().map { RoomSnapshot(it, derivationStore, app) },
            ),
            recorder = TrackingStatus.state.map { RecorderBounds(it.tracking, it.openTrack?.startedAt) },
            atlas = AtlasLookup(language, cityLookup),
            distance = AndroidDistance,
            now = System::currentTimeMillis,
            deviceZone = ::timelineZone,
        ),
        viewModelScope,
    )

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

    private val placeRows: Flow<List<Place>> = placeRepository.observePlaces()
        .distinctUntilChanged()
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /**
     * A place row written but not yet derived, dressing whatever describes it: the spot it was
     * edited from by key, and once the row has an id, anything already holding that place.
     * A summary, stay or item the row does not describe comes back as the same instance.
     */
    class PendingPlace internal constructor(private val editing: String, internal val row: Place) {
        private fun describes(key: String, place: Place?) =
            key == editing || (row.id != 0L && place?.id == row.id)

        fun dress(summary: PlaceResolver.PlaceSummary) =
            if (describes(summary.key, summary.place)) summary.withPlace(row) else summary

        fun dress(stay: PlaceResolver.ResolvedStay) =
            if (describes(stay.key, stay.place)) stay.withPlace(row) else stay

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

    val pendingPlaceRow: StateFlow<PendingPlace?> = pendingPlace

    private val gapReading: Flow<HistoryGaps> = combine(history.gaps, pendingPlace) { g, pending ->
        if (pending == null) g else g.mapPlaces(pending::dress)
    }.flowOn(Dispatchers.Default)
        // A replayed reading from an earlier visit lacks the gaps made since, and `gapDetail` reports those as Gone.
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), replay = 1)

    fun gapDetail(afterTrackId: Long): Flow<GapState> = gapReading.map { g ->
        val resolved = g.byAnchor[afterTrackId] ?: return@map GapState.Gone
        val neighbors = g.neighbors[afterTrackId] ?: return@map GapState.Gone
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

    fun stayDetail(afterTrackId: Long): Flow<StayState> =
        combine(
            history.stay(afterTrackId),
            pendingPlace,
            placeRows,
            TrackingStatus.state.map { s -> s.openTrack?.let { StayClaim.Recording(it.id, it.startedAt) } }.distinctUntilChanged(),
        ) { stay, pending, rows, recording ->
            val found = stay ?: return@combine StayState.Gone
            val resolved = if (pending == null) found else found.copy(place = found.place?.let(pending::dress))
            val nearby = NearbyPlaces.around(resolved.ends, rows, resolved.place?.place?.id, AndroidDistance)
            StayDetail(resolved, nearby, resolved.placeDistanceM(AndroidDistance), StayClaim.of(resolved, recording))
        }.flowOn(Dispatchers.Default).distinctUntilChanged()

    fun stayedHere(claim: StayClaim, placeId: Long, onDone: (PlaceRepository.StayedHere) -> Unit) {
        scope.launch {
            placeRepository.stayedHere(claim.beforeId, claim.afterId, placeId)?.let(onDone)
        }
    }

    fun undoStayedHere(done: PlaceRepository.StayedHere) {
        scope.launch { placeRepository.undoStayedHere(done) }
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
    val timeline: StateFlow<List<TimelineItem>?> = combine(history.timeline, pendingPlace) { t, pending ->
        if (pending == null) t.rows else t.rows.map(pending::dress)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The anchors of the stays the Timeline offers to merge away, from the swipes its rows carry. */
    val mergeableAnchors: StateFlow<Set<Long>> = history.mergeableAnchors
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /**
     * Runs of nights away from home, oldest first. **Null until the first derivation lands**, as on
     * [timeline].
     */
    val travels: StateFlow<List<TravelNaming.Summary>?> = history.travels
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Every country and city the history has been to, most days first. **Null until the first
     * derivation lands**, as on [timeline].
     */
    val world: StateFlow<List<VisitedWorld.Country>?> = history.world
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
    val monthlyTotals: StateFlow<List<MonthTotals>?> = history.timeline.map { it.months }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Every cluster's summary, including clusters with no visits, which a gap's ends still open a
     * detail page on. **Null until the first derivation lands**, as on [timeline].
     */
    val places: StateFlow<List<PlaceResolver.PlaceSummary>?> =
        combine(history.places, pendingPlace) { summaries, pending ->
            if (pending == null) summaries else summaries.map(pending::dress)
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * [stop]'s summary ([PlaceResolver.summaryOf]), waiting a bounded while for [places] to hold it,
     * since a list replayed from before the last derivation may lack it. Null where none arrives.
     */
    suspend fun placeSummary(stop: PlaceResolver.ResolvedStay): PlaceResolver.PlaceSummary? =
        withTimeoutOrNull(PLACE_SUMMARY_WAIT_MS) {
            places.mapNotNull { list -> list?.let { PlaceResolver.summaryOf(it, stop) } }.first()
        }

    /** Every gap whole, newest first under the day it began, and how many there are. */
    internal class MissingRecordings(val days: List<DayGroup<TimelineItem.GapItem>>) {
        val count: Int get() = days.sumOf { it.items.size }
    }

    /** Whether the derivation holds any gap; false until the first one lands. */
    val hasMissingRecordings: StateFlow<Boolean> = history.gaps
        .map { it.oldestFirst.isNotEmpty() }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** **Null until the first derivation lands**, as on [timeline]. */
    internal val missingRecordings: StateFlow<MissingRecordings?> = gapReading
        .map { g -> g.oldestFirst.asReversed() }
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

    /** Trained off [placeRows] rather than the derivation, which re-emits on every finished track. */
    val categorySuggester: StateFlow<PlaceCategorySuggester.Model> = placeRows
        .map(PlaceCategorySuggester::train)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaceCategorySuggester.Untrained)

    /**
     * Create a place with a name, pin and radius as one row write, where [spot] is the unnamed
     * cluster or spot being named. A created place is untagged, since its category suggestion is
     * read off the name.
     *
     * [onCreated] receives the inserted id. Creating changes the place's key, and [places] may
     * conflate the dressed emission away, leaving [PlaceResolver.reacquire] to match by a position
     * the new pin may have moved.
     *
     * A [stayClaim] is stated to the new row in the same transaction.
     */
    fun createPlace(
        spot: PlaceResolver.PlaceSummary,
        label: String,
        circle: PlaceCircle,
        stayClaim: StayClaim?,
        onCreated: (Long) -> Unit,
    ) {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return
        val row = Place(
            label = trimmed,
            lat = circle.pin.lat,
            lon = circle.pin.lon,
            createdAt = System.currentTimeMillis(),
            radiusM = circle.radiusM,
            source = PlaceOrigin.MANUAL.code,
        )
        commitPlace(spot.key, row, onCreated) {
            stayClaim?.let { placeRepository.createForStay(row, it.beforeId, it.afterId) } ?: placeRepository.create(row)
        }
    }

    /**
     * Commit [existing]'s name, pin and radius as one row write, pending under [editingKey].
     *
     * Writes nothing when [PlaceResolver.saysSameAs] finds the row unchanged, since a moved pin or
     * radius re-derives the whole history. [pendingPlace] is retired on the same test.
     */
    fun savePlace(existing: Place, editingKey: String, label: String, circle: PlaceCircle) {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return
        val row = existing.copy(
            label = trimmed,
            lat = circle.pin.lat,
            lon = circle.pin.lon,
            radiusM = circle.radiusM,
            source = PlaceOrigin.MANUAL.code,
        )
        if (PlaceResolver.saysSameAs(existing, row)) return
        commitPlace(editingKey, row) {
            placeRepository.save(row)
            null
        }
    }

    /** [write] returns the inserted id for a created row, and null for a saved one. */
    private fun commitPlace(editingKey: String, row: Place, onCreated: (Long) -> Unit = {}, write: suspend () -> Long?) {
        var pending = PendingPlace(editingKey, row)
        pendingPlace.value = pending
        scope.launch {
            try {
                write()?.let { id ->
                    // Id-keyed controls on a dressed summary write nowhere until the row has its
                    // id. A write made since owns the pending slot.
                    val identified = PendingPlace(editingKey, row.copy(id = id))
                    if (pendingPlace.compareAndSet(pending, identified)) pending = identified
                    onCreated(id)
                }
                // Room's invalidation is asynchronous, so the row stays pending until both lists
                // the screens draw from already say what was written.
                withTimeoutOrNull(PENDING_PLACE_TIMEOUT_MS) {
                    coroutineScope {
                        launch {
                            history.places.first { summaries ->
                                // A created row is in no list until the derivation places it.
                                (pending.row.id == 0L || summaries.any { it.place?.id == pending.row.id }) &&
                                    summaries.all { pending.dress(it) === it }
                            }
                        }
                        launch { history.timeline.first { t -> t.rows.all { pending.dress(it) === it } } }
                    }
                }
            } finally {
                // A later edit's pending row is that write's to clear.
                pendingPlace.compareAndSet(pending, null)
            }
        }
    }

    /**
     * [async] holds a failure until its result is awaited, which an undo may never do; here the
     * failure reaches [scope]'s handler as it happens, and the result is cancelled.
     */
    private fun <T> launchUndoable(write: suspend () -> T): Deferred<T> {
        val result = CompletableDeferred<T>()
        scope.launch { result.complete(write()) }.invokeOnCompletion { result.cancel() }
        return result
    }

    // Only the latest: one Undo is offered at a time, and a newer removal replaces the older's.
    private var lastRemoval: Pair<Long, Deferred<PlaceRepository.Removal>>? = null

    fun deletePlace(place: Place) {
        lastRemoval = place.id to launchUndoable { placeRepository.delete(place) }
    }

    /** Undo a [deletePlace]; the row keeps its id and its stated ends. */
    fun restorePlace(place: Place) {
        val (id, removal) = lastRemoval ?: return
        if (id != place.id) return
        lastRemoval = null
        scope.launch { placeRepository.restore(removal.await()) }
    }

    private var lastMerge: Pair<Long, Deferred<PlaceRepository.Merge>>? = null

    fun mergePlaces(keep: Place, absorbed: List<Place>) {
        lastMerge = keep.id to launchUndoable { placeRepository.merge(keep, absorbed) }
    }

    /** Undo a [mergePlaces] into [keep]; the absorbed rows keep their ids and their stated ends. */
    fun unmergePlaces(keep: Place) {
        val (id, merge) = lastMerge ?: return
        if (id != keep.id) return
        lastMerge = null
        scope.launch { placeRepository.unmerge(merge.await()) }
    }

    /** Null untags. A category is not a clustering input, so nothing re-derives. */
    fun setPlaceCategory(id: Long, category: PlaceCategory?) {
        scope.launch { placeRepository.setCategory(id, category) }
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
        scope.launch {
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
        scope.launch {
            repository.mergeTracks(plan.earlierId, plan.laterId)?.let(onMerged)
        }
    }

    /** [onHidden] receives the stays the write hid, and is not called where it hid none. */
    fun hideStays(stays: List<HiddenStay>, onHidden: (List<HiddenStay>) -> Unit = {}) {
        scope.launch {
            repository.hideStays(stays).takeIf { it.isNotEmpty() }?.let(onHidden)
        }
    }

    fun showStays(stays: List<HiddenStay>, onShown: () -> Unit = {}) {
        scope.launch {
            repository.unhideStays(stays)
            onShown()
        }
    }

    /** Undo a [mergeTracks]: drop the merged track, bring both originals back. */
    fun unmergeTracks(mergedId: Long, plan: TrackMerge.Plan) {
        scope.launch {
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
        scope.launch {
            repository.splitTrack(trackId, atTs)?.let(onSplit)
        }
    }

    /** Undo a [splitTrack]: the second half's fixes go back and its row goes away. */
    fun unsplitTracks(originalId: Long, split: TrackRepository.Split) {
        scope.launch {
            repository.unsplitTracks(originalId, split)
        }
    }

    /**
     * [onDeleted] runs only if the delete went through: a return inside the stitch window can
     * reopen the track before the tap lands.
     */
    fun delete(trackIds: List<Long>, onDeleted: () -> Unit) {
        scope.launch {
            if (repository.deleteTracks(trackIds)) onDeleted()
        }
    }

    fun purgeDiscarded(through: Long) {
        scope.launch { repository.purgeDiscarded(through) }
    }

    fun setTrackActivity(trackId: Long, activityType: ActivityType) {
        scope.launch { repository.setActivityType(trackId, activityType) }
    }

    suspend fun getPoints(trackId: Long): List<TrackPoint> = repository.pointsFor(trackId)

    suspend fun getPointsAfter(trackId: Long, afterId: Long): List<TrackPoint> =
        repository.pointsAfter(trackId, afterId)

    /** The overrun comes from the stored flags rather than a fresh detection. */
    suspend fun getTrackPoints(trackId: Long): TrackPoints = repository.trackPointsFor(trackId)

    /** The ids of the tracks the timeline holds directly before and after track [trackId]. */
    suspend fun tracksAround(trackId: Long): Pair<Long?, Long?> = repository.keptTracksAround(trackId)

    /** Tracks [beforeId] and [afterId] as a trip between them meets them, the one before first. */
    internal suspend fun touchingTracks(beforeId: Long?, afterId: Long?, smoothed: Boolean): Pair<TouchingTrack?, TouchingTrack?> =
        coroutineScope {
            val summaries = trackRows.first()
            val before = beforeId?.let { async { touchingTrack(summaries, it, precedes = true, smoothed) } }
            val after = afterId?.let { async { touchingTrack(summaries, it, precedes = false, smoothed) } }
            before?.await() to after?.await()
        }

    private suspend fun touchingTrack(summaries: List<TrackSummary>, trackId: Long, precedes: Boolean, smoothed: Boolean): TouchingTrack? {
        val track = repository.track(trackId) ?: return null
        val lat = (if (precedes) track.endLat else track.startLat) ?: return null
        val lon = (if (precedes) track.endLon else track.startLon) ?: return null
        val boundMs = (if (precedes) track.endedAt else track.startedAt) ?: return null
        val placeId = if (precedes) track.endPlaceId else track.startPlaceId
        val parts = TripChains.containing(summaries, trackId)?.parts
        val lines = if (parts == null) {
            listOf(journeyPolylines.lineFor(track, smoothed))
        } else {
            val at = parts.indexOfFirst { it.id == trackId }
            // Only the parts on the far side of the touching one: a trip being edited is part of its neighbor's trip.
            val shown = if (precedes) parts.take(at + 1) else parts.drop(at)
            coroutineScope { shown.map { async { journeyPolylines.lineFor(it, smoothed) } }.awaitAll() }
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
     * The clocks each part of a trip ran on at its ends, from the same derivation its timeline row
     * reads. A track the derivation never saw gets the device's clock.
     */
    suspend fun clocksOfTrip(trip: Trip): TripClocks = history.clocks().ofTrip(trip)

    // Off the main thread: an imported place can hold thousands of stated ends to file.
    suspend fun statedEnds(here: Long?, elsewhere: List<Long>): PlaceClusterer.Stated =
        withContext(Dispatchers.Default) { repository.statedEnds(here, elsewhere) }

    /**
     * The containing city, so a spot inside a capital resolves to the capital rather than its
     * district. The first call loads the atlas.
     */
    internal suspend fun cityAt(at: Coordinate): SpotCity? = withContext(Dispatchers.Default) {
        val atlas = Cities.atlas(getApplication(), language.value)
        cityOf(at, atlas)?.let { SpotCity.of(it, atlas) }
    }

    /** The country outlines as [outlinesGeoJson] writes them; the first call loads the atlas. */
    suspend fun countryOutlines(): String =
        withContext(Dispatchers.Default) { outlinesGeoJson(Cities.borders(getApplication())) }

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

        const val PLACE_SUMMARY_WAIT_MS = 5_000L
    }
}
