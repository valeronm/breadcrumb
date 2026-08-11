package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint

/**
 * Where a track's clock starts and stops: at its first and last usable fix, never at the moment the
 * recorder opened or closed the row. Those two are transition times — a track opens when Activity
 * Recognition reports movement and closes when it reports the end of it — and either can sit minutes
 * from the nearest fix, since GPS is asked for one only after the open and can go silent (a garage, a
 * tunnel, indoors) long before the close. Left alone that span claims a whereabouts nothing measured,
 * and claims it *from the interval next door*: every minute the track holds is a minute the stay
 * around it does not, so an arrival reads late and the place someone walked into is credited to the
 * road outside it.
 *
 * An **ignored** fix is not usable, and that is what makes this one rule rather than two. The
 * quality gate's rejects and [EdgeStayIgnore]'s flagged overrun are both already off the path, so
 * pulling the clock to the outermost *good* fix lands it exactly on the cut wherever an overrun was
 * found — [EdgeStayDetector.EdgeStay.boundaryTs] is by construction the last good fix the track
 * keeps — while also covering the case that rule must decline: no fixes at all, which is starvation
 * rather than an arrival and so nothing a detector reading fixes may place a boundary in.
 *
 * Reading the points alone is also what lets a withdrawn overrun flag re-open the clock onto the fix
 * it hands back, though nobody stored the recorder's original bound. The two fixes this lands on are
 * the ones whose coordinates [io.github.valeronm.breadcrumb.data.TrackStats] stores beside these
 * timestamps; the row's clock and its endpoints are meant to describe the same pair.
 *
 * A change here is swept into stored history by `EdgeStayDetector.RULE_VERSION`, which versions the
 * verdicts of the one pass that settles flags and clock together. Pure and Android-free.
 */
object TrackBounds {

    data class Bounds(val startedAt: Long, val endedAt: Long)

    /**
     * [points] is all of a track's points in time order, good and ignored alike; [startedAt] and
     * [endedAt] its current bounds, handed straight back when no fix survives to speak for them.
     */
    fun of(points: List<TrackPoint>, startedAt: Long, endedAt: Long): Bounds {
        val first = points.firstOrNull { !it.ignored } ?: return Bounds(startedAt, endedAt)
        return Bounds(first.timestamp, points.last { !it.ignored }.timestamp)
    }
}
