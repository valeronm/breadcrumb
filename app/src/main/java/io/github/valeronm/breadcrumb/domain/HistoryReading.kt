package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.TrackSummary
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
    private val tracks: List<StayDeriver.TrackEnd>,
    private val cities: AtlasCities,
    deviceZone: () -> ZoneId,
) {
    private val timeline = TravelDeriver.Timeline(derivation, tracks)

    private val clusterNames = TravelNaming.ClusterNames(places, cities::ofCluster)

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

    private val trackEnds by lazy { tracks.associateBy { it.trackId } }

    fun trackEnd(id: Long): StayDeriver.TrackEnd? = trackEnds[id]

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

/** The receiver itself where [transform] hands every element back. */
internal inline fun <E> List<E>.mapSame(transform: (E) -> E): List<E> {
    val mapped = map(transform)
    return if (mapped.indices.all { mapped[it] === this[it] }) this else mapped
}

/** Holds no reference to the [HistoryReading] it came from. */
class HistoryGaps(
    val oldestFirst: List<ResolvedGap>,
    val neighbors: Map<Long, TrackMerge.Neighbors>,
) {
    val byAnchor: Map<Long, ResolvedGap> = oldestFirst.associateBy { it.gap.afterTrackId }

    fun mapPlaces(f: (PlaceResolver.ResolvedStay) -> PlaceResolver.ResolvedStay): HistoryGaps {
        val mapped = oldestFirst.mapSame { it.mapPlaces(f) }
        return if (mapped === oldestFirst) this else HistoryGaps(mapped, neighbors)
    }
}

/** A whole stay with its place and clocks, and the facing ends it lies between. */
data class ResolvedStayInterval(
    val stay: StayDeriver.Stay,
    val place: PlaceResolver.ResolvedStay?,
    val clocks: Clocks,
    /** Null where no finished track follows. */
    val hide: HiddenStay?,
    val arrival: Coordinate?,
    val departure: Coordinate?,
    /** Every finished stay at [stay]'s cluster, this one included where it is finished. */
    val hideableHere: List<HiddenStay>,
    val merge: TrackMerge.Plan?,
) {
    val afterId: Long? get() = hide?.afterId

    val ends: List<Coordinate> = listOfNotNull(arrival, departure)

    val middle: Coordinate? =
        if (ends.isEmpty()) null else Coordinate(ends.sumOf { it.lat } / ends.size, ends.sumOf { it.lon } / ends.size)

    /** How far the nearer of [ends] lies from [place]'s pin; null without a place or an end. */
    fun placeDistanceM(distance: DistanceFn): Double? {
        val pin = place?.pin ?: return null
        return ends.minOfOrNull { distance.meters(it.lat, it.lon, pin.lat, pin.lon) }
    }
}

/**
 * The tracks and the derivation as one reading, with what several screens read off them worked
 * out once. [summaries] run newest first.
 */
class HistoryReading(private val summaries: List<TrackSummary>, private val history: DerivedHistory) {
    private val neighbors = TrackMerge.neighborsByAnchor(summaries)

    // Decided over the intervals: the timeline's rows are per-day slices, bounded by the display.
    private val mergePlans = TrackMerge.plansByAnchor(history.derivation.intervals, neighbors)

    private val staySwipes = StaySwipe.byAnchor(history.derivation.intervals, neighbors, mergePlans)

    val gaps: HistoryGaps

    init {
        val resolved = ArrayList<ResolvedGap>()
        val gapNeighbors = HashMap<Long, TrackMerge.Neighbors>()
        for (gap in history.derivation.intervals) {
            if (gap !is StayDeriver.Gap) continue
            resolved += ResolvedGap(
                gap = gap,
                fromPlace = history.placeOfCluster(gap.startClusterId),
                toPlace = history.placeOfCluster(gap.endClusterId),
                clocks = history.clocks.ofInterval(gap),
                merge = mergePlans[gap.afterTrackId],
            )
            neighbors[gap.afterTrackId]?.let { gapNeighbors[gap.afterTrackId] = it }
        }
        gaps = HistoryGaps(resolved, gapNeighbors)
    }

    fun stay(afterTrackId: Long): ResolvedStayInterval? {
        val stay = history.derivation.intervals.asSequence()
            .filterIsInstance<StayDeriver.Stay>()
            .firstOrNull { it.afterTrackId == afterTrackId } ?: return null
        val place = history.placeOfCluster(stay.clusterId)
        val hide = HiddenStays.of(stay, neighbors)
        return ResolvedStayInterval(
            stay = stay,
            place = place,
            clocks = history.clocks.ofInterval(stay),
            hide = hide,
            arrival = history.trackEnd(afterTrackId)?.end,
            departure = hide?.let { history.trackEnd(it.afterId)?.start },
            hideableHere = HiddenStays.hideableAt(stay.clusterId, history.derivation.intervals, neighbors),
            merge = mergePlans[afterTrackId],
        )
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
