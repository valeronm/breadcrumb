package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * Derives *travels* — the runs of nights spent away from home — from the timeline the app already
 * has, at zero sensing cost: [StayDeriver]'s intervals plus the tracks between them. A travel is a
 * maximal run of consecutive nights not spent at home, and it ends the first night spent at one.
 *
 * **Away means a different place, not a distance.** A night is away when the cluster holding it is
 * not one of home's — the same "same place?" question the rest of the app asks, answered by the same
 * clustering, with each home's own capture radius drawing its own boundary. There is deliberately no
 * kilometre threshold: any threshold puts a cliff between a hotel just inside it and the same hotel
 * just outside, and it calls a week spent five minutes down the road "home", which is the one thing
 * the user knows it wasn't. Widening a home's capture radius is how a boundary moves.
 *
 * **A night, not a displacement.** A drive out and back in a day is not a travel, however far it
 * reached — what separates the two is sleeping elsewhere, so the night is the unit and every rule
 * here is about placing one. A night home in the middle of a run splits it into two travels rather
 * than being tolerated: treating it as one journey means inventing a tolerance, and the honest
 * reading of going home is that a trip ended.
 *
 * **Home is a set** ([homeOf]). Tagging two places [PlaceCategory.HOME] is itself the statement that
 * both are home, so a week at the second one is not a journey. What follows and is worth stating:
 * moving between two homes is a travel only when a night falls *between* them — an overnight hop
 * lands its sample in the leg carrying it, both of whose ends are home, while nights spent en route
 * belong to neither.
 *
 * **Nights are placed on solar time, from longitude alone** ([solarOffsetOf]) — no stored zone
 * exists to read (the recorder never wrote one, and imported GPX carries none), and today's device
 * zone is the wrong answer for a past trip abroad: it slices a destination's nights at the home
 * zone's boundary. The offset a longitude implies is off political time by up to ~1.5 h at a zone's
 * edges, which cannot move an answer about which bed someone was in. The enumeration is anchored on
 * the *primary* home's longitude, so the calendar of nights doesn't shift underfoot as the traveller
 * moves; against a far enough destination that puts the sample in its daytime, which is fine for the
 * only question asked of it — whose place was this — and is why the field is named for the sample
 * rather than for sleep.
 *
 * Pure and Android-free; nothing is persisted. Travels re-derive from the same inputs as the stays
 * they sit on, so history backfills and deletions self-heal the same way.
 */
object TravelDeriver {

    /**
     * The hour (solar-local) each night is sampled at. Deep enough into the night that an evening
     * out and a pre-dawn departure both fall outside it.
     */
    const val NIGHT_SAMPLE_HOUR = 3

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** Where one night's sample landed. [UNKNOWN] is a night the data cannot place. */
    private enum class NightPlace { HOME, AWAY, UNKNOWN }

    /**
     * Home, in the two forms the rule needs it. [clusterIds] answers almost every night — a stay and
     * a gap both carry the cluster they belong to, so being home is a set membership rather than a
     * measurement. [seeds] carries the same homes as pin-and-reach, ordered with the most slept-in
     * first: the head's longitude anchors the calendar of nights, and the reaches answer the one case
     * with no cluster to read, a night spent in motion.
     */
    data class Home(
        val clusterIds: Set<Int>,
        val seeds: List<PlaceClusterer.Seed>,
    ) {
        val isKnown: Boolean get() = seeds.isNotEmpty()
    }

    data class Travel(
        /** First and last night away, inclusive, as solar-local dates anchored on the primary home. */
        val firstNight: LocalDate,
        val lastNight: LocalDate,
        /** The instants those two nights were sampled at — the only bounds a reader on another
         *  clock can use, since the dates above are this rule's own. See [daysCovered]. */
        val firstNightAt: Long,
        val lastNightAt: Long,
        /**
         * When a home was last left and next reached — the bounds of the home stays either side.
         * **Not necessarily the same home**: a journey between two of them is bounded by leaving one
         * and arriving at the other. Null where the history has no such stay to name (it begins or
         * ends mid-travel).
         */
        val leftHomeAt: Long?,
        val reachedHomeAt: Long?,
        /**
         * The journey's own bounds — [leftHomeAt] and [reachedHomeAt] with their fallbacks resolved,
         * so a reader attributing anything else to this journey measures it against the same edges
         * [clusterStayMs] was measured against.
         */
        val windowStart: Long,
        val windowEnd: Long,
        /** Time spent per endpoint cluster between [leftHomeAt] and [reachedHomeAt], for naming the
         *  travel after where it was actually spent. Keys index [StayDeriver.Derivation.clusters]. */
        val clusterStayMs: Map<Int, Long>,
    ) {
        val nightCount: Int get() = (ChronoUnit.DAYS.between(firstNight, lastNight) + 1).toInt()
    }

    /**
     * The offset a longitude implies, 15° to the hour. Not a political zone and not trying to be —
     * see the rule on [TravelDeriver].
     */
    fun solarOffsetOf(lon: Double): ZoneOffset =
        ZoneOffset.ofTotalSeconds((lon / 15.0 * 3600).roundToInt().coerceIn(-64_800, 64_800))

    /**
     * Home, most nights held first. Places tagged [PlaceCategory.HOME] are the answer whenever any
     * exist: **a declaration beats an inference**, a tagged home being a statement about someone's
     * life while the fallback is an argument from where they slept, and the two disagree exactly when
     * the user has said something the data cannot see. With nothing tagged, the single cluster that
     * held the most nights stands in. An unknown home ([Home.isKnown]) leaves travels underivable
     * rather than guessed.
     *
     * A tagged place always has a cluster of its own — pins seed the clustering — so its capture
     * radius is what decides which endpoints count as being there, exactly as everywhere else.
     */
    fun homeOf(
        places: List<Place>,
        clusters: List<PlaceClusterer.Cluster>,
        intervals: List<StayDeriver.Interval>,
    ): Home {
        val nightsPerCluster = IntArray(clusters.size)
        for (interval in intervals) {
            if (interval !is StayDeriver.Stay) continue
            val end = interval.end ?: continue
            // The cluster's centroid, a stay carrying no position of its own: the mean of what the
            // spot's endpoints measured, which is the nearest thing to a longitude it has.
            val cluster = clusters.getOrNull(interval.clusterId) ?: continue
            nightsPerCluster[interval.clusterId] += nightsWithin(interval.start, end, cluster.centroid.lon)
        }
        val tagged = places.withIndex()
            .filter { (_, place) -> place.placeCategory == PlaceCategory.HOME }
            .map { (index, place) -> clusters.indexOfFirst { it.seedIndex == index } to place }
            .sortedByDescending { (clusterId, _) -> nightsPerCluster.getOrElse(clusterId) { 0 } }
        if (tagged.isNotEmpty()) {
            return Home(
                clusterIds = tagged.mapNotNull { (clusterId, _) -> clusterId.takeIf { it >= 0 } }.toSet(),
                seeds = PlaceClusterer.seedsOf(tagged.map { (_, place) -> place }),
            )
        }
        val best = nightsPerCluster.indices.maxByOrNull { nightsPerCluster[it] }
        if (best == null || nightsPerCluster[best] == 0) return Home(emptySet(), emptyList())
        return Home(setOf(best), listOf(clusters[best].seed))
    }

    /**
     * Everything a night can be placed from: the derived intervals with the clusters they index
     * into, plus the tracks — whose own hours no interval covers, and which are therefore the only
     * witness to a night spent moving.
     */
    data class Timeline(
        val derivation: StayDeriver.Derivation,
        val tracks: List<StayDeriver.TrackEnd>,
    )

    /** The travels in [timeline], oldest first. An unknown [home] leaves nothing to be away from. */
    fun derive(
        timeline: Timeline,
        home: Home,
        nowMs: Long,
        distance: DistanceFn,
    ): List<Travel> {
        if (!home.isKnown) return emptyList()
        return Nights(timeline, home, nowMs, distance).travels()
    }

    /** One night, placed. */
    private class Sample(
        val date: LocalDate,
        val at: Long,
        val place: NightPlace,
    )

    /** One derivation's worth of state, so the passes below can read it rather than pass it along. */
    private class Nights(
        val timeline: Timeline,
        val home: Home,
        val nowMs: Long,
        val distance: DistanceFn,
    ) {
        private val tracks get() = timeline.tracks
        private val intervals get() = timeline.derivation.intervals
        private val stays = intervals.filterIsInstance<StayDeriver.Stay>()
        private val offset = solarOffsetOf(home.seeds.first().anchor.lon)

        private fun placeOf(clusterId: Int?): NightPlace = when (clusterId) {
            null -> NightPlace.UNKNOWN
            in home.clusterIds -> NightPlace.HOME
            else -> NightPlace.AWAY
        }

        fun travels(): List<Travel> {
            val firstAt = minOf(
                intervals.firstOrNull()?.start ?: Long.MAX_VALUE,
                tracks.firstOrNull()?.startedAt ?: Long.MAX_VALUE,
            )
            val lastAt = maxOf(
                intervals.lastOrNull()?.let { it.end ?: nowMs } ?: Long.MIN_VALUE,
                tracks.lastOrNull()?.endedAt ?: Long.MIN_VALUE,
            )
            if (firstAt > lastAt) return emptyList()

            val nights = mutableListOf<Sample>()
            var date = dateAt(firstAt, offset)
            val lastDate = dateAt(lastAt, offset)
            while (!date.isAfter(lastDate)) {
                val at = sampleInstant(date, offset)
                if (at in firstAt..lastAt) nights += sample(date, at)
                date = date.plusDays(1)
            }
            return runsIn(nights)
        }

        private fun sample(date: LocalDate, at: Long): Sample = when (val interval = covering(at)) {
            is StayDeriver.Stay -> Sample(date, at, placeOf(interval.clusterId))
            is StayDeriver.Gap -> acrossGap(date, at, interval)
            else -> inMotion(date, at)
        }

        // Nights are sampled in order and the intervals and tracks are in order, so both searches
        // are cursors that only move forward — a scan per night is a walk of the whole history per
        // night, and a history holds thousands of each.
        private var intervalCursor = 0
        private var trackCursor = 0

        private fun covering(at: Long): StayDeriver.Interval? {
            while (intervalCursor < intervals.size && (intervals[intervalCursor].end ?: nowMs) <= at) {
                intervalCursor++
            }
            return intervals.getOrNull(intervalCursor)?.takeIf { at >= it.start }
        }

        private fun trackCovering(at: Long): StayDeriver.TrackEnd? {
            while (trackCursor < tracks.size && tracks[trackCursor].endedAt < at) trackCursor++
            return tracks.getOrNull(trackCursor)?.takeIf { at >= it.startedAt }
        }

        /**
         * Nothing observed the middle, so the sides are the whole of the evidence: a night both ends
         * agree about is placed there, and one they disagree about cannot be placed at all — the move
         * happened somewhere inside, and guessing where would invent the very thing the gap records
         * as missing.
         *
         * [StayDeriver.GapReason] is deliberately not consulted: an UNKNOWN_ENDPOINT gap is one whose
         * endpoint had no coordinates, so that side carries no cluster either and falls out as
         * [NightPlace.UNKNOWN] on its own. Reading the reason would encode a second time what the ids
         * already say.
         */
        private fun acrossGap(date: LocalDate, at: Long, gap: StayDeriver.Gap): Sample {
            val from = placeOf(gap.fromClusterId)
            val to = placeOf(gap.toClusterId)
            return Sample(date, at, if (from == to) from else NightPlace.UNKNOWN)
        }

        /**
         * A night spent in motion — a night drive, a red-eye — which no interval covers, and the one
         * night with no cluster to read. It is home only while inside a home's own capture area, the
         * same reach that would have claimed a stop there. Its position is interpolated along the
         * track's bounds rather than read from its fixes: this asks only whose ground someone was on,
         * and the point walk that would answer it exactly runs over millions of rows.
         */
        private fun inMotion(date: LocalDate, at: Long): Sample {
            val track = trackCovering(at)
            val from = track?.start
            val to = track?.end
            if (track == null || from == null || to == null) {
                return Sample(date, at, NightPlace.UNKNOWN)
            }
            val span = (track.endedAt - track.startedAt).coerceAtLeast(1)
            val fraction = (at - track.startedAt).toDouble() / span
            val point = Coordinate(
                lat = from.lat + (to.lat - from.lat) * fraction,
                lon = from.lon + (to.lon - from.lon) * fraction,
            )
            val held = PlaceClusterer.nearestSeedIndex(point.lat, point.lon, home.seeds, distance) != null
            return Sample(date, at, if (held) NightPlace.HOME else NightPlace.AWAY)
        }

        private fun startOfDay(date: LocalDate): Long =
            date.atStartOfDay().toInstant(offset).toEpochMilli()

        private fun runsIn(nights: List<Sample>): List<Travel> {
            val out = mutableListOf<Travel>()
            var i = 0
            while (i < nights.size) {
                if (nights[i].place != NightPlace.AWAY) {
                    i++
                    continue
                }
                // A run reaches through nights that could not be placed but cannot end on one: an
                // unplaced night between two away ones is part of the same journey, while a trailing
                // one is the boundary the data stops short of, and claiming it as away would be the
                // guess the gap rule just refused to make.
                var last = i
                var j = i + 1
                while (j < nights.size && nights[j].place != NightPlace.HOME) {
                    if (nights[j].place == NightPlace.AWAY) last = j
                    j++
                }
                out += travelOf(nights.subList(i, last + 1))
                i = last + 1
            }
            return out
        }

        private fun travelOf(run: List<Sample>): Travel {
            fun atHome(stay: StayDeriver.Stay) = stay.clusterId in home.clusterIds
            val leftHomeAt = stays.lastOrNull { (it.end ?: nowMs) <= run.first().at && atHome(it) }?.end
            val reachedHomeAt = stays.firstOrNull { it.start >= run.last().at && atHome(it) }?.start
            // **A journey's figures come from the journey's own days and no others.** Those are the
            // days [daysCovered] marks — the day before the first night through the day the last one
            // ends on — measured here on the same solar clock the nights were placed by.
            //
            // The home stays either side then trim within them, never past them. They cannot be
            // trusted to bound anything on their own: the evening someone flies, the recorder's next
            // track is at the far end, so the interval covering that night at home is a gap rather
            // than a stay, and the last stay *at* home can be the morning before — which would hand
            // the journey a whole day spent near home. A journey nothing has come home from runs to
            // the end of its last day.
            val firstDay = startOfDay(run.first().date.minusDays(1))
            val lastDay = startOfDay(run.last().date.plusDays(1))
            val windowStart = maxOf(leftHomeAt ?: Long.MIN_VALUE, firstDay)
            val windowEnd = minOf(reachedHomeAt ?: nowMs, lastDay)
            val clusterStayMs = clusterStayMsWithin(stays, windowStart, windowEnd, nowMs)
            return Travel(
                firstNight = run.first().date,
                lastNight = run.last().date,
                firstNightAt = run.first().at,
                lastNightAt = run.last().at,
                leftHomeAt = leftHomeAt,
                reachedHomeAt = reachedHomeAt,
                windowStart = windowStart,
                windowEnd = windowEnd,
                clusterStayMs = clusterStayMs,
            )
        }
    }

    /**
     * Time per cluster within `[windowStart, windowEnd)` — the rule [Travel.clusterStayMs] is
     * measured by.
     *
     * The overlap, not only the stays wholly inside: one straddling either edge spent real time in
     * the window, and the stay someone is in *right now* ends at [nowMs], past every bound a
     * finished journey has. Containment alone would name a journey in progress after wherever its
     * traveller was the day before.
     */
    private fun clusterStayMsWithin(
        stays: List<StayDeriver.Stay>,
        windowStart: Long,
        windowEnd: Long,
        nowMs: Long,
    ): Map<Int, Long> {
        val clusterStayMs = mutableMapOf<Int, Long>()
        for (stay in stays) {
            val overlap = overlapMs(stay.start, stay.end ?: nowMs, windowStart, windowEnd)
            if (overlap <= 0L) continue
            clusterStayMs[stay.clusterId] = (clusterStayMs[stay.clusterId] ?: 0L) + overlap
        }
        return clusterStayMs
    }

    /**
     * The days [travel] covers in [zone], for a screen that groups by that zone to mark.
     * **Always one more than it has nights**: a night sampled at 03:00 on a date is the evening
     * before running into that morning, so n nights are spread across n+1 days — the day of setting
     * out, then one day per night.
     *
     * Read off the night samples' own instants, and off nothing else. The obvious alternative,
     * running the cover from when home was left to when it was next reached, breaks on a hole in the
     * history: the last home stay before a journey can sit a fortnight back with every night between
     * unplaceable, and a four-night trip then reports as a fortnight away. The instants, never
     * [Travel.firstNight] itself, because those dates are on this rule's solar clock and a screen
     * slicing days in the device's would mark the wrong one at either edge.
     */
    fun daysCovered(travel: Travel, zone: ZoneId): List<LocalDate> {
        var date = Instant.ofEpochMilli(travel.firstNightAt - DAY_MS).atZone(zone).toLocalDate()
        val lastDate = Instant.ofEpochMilli(travel.lastNightAt).atZone(zone).toLocalDate()
        val days = mutableListOf<LocalDate>()
        while (!date.isAfter(lastDate)) {
            days += date
            date = date.plusDays(1)
        }
        return days
    }

    /** How many night samples fall inside [start]..[end], on the solar clock at [lon]. */
    private fun nightsWithin(start: Long, end: Long, lon: Double): Int {
        val offset = solarOffsetOf(lon)
        var date = dateAt(start, offset)
        val lastDate = dateAt(end, offset)
        var count = 0
        while (!date.isAfter(lastDate)) {
            if (sampleInstant(date, offset) in start..end) count++
            date = date.plusDays(1)
        }
        return count
    }

    private fun dateAt(epochMs: Long, offset: ZoneOffset): LocalDate =
        Instant.ofEpochMilli(epochMs).atOffset(offset).toLocalDate()

    private fun sampleInstant(date: LocalDate, offset: ZoneOffset): Long =
        date.atTime(LocalTime.of(NIGHT_SAMPLE_HOUR, 0)).toInstant(offset).toEpochMilli()
}
