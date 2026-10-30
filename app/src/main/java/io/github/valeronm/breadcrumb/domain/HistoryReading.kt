package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.domain.atlas.CityAtlas
import java.time.ZoneId

/**
 * [places] must be the list the clusters were ordered against: [PlaceResolver] matches a
 * cluster to a place by position (`seedIndex`), so after a place is deleted a later list
 * resolves every cluster after it to its neighbor.
 */
class DerivedHistory(
    val derivation: StayDeriver.Derivation,
    private val places: List<Place>,
    val now: Long,
    tracks: List<StayDeriver.TrackEnd>,
    private val cities: Map<Coordinate, CityAtlas.City>,
    deviceZone: () -> ZoneId,
) {
    private val timeline = TravelDeriver.Timeline(derivation, tracks)

    private val clusterNames = TravelNaming.ClusterNames(places) { cluster -> cities[cluster.atlasPoint] }

    val clocks = HistoryClocks(derivation, cities, tracks, deviceZone)

    // Over the unsliced stays: after slicePerDay a three-day stay counts as three visits.
    private val clusterPlaces by lazy {
        PlaceResolver.resolveClusters(
            derivation.intervals.filterIsInstance<StayDeriver.Stay>(),
            derivation.clusters,
            places,
            cities,
        )
    }

    fun placeOfCluster(id: Int?): PlaceResolver.ResolvedStay? = id?.let(clusterPlaces::getOrNull)

    fun placeSummaries(): List<PlaceResolver.PlaceSummary> = PlaceResolver.summarize(derivation, places, now, cities)

    fun travels(distance: DistanceFn): List<TravelNaming.Summary> {
        val home = TravelDeriver.homeOf(places, derivation.clusters, derivation.intervals)
        return TravelNaming.summarize(TravelDeriver.derive(timeline, home, now, distance), timeline, clusterNames)
    }

    fun world(): List<VisitedWorld.Country> = VisitedWorld.of(timeline, clusterNames, clocks::ofCluster, now)
}

/** A whole gap with the place and clock at each end, and the merge its tracks allow. */
data class ResolvedGap(
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

    fun mapPlaces(f: (PlaceResolver.ResolvedStay) -> PlaceResolver.ResolvedStay): ResolvedGap {
        val from = fromPlace?.let(f)
        val to = toPlace?.let(f)
        return if (from === fromPlace && to === toPlace) this else copy(fromPlace = from, toPlace = to)
    }
}

/** Holds no reference to the [HistoryReading] it came from. */
class HistoryGaps(
    val oldestFirst: List<ResolvedGap>,
    val neighbors: Map<Long, TrackMerge.Neighbors>,
) {
    val byAnchor: Map<Long, ResolvedGap> = oldestFirst.associateBy { it.gap.afterTrackId }

    fun mapPlaces(f: (PlaceResolver.ResolvedStay) -> PlaceResolver.ResolvedStay) =
        HistoryGaps(oldestFirst.map { it.mapPlaces(f) }, neighbors)
}

/**
 * The tracks and the derivation as one reading, with what several screens read off them worked
 * out once.
 */
class HistoryReading(private val summaries: List<TrackSummary>, private val history: DerivedHistory) {
    private val staySwipes: Map<Long, StaySwipe>

    val gaps: HistoryGaps

    init {
        val neighbors = TrackMerge.neighborsByAnchor(summaries)
        // Decided over the intervals: the timeline's rows are per-day slices, bounded by the display.
        val mergePlans = TrackMerge.plansByAnchor(history.derivation.intervals, neighbors)
        staySwipes = StaySwipe.byAnchor(history.derivation.intervals, neighbors)
        val resolved = ArrayList<ResolvedGap>()
        val gapNeighbors = HashMap<Long, TrackMerge.Neighbors>()
        for (gap in history.derivation.intervals) {
            if (gap !is StayDeriver.Gap) continue
            resolved += ResolvedGap(
                gap = gap,
                fromPlace = history.placeOfCluster(gap.from?.clusterId),
                toPlace = history.placeOfCluster(gap.to?.clusterId),
                clocks = history.clocks.ofInterval(gap),
                merge = mergePlans[gap.afterTrackId],
            )
            neighbors[gap.afterTrackId]?.let { gapNeighbors[gap.afterTrackId] = it }
        }
        gaps = HistoryGaps(resolved, gapNeighbors)
    }

    val mergeableAnchors: Set<Long> by lazy { staySwipes.filterValues { it is StaySwipe.Merge }.keys }

    val timelineRows: List<TimelineItem> by lazy {
        // A gap carries a clock per end, so the slicer cuts a crossing into the day it left and the
        // day it landed.
        val zonesOfInterval = { interval: StayDeriver.Interval ->
            when (interval) {
                is StayDeriver.Stay -> history.clocks.ofInterval(interval)
                // A gap side with no cluster reads the device zone on each call.
                is StayDeriver.Gap -> gaps.byAnchor.getValue(interval.afterTrackId).clocks
            }
        }
        TimelineRows.interleave(
            summaries,
            TimelineRows.slicePerDay(history.derivation.intervals, zonesOfInterval, history.now),
        ).map { item ->
            when (item) {
                is TimelineItem.TrackItem -> item.copy(clocks = history.clocks.ofTrip(item.trip))
                is TimelineItem.RecordingItem -> item
                // The slice already carries the zones.
                is TimelineItem.GapItem -> gaps.byAnchor.getValue(item.gap.afterTrackId).let {
                    item.copy(fromPlace = it.fromPlace, toPlace = it.toPlace, merge = it.merge)
                }
                is TimelineItem.StayItem -> item.copy(
                    place = history.placeOfCluster(item.stay.clusterId),
                    swipe = staySwipes[item.stay.afterTrackId],
                )
            }
        }
    }
}

class ResolvedTimeline(val rows: List<TimelineItem>, now: () -> Long, deviceZone: () -> ZoneId) {
    /** An open stay's minutes count to the clock of the first read. */
    val months: List<MonthTotals> by lazy { MonthlyTotals.derive(rows, now(), deviceZone()) }
}
