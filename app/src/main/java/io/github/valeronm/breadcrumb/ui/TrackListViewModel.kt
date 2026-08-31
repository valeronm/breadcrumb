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

    /** GPX import/export/share and full backup/restore — the transfer half of this screen's API. */
    internal val importExport = ImportExportController(app, viewModelScope, repository, backupRepositories)

    /** The journey map's per-track lines, loaded once and kept — see [JourneyPolylines]. */
    internal val journeyPolylines = JourneyPolylines(repository)

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
     * One reading of the derivation and everything resolved against it, shared by [timeline] and
     * [places].
     *
     * **[places] is the list the clusters were ordered against, not merely a fresh one.**
     * [PlaceResolver] resolves a cluster to a place by position (`seedIndex`), so the two are only
     * meaningful together — pairing a derivation with a later reading is what deleting a place would
     * otherwise do: every entry after the gap shifts, and clusters resolve to their neighbours.
     */
    private class Derived(
        val derivation: StayDeriver.Derivation,
        val places: List<Place>,
        val now: Long,
        val tracks: List<StayDeriver.TrackEnd>,
        val cities: Map<Coordinate, CityAtlas.City>,
    ) {
        /** The unsliced stays, extracted once — every downstream flow needs them. */
        val stays: List<StayDeriver.Stay> = derivation.intervals.filterIsInstance<StayDeriver.Stay>()

        /**
         * The clock each cluster runs on, in the derivation's order. Resolved once per derivation
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
         * that claimed it rather than paying a fresh atlas walk per track, which for a
         * mostly-imported history is thousands of walks for answers already in hand. Keyed by
         * coordinate, unlike the derivation's own map: a coordinate two clusters share reads the
         * later one's clock, and clusters that close are on one clock.
         */
        private val clusterOfEndpoint: Map<Coordinate, Int> by lazy {
            buildMap {
                derivation.clusters.forEachIndexed { index, cluster ->
                    for (member in cluster.members) put(member, index)
                }
            }
        }

        /** Each track's two ends, on their own clocks — a track can cross a border. */
        private val zonesByTrack: Map<Long, Clocks> by lazy {
            val zoneAt = { at: Coordinate? -> zoneOfCluster(at?.let(clusterOfEndpoint::get)) }
            tracks.associate { it.trackId to Clocks(zoneAt(it.start), zoneAt(it.end)) }
        }

        fun zoneOfCluster(id: Int?): ZoneId = id?.let(zoneByCluster::getOrNull) ?: timelineZone()

        fun zonesOfTrack(trackId: Long): Clocks =
            zonesByTrack[trackId] ?: Clocks.both(timelineZone())
    }

    /** The places table, read once for every reader below rather than observed per consumer. */
    private val placeRows: Flow<List<Place>> = placeRepository.observePlaces()
        .distinctUntilChanged()
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /**
     * Last pass's answers, so a reading only pays the atlas for the coordinates that moved.
     * Rebuilt each pass rather than added to: a cluster's centroid is the mean of its members, so
     * every finished track nudges one, and a memo that only grew would collect an entry per nudge
     * for as long as the process lives — which is weeks.
     *
     * A null value is an answer (nothing in the atlas reaches there), not a miss, so the lookup
     * below asks [Map.containsKey]. Written only from the [derived] flow, which is one coroutine;
     * nothing else may touch it.
     */
    private var cityByPoint: Map<Coordinate, CityAtlas.City?> = emptyMap()

    /**
     * Where each of [points] sits, for the readers that need a name or a clock off it. Cluster
     * centroids go in claimed or not: a label says what the user calls a spot, never which country
     * it is in or what time it is there, and what a label *does* outrank is [PlaceResolver]'s to
     * decide.
     *
     * Runs once per reading of the derivation rather than where the rows are built: the resolvers
     * re-run on writes that move no cluster — a rename, a track's activity retyped — and a lookup
     * is three walks of a 160,000-row table.
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

    /** The atlas's reading of one coordinate — the single spelling of it in this file. */
    private fun cityOf(at: Coordinate): CityAtlas.City? =
        Cities.atlas(getApplication()).naming(at.lat, at.lon, AndroidDistance)

    /**
     * The derivation every screen maps from: stored rows mapped to shapes, with the trailing stay
     * and the atlas's answers resolved onto it.
     *
     * **The places come from the same snapshot as the rows**, not from an arm of their own — a
     * second arm turns one transaction into two emissions here, and
     * [DerivationStore.observeStored] says what the reading in between holds.
     *
     * Only the recorder arm's two fields are taken from the live status — the active track's
     * *start*, constant per track, and the armed flag — so the per-fix emissions behind it cannot
     * re-run anything here.
     *
     * **The armed flag is a trigger; the value it stands for is the disarm timestamp in Settings**,
     * read fresh in the block below and handed to [DerivationStore.read], which is what closes the
     * trailing stay. The pairing rests on the service writing that timestamp *before* it flips
     * [TrackingStatus] — the order both `handleStart` and `handleStop` keep.
     */
    private val derived: Flow<Derived> = combine(
        derivationStore.observeStored(),
        TrackingStatus.state
            .map { it.tracking to (if (it.recording) it.startedAtMillis else null) }
            .distinctUntilChanged(),
        // Mapped in the arm rather than in the block below, so a reading of the derivation or an
        // arm/disarm does not re-map every track in the history for a list that did not move.
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
            citiesOf(derivation.clusters.map { it.centroid }),
        )
    }
        .flowOn(Dispatchers.Default)
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /**
     * A row the editor has committed, and the key of the summary it was committed against — matched
     * by that key, so a write can only dress the spot it was made against, and each reading decides
     * for itself what a written row may claim ([PlaceResolver.PlaceSummary.withPlace] and its
     * counterpart on a resolved stay).
     *
     * The two [dress] overloads are the two readings of one resolution, and the rule matching a write
     * to a stop is stated once across them.
     */
    private class PendingPlace(val editing: String, val row: Place) {
        fun dress(summary: PlaceResolver.PlaceSummary) =
            if (summary.key == editing) summary.withPlace(row) else summary

        fun dress(stay: PlaceResolver.ResolvedStay) =
            if (stay.key == editing) stay.withPlace(row) else stay
    }

    /**
     * The place write in flight, if any — **what every reader of the derivation sees in place of the
     * spot as it was**, until the derivation behind that write lands.
     *
     * Naming re-derives the whole history, and until that finishes the stored rows still describe
     * the unnamed cluster the reader has just named. A screen drawing only what is derived would
     * therefore keep the old page — old name, Create button and all — for the length of a rebuild
     * and then swap, which reads as a glitch rather than as a stat being refined.
     *
     * Held here rather than by the screen that asked, so **every** surface answers alike: the detail
     * the editor closed onto, the Places list a back press away, and the [timeline] row whose naming
     * invitation is what usually asked in the first place.
     *
     * It stands over a rebuild that `SweepStatus` is meanwhile announcing on the Timeline, and the
     * two do not conflict: the banner is the honest account of a history being reprocessed, this is
     * the answer to a question the reader just asked and is owed. Were a seed change ever repaired
     * regionally rather than rebuilt, this is the mechanism that would go.
     */
    private val pendingPlace = MutableStateFlow<PendingPlace?>(null)

    /**
     * The timeline's rows as derived, before the write in flight ([pendingPlace]) is drawn over
     * them. All of the work is here — resolving, merge offers, slicing, interleaving — and it is
     * shared so that a pending row arriving or retiring costs a map over these rows rather than
     * another walk of the history.
     */
    private val resolvedTimeline: Flow<List<TimelineItem>> = combine(trackRows, derived) { summaries, d ->
        // Resolve places over the UNSLICED stays — after slicePerDay a 3-day stay would count
        // as 3 visits. Cluster ids survive the slicing copies, so items look up directly.
        val clusterPlaces = PlaceResolver.resolveClusters(d.stays, d.derivation.clusters, d.places, d.cities)
        // Each track paired with its chronological successor, keyed by the track an interval
        // follows — what merging the two tracks around a short interval needs. observeSummaries
        // returns newest first, so chronological order is a reversed *view*: no re-sort of the
        // whole history on every emission.
        val neighbors = summaries.asReversed().zipWithNext()
            .associate { (a, b) -> a.id to TrackMerge.Neighbors(a, b) }

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
                // The zones already rode in on the slice; only what the places table says is added.
                is TimelineItem.GapItem -> item.copy(
                    fromPlace = item.gap.fromClusterId?.let(clusterPlaces::getOrNull),
                    toPlace = item.gap.toClusterId?.let(clusterPlaces::getOrNull),
                    merge = mergePlans[item.gap.afterTrackId],
                )
                // Dropped after the offer is attached, never before: whether a seam is worth a row
                // is a question about the offer it would carry — see [TimelineItem.StayItem.isBareSeam].
                is TimelineItem.StayItem -> item.copy(
                    place = clusterPlaces.getOrNull(item.stay.clusterId),
                    merge = mergePlans[item.stay.afterTrackId],
                ).takeUnless { it.isBareSeam }
            }
        }
    }.flowOn(Dispatchers.Default)
        // No grace of its own: its only subscribers are the stages below, whose five seconds already
        // carry a tab swipe, and a grace here would only hold the derivation that much longer.
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(), replay = 1)

    /**
     * Tracks interleaved with derived stays and data gaps, newest first, sliced per local day.
     *
     * **Null until the first derivation lands**, which is not the same answer as an empty list and
     * must not be collapsed into one: the derivation walks the whole history, so on a cold start
     * there is a window where a full history reads as empty. The Timeline's empty state offers a
     * backup restore — an offer that is only safe *because* there is nothing to merge with — so a
     * reader that can't tell the two apart makes that offer over the user's data.
     */
    val timeline: StateFlow<List<TimelineItem>?> = combine(resolvedTimeline, pendingPlace) { items, pending ->
        // Left alone rather than re-mapped when nothing is in flight, which is nearly always.
        if (pending == null) {
            items
        } else {
            items.map { item ->
                when (item) {
                    is TimelineItem.TrackItem -> item
                    is TimelineItem.StayItem -> item.copy(place = item.place?.let(pending::dress))
                    is TimelineItem.GapItem -> item.copy(
                        fromPlace = item.fromPlace?.let(pending::dress),
                        toPlace = item.toPlace?.let(pending::dress),
                    )
                }
            }
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The runs of nights spent away from home, oldest first — what the Timeline marks its days with.
     * Rides the shared derivation rather than collecting anything of its own, and costs one sample
     * per night in the history, which is nothing beside the derivation it maps off.
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
        // The atlas is 4 MB of heap; a history with no travels in it never asks for one.
        if (travels.isEmpty()) return@map emptyList()
        // One naming pass for the whole emission: the same hotel names every journey that stayed
        // there, and a fortnight in one city asks about hundreds of track ends that resolve to the
        // same handful of coordinates.
        TravelNaming.summarize(
            travels,
            timeline,
            TravelNaming.Atlas(Cities.atlas(getApplication()), d.places, AndroidDistance),
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * What each month of the history came to — distance per activity, time per place category.
     * Every month the history holds something in, oldest first; picking a window out of it is the
     * screen's job ([MonthlyTotals.window]), so stepping the shown month costs no re-derivation.
     *
     * Mapped off the Timeline's rows rather than off the derivation, which is the whole point: a
     * month's figures are the sum of exactly the rows the Timeline files under it, so the two
     * surfaces cannot disagree — and the rows arrive already cut at midnight on the clock they were
     * lived in, so no stay straddles a month boundary either. Off [resolvedTimeline] rather than
     * [timeline]: a place write in flight changes a name, a pin or a reach, none of which is a figure.
     *
     * **Null until the first derivation lands**, for the reason given on [timeline]: a screen that
     * reads "not yet" as "nothing" reports an empty year over a full one.
     */
    val monthlyTotals: StateFlow<List<MonthTotals>?> = resolvedTimeline.map(::monthsOf)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Last pass's rows and the months derived from them — see [monthsOf]. Written only from that
     * flow's coroutine, as [cityByPoint] is from its own; nothing else may touch either.
     */
    private var lastTimeline: List<TimelineItem> = emptyList()
    private var lastMonths: List<MonthTotals> = emptyList()

    /**
     * [MonthlyTotals.derive], skipped when the rows are the ones it last ran on.
     *
     * The flow above is dropped five seconds after the Statistics page leaves composition — which a
     * swipe to the journeys beside it does — and [resolvedTimeline] then replays the *same list
     * instance* on the way back, so without this guard every visit to the tab re-walks the whole
     * history for an identical answer. [derived] carries the same guard for the same reason.
     *
     * A consequence worth naming: the wall clock is read per derivation rather than per emission, so
     * an open stay's minutes go as stale as the memo. That is the cheaper half of the trade — the
     * alternative walks a history to advance one row's figure by however long the reader spent on
     * another tab.
     */
    private fun monthsOf(items: List<TimelineItem>): List<MonthTotals> {
        if (items !== lastTimeline) {
            lastMonths = MonthlyTotals.derive(items, System.currentTimeMillis(), timelineZone())
            lastTimeline = items
        }
        return lastMonths
    }

    /** [places] as derived, before the write in flight is drawn over it — shared for the reason
     *  [resolvedTimeline] is. */
    private val placeSummaries: Flow<List<PlaceResolver.PlaceSummary>> = derived.map { d ->
        PlaceResolver.summarize(d.stays, d.derivation.clusters, d.places, d.now, d.cities)
    }.flowOn(Dispatchers.Default)
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(), replay = 1)

    /**
     * Every cluster's aggregate stats — visited places for the Places screen plus zero-visit
     * pass-through clusters so gap sides always have a detail page to open (the Places tab
     * filters the zero-visit rows out at display time). Idle unless a subscriber screen is open.
     *
     * **Null until the first derivation lands**, for the reason given on [timeline]: a screen that
     * reads "not yet" as "nothing" tells the user their history is empty while it is being read.
     */
    val places: StateFlow<List<PlaceResolver.PlaceSummary>?> =
        combine(placeSummaries, pendingPlace) { summaries, pending ->
            if (pending == null) summaries else summaries.map(pending::dress)
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The places table as stored — what the user *said* about places, with nothing derived around
     * it. Not a leaner [places]: that one answers "which spots does this history hold, and what is
     * known about each", and waits on the derivation to do it, while this answers "where are the
     * user's pins" off a table read. A screen with a map and no interest in visits (a track's own,
     * which annotates a route with the places it ran through) wants the second question, and asking
     * the first would leave it blank behind rows it has no use for.
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
     * nothing to the derivation, and waiting on it would retrain on every finished track.
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
     * A place the editor left as it found it writes nothing. That guard belongs here rather than at
     * the button: a pin or a radius moving re-derives the whole history, so what counts as "changed"
     * is a data-layer question, not something a Done tap should be trusted to have asked. It asks
     * [PlaceResolver.saysSameAs], which is the same question the pending row is retired on — a write
     * worth making and a write not yet seen are one comparison read from two ends, and two spellings
     * of it drift into a guard that skips a write the dressing then waits out.
     *
     * [editing] is the summary the editor was opened on, and what is written becomes [pendingPlace]
     * against its key for as long as the write takes — see there for why. It is a whole summary
     * rather than its place because an unnamed cluster has no place and still has to be identified.
     *
     * [onCreated] answers a different question at a different time: the inserted row's *id*, which
     * exists only once the write has run. Creating is the one act that changes a place's key, and the
     * id is what re-finds the row under its new one. The pending row above covers the same screen
     * while the write is in flight and would usually be enough — but [places] is a `StateFlow`, so a
     * dressed emission can be conflated away entirely, and [PlaceResolver.reacquire] is then down to
     * matching by position, which a hand-placed pin may just have moved. Handed to the caller rather
     * than broadcast, so the screen that asked is the screen that follows it.
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
                    // **A created row has no id until the insert answers**, and a summary dressed in
                    // one that hasn't is a place whose id-keyed controls write nowhere — the category
                    // chips beside it, and a second edit sent back here, both of which address a row
                    // by id. So the pending row takes its id as soon as there is one, and only while
                    // it is still the one on screen: a write made since has the better claim to that.
                    val identified = PendingPlace(editing.key, row.copy(id = id))
                    if (pendingPlace.compareAndSet(pending, identified)) pending = identified
                    onCreated(id)
                } else {
                    placeRepository.save(row)
                }
                // **A write committing is not the moment the screens see it.** Room's invalidation
                // is asynchronous, so the derivation this write carried has not been read back yet;
                // dropping the pending row here would put the pre-write list on screen once more —
                // the very flash this exists to prevent, moved to the end. So the stop condition is
                // evidence: a derivation that already says what was written, which is the same test
                // [PlaceResolver.PlaceSummary.withPlace] retires itself on. Bounded, because a
                // pending row is worth showing for about as long as a rebuild and no longer.
                withTimeoutOrNull(PENDING_PLACE_TIMEOUT_MS) {
                    derived.first { d -> d.places.any { PlaceResolver.saysSameAs(it, row) } }
                }
            } finally {
                // Only what this write put there. An edit made while it was in flight replaced it,
                // and that write's own wait is what its row is owed — clearing unconditionally here
                // would drop a name the reader has just given back off the screen.
                pendingPlace.compareAndSet(pending, null)
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
     * split its stays. Judged one after another, each accepted end joining the seed list, so a round
     * trip's two identical ends yield one place — then written together, a create being what
     * re-derives the history and two of them being one derivation's worth of change. Default radius,
     * untagged — naming and categorizing stay separate steps, and the editor is where a circle gets
     * judged.
     */
    private suspend fun createTripPlaces(named: List<Pair<String, Coordinate>>) {
        // The rows the screen is already collecting — warm by the time a trip commits.
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
            // Off the row, not off the pin beside it: the accepted end has to enter the seed list as
            // the same projection the rows already in it entered by, or the two halves this is
            // judged against are read by different rules.
            seeds += PlaceClusterer.seedOf(row)
        }
        if (rows.isNotEmpty()) placeRepository.createAll(rows)
    }

    /** One trip end as the form commits it: the typed end, and the name it was picked by —
     *  non-null exactly when the pin came from a named search hit and should become a place. */
    class ManualTripEnd(val end: TrackRepository.ManualEnd, val placeName: String?)

    /**
     * Commit the trip the add-trip form describes — a new row, or [editing]'s rewritten in place —
     * and an end picked by name becomes a place once it lands; the policy lives with the commit, not
     * in a button. [onResult] gets the repository's verdict either way, the form staying open on a
     * refusal, so it must hear about one.
     *
     * One entry point for both, because everything after the write is the same: which of the two it
     * was is the presence of a track id and nothing else.
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
    suspend fun zonesOfTrack(trackId: Long): Clocks = derived.first().zonesOfTrack(trackId)

    /**
     * Which city a coordinate sits in — the containing one, so a place inside a capital says the
     * capital rather than its arrondissement. The first caller pays for reading the atlas, hence
     * a suspend function off the main thread rather than a value a composable can simply read.
     */
    suspend fun cityAt(at: Coordinate): CityAtlas.City? = withContext(Dispatchers.Default) {
        // Past [cityByCentroid] deliberately: a screen asks from its own coroutine, and that memo
        // belongs to the derivation's. One lookup for one open screen is not worth sharing state
        // across threads for.
        cityOf(at)
    }

    /**
     * Atlas cities matching a typed name — the add-trip form's pin search. The first call pays
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
    suspend fun searchOnline(query: String, near: Coordinate?): List<OnlinePlaceSearch.Hit> =
        withContext(Dispatchers.IO) {
            OnlinePlaceSearch.search(getApplication(), query, near)
        }

    private companion object {
        /** How long a committed place row may stand in for what the derivation will say. Generous
         *  against the rebuild it waits on, and a bound rather than a duration: it exists so a
         *  derivation that never arrives cannot leave a name on screen for the process's life. */
        const val PENDING_PLACE_TIMEOUT_MS = 30_000L
    }
}
