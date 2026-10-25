package io.github.valeronm.breadcrumb.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What a manual trip stores once a router has drawn its path, and how its two times relate: the
 * typed ends at their typed times with the router's shape between them, every fix timed by its
 * distance along the whole line at one constant speed. The ends keep the typed coordinates rather
 * than the router's, which are snapped to the nearest road: where a trip is stated to start is
 * where the user put the pin.
 */
object ManualPath {

    class Fix(val at: Coordinate, val timestampMs: Long)

    enum class End { ORIGIN, DESTINATION }

    /**
     * The line a manual trip is stored along: the typed ends with [shape] between them, a point
     * repeating its predecessor dropped so no two fixes share an instant.
     */
    fun line(from: Coordinate, to: Coordinate, shape: List<Coordinate>): List<Coordinate> {
        val line = ArrayList<Coordinate>(shape.size + 2)
        line += from
        for (c in shape) if (line.last() != c) line += c
        if (line.last() != to) line += to
        return line
    }

    fun lengthM(line: List<Coordinate>, distance: DistanceFn): Double {
        var total = 0.0
        for (i in 1 until line.size) total += distance.meters(line[i - 1].lat, line[i - 1].lon, line[i].lat, line[i].lon)
        return total
    }

    /** The shape [line] was built from: what a stored manual track holds between its ends. */
    fun shapeOf(line: List<Coordinate>): List<Coordinate> =
        if (line.size <= 2) emptyList() else line.subList(1, line.size - 1)

    /** [shape] timed between the typed ends [from] and [to] — see [line]. */
    fun fixes(from: Fix, to: Fix, shape: List<Coordinate>, distance: DistanceFn): List<Fix> {
        val line = line(from.at, to.at, shape)
        if (line.size < 2) return listOf(from, to)
        val along = DoubleArray(line.size)
        for (i in 1 until line.size) {
            val a = line[i - 1]
            val b = line[i]
            along[i] = along[i - 1] + distance.meters(a.lat, a.lon, b.lat, b.lon)
        }
        val total = along.last()
        val span = to.timestampMs - from.timestampMs
        return line.mapIndexed { i, c ->
            val at = when {
                i == 0 -> from.timestampMs
                i == line.lastIndex || total <= 0.0 -> to.timestampMs
                else -> from.timestampMs + Math.round(span * (along[i] / total))
            }
            Fix(c, at)
        }
    }

    /**
     * Where the other end lands when one end is set and the two are a road's [durationMs] apart:
     * after it when [forward], before it otherwise. With [roundToMinute], rounded to the nearest
     * minute, the resolution an end is typed at; nearest rather than down, so the span drifts from
     * the duration by the same half minute either way round.
     */
    fun coupledEnd(editedMs: Long, durationMs: Long, forward: Boolean, roundToMinute: Boolean = true): Long {
        val exact = if (forward) editedMs + durationMs else editedMs - durationMs
        return if (roundToMinute) Math.round(exact / MINUTE_MS.toDouble()) * MINUTE_MS else exact
    }

    /**
     * Which end keeps its time when a road's duration arrives, the other being coupled from it —
     * or null where both stand. One end timed: that one. Both timed, which is a gap's two bounds
     * where the recording left off and picked up: the time the road does not account for is spent
     * at a named place rather than at a spot nothing names, so the unnamed end keeps its bound and
     * the named one is coupled from it, leaving no stay at the unnamed place. Both named or
     * neither, both stand.
     */
    fun anchor(originTimed: Boolean, destinationTimed: Boolean, originNamed: Boolean, destinationNamed: Boolean): End? =
        when {
            originTimed != destinationTimed -> if (originTimed) End.ORIGIN else End.DESTINATION
            originTimed && originNamed != destinationNamed -> if (originNamed) End.DESTINATION else End.ORIGIN
            else -> null
        }

    /**
     * The ends of a trip departing at [departureMs] and arriving at [arrivalMs] that carry straight
     * on from a recorded track: the departure where the track before it ends at [previousEndMs], the
     * arrival where the one after starts at [nextStartMs]. An end on its track's bound leaves no stay
     * between the two; a minute either way does.
     */
    fun continuing(departureMs: Long?, arrivalMs: Long?, previousEndMs: Long?, nextStartMs: Long?): Set<End> =
        buildSet {
            if (departureMs != null && departureMs == previousEndMs) add(End.ORIGIN)
            if (arrivalMs != null && arrivalMs == nextStartMs) add(End.DESTINATION)
        }

    /** One side of a stay between a trip's end and a recorded track's. */
    class StaySide(val atMs: Long, val at: Coordinate, val placeId: Long? = null)

    /**
     * How long the timeline will show a stay from [left] to [right], or null where it will show
     * none or too short a one to print ([StayDeriver.REPORTABLE_DURATION_MS]). Cluster identity is
     * not asked, the trip's ends belonging to none until saved.
     */
    fun stayMs(left: StaySide, right: StaySide, agreement: StayDeriver.Agreement): Long? {
        val verdict = StayDeriver.verdictBetween(
            StayDeriver.TrackEnd(0, left.atMs, left.atMs, left.at, left.at, endPlaceId = left.placeId),
            StayDeriver.TrackEnd(0, right.atMs, right.atMs, right.at, right.at, startPlaceId = right.placeId),
            sameCluster = false,
            agreement = agreement,
        )
        return if (verdict == StayDeriver.Verdict.Stayed) StayDeriver.reportable(right.atMs - left.atMs) else null
    }

    /**
     * The days on [zone]'s clock an end of a trip can fall on between the track before it, ending at
     * [previousEndMs], and the one after, starting at [nextStartMs] — the trip may overlap neither.
     * Open on a side whose track is unknown; null where both are.
     */
    fun openDays(previousEndMs: Long?, nextStartMs: Long?, zone: ZoneId): ClosedRange<LocalDate>? {
        if (previousEndMs == null && nextStartMs == null) return null
        val from = previousEndMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: LocalDate.MIN
        val to = nextStartMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: LocalDate.MAX
        return from..to
    }

    private const val MINUTE_MS = 60_000L
}
