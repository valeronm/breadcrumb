package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.DistanceFn
import io.github.valeronm.breadcrumb.domain.IgnoreReason
import io.github.valeronm.breadcrumb.domain.Motion

/**
 * Pure track geometry and fix-quality math over recorded [TrackPoint]s — distance, bounding extent,
 * per-point speed, and the "bad fix" rule — all host-testable via an injectable [DistanceFn].
 * [TrackQuality.badFixReason] is the single source of truth for which fixes are unreliable, applied
 * by live recording ([io.github.valeronm.breadcrumb.location.LocationRecordingService]) as each fix
 * is ingested: flagged fixes drop out of distance, the rendered line and exports, but stay stored —
 * the count of ignored points is itself a signal a track is questionable. The reasons are
 * [IgnoreReason], shared with the domain's edge-stay rule.
 */
object TrackQuality {

    /** A position delta below this (meters) over a zero/negative time gap isn't a real jump. */
    private const val MIN_JUMP_M = 10.0

    /**
     * Plausible upper-bound ground speed (km/h) for a fix recorded under [activity], used to reject
     * teleports — raised to fit [motion] when the position stream has positively measured the
     * ground moving faster than the label allows for. The label describes the user's body; a
     * carried journey moves the ground underneath it — aboard a vessel or train the body is walking
     * or still, and judging the deck's speed by a pedestrian's ceiling rejects the whole crossing
     * as teleports: accurately recorded fixes, discarded for disagreeing with a label that was
     * never about the journey. Three properties keep the raise from becoming a licence: it only
     * ever raises (the greater of the two is taken — a verdict can hand a fix the benefit of the
     * doubt, never withdraw one the label already granted); [MOTION_CEILING_FACTOR] is generous on
     * purpose ([Motion.Moving.speedMps] is a window *average*, and a carrier accelerating or
     * rounding a headland is instantaneously well above it — a tight margin would reject exactly
     * those fixes); and it is clamped to the most permissive ceiling any activity carries, because
     * a lone teleport is fed to the confirmer like any other fix (the feed contract that keeps the
     * witness from inheriting the error it checks) and can carry a window to [Motion.Moving] on its
     * own — at worst a fix is judged as though the track were a drive, a ceiling the recorder
     * already grants on a label alone. [Motion.Unknown] — the cross-check switched off, or the sky
     * blocked — leaves the label's ceiling exactly as it was.
     */
    fun jumpCeilingKmh(activity: ActivityType, motion: Motion = Motion.Unknown): Double {
        val label = labelCeilingKmh(activity)
        val moving = motion as? Motion.Moving ?: return label
        return maxOf(label, derivedCeilingKmh(moving))
    }

    /**
     * Whether [motion] overrules [activity] — the measured ground speed is one the label cannot
     * explain at all. Compared against the label's ceiling **unmargined**, unlike [jumpCeilingKmh]:
     * the margin exists so a generous ceiling admits a fix the window average lags behind, and being
     * generous there costs nothing, because the worst case is a fix kept. Here the generosity is
     * spent the other way — this is the recorder claiming out loud that the journey is not what the
     * label says — and at [MOTION_CEILING_FACTOR] a brisk walk clears WALKING's ceiling on the
     * margin alone, which is the claim being made about most walks. A ceiling may guess; a statement
     * may not.
     */
    fun motionOverrules(activity: ActivityType, motion: Motion): Boolean {
        val moving = motion as? Motion.Moving ?: return false
        return moving.speedKmh > labelCeilingKmh(activity)
    }

    /** The ceiling the measured pace argues for — margined for window-average lag, clamped. */
    private fun derivedCeilingKmh(moving: Motion.Moving): Double =
        (moving.speedKmh * MOTION_CEILING_FACTOR).coerceAtMost(MAX_CEILING_KMH)

    /**
     * The most permissive ceiling in [activity]'s group — the bar the carrier-evidence speed
     * channel measures against, not the label's own: a run inside a walking-labelled track (same
     * group, keeps its label) sustains speeds above WALKING's ceiling, no human the group's.
     */
    fun groupCeilingKmh(activity: ActivityType): Double =
        ActivityType.entries.filter { it.trackGroup == activity.trackGroup }.maxOf { labelCeilingKmh(it) }

    private fun labelCeilingKmh(activity: ActivityType): Double = when (activity) {
        ActivityType.WALKING, ActivityType.STILL -> 12.0
        ActivityType.RUNNING -> 30.0
        ActivityType.CYCLING -> 70.0
        // Above any ferry afloat (a fast catamaran cruises ~70), and well under the road ceiling,
        // so a crossing's teleports are caught by a bar its own speeds can't reach.
        ActivityType.FERRY -> 120.0
        ActivityType.DRIVING, ActivityType.TAXI, ActivityType.UNKNOWN -> 220.0
    }

    /** How far above the observed window average a fix may still be believed. */
    private const val MOTION_CEILING_FACTOR = 2.5

    /** Derived, not written down: the clamp is "no more than some label already allows". */
    private val MAX_CEILING_KMH = ActivityType.entries.maxOf { labelCeilingKmh(it) }

    fun distanceMeters(a: TrackPoint, b: TrackPoint, distance: DistanceFn = AndroidDistance): Double =
        distance.meters(a.latitude, a.longitude, b.latitude, b.longitude)

    /**
     * A point list with the distance across each of its seams already walked: [meters] index 0 is
     * 0 (nothing precedes the first fix), index i is the metres from `points[i - 1]` to
     * `points[i]`. The one walk the track screen's per-point series are built from: per-point speed
     * and the speed series the map and graph are colored by is read off exactly these numbers, the ellipsoidal distance per
     * seam is the most expensive thing either does, and most fixes carry no GPS speed, so the speed
     * series really does need every seam. Sharing the walk also takes it off the colour-metric tap:
     * seam distances don't depend on the displayed metric, while the series drawn from them do.
     * Not a data class on purpose: an array field would give it identity equality wearing value
     * clothes, and its one use as a Compose `remember` key wants identity anyway.
     */
    class Seams(val points: List<TrackPoint>, val meters: DoubleArray)

    /** Walks [points] into [Seams]; [distance] is injectable so the derivation is host-testable. */
    fun seams(points: List<TrackPoint>, distance: DistanceFn = AndroidDistance): Seams {
        val out = DoubleArray(points.size)
        for (i in 1 until points.size) {
            out[i] = distanceMeters(points[i - 1], points[i], distance)
        }
        return Seams(points, out)
    }

    /**
     * Per-point speed in km/h — the GPS-reported speed where present (non-null and non-negative),
     * else derived from the previous point over the [Seams] walk — used to color the rendered track
     * by speed. A fix whose timestamp doesn't advance is *unmeasurable*, not stationary: there is
     * no elapsed time to divide by, calling it a standstill draws a stop the track never made, and
     * a GPX import storing every fix twice would render a motorway run as a per-second sawtooth
     * between real speed and zero on the graph and the map. Such a fix carries the last speed
     * forward; only a track's first point has nothing to carry, and that one is 0.
     */
    fun pointSpeedsKmh(seams: Seams): FloatArray {
        val points = seams.points
        val out = FloatArray(points.size)
        var prev: TrackPoint? = null
        for (i in points.indices) {
            val p = points[i]
            val reported = p.speed
            out[i] = when {
                reported != null && reported >= 0f -> reported * 3.6f
                // A non-positive gap is unmeasurable, not stationary: carry the last speed forward.
                prev != null -> seamSpeedKmh(prev, p, seams.meters[i])?.toFloat() ?: out[i - 1]
                else -> 0f
            }
            prev = p
        }
        return out
    }

    /** [pointSpeedsKmh] for a caller with no seam walk to share — it does its own. */
    fun pointSpeedsKmh(points: List<TrackPoint>, distance: DistanceFn = AndroidDistance): FloatArray =
        pointSpeedsKmh(seams(points, distance))

    /** First seam at least this fast (km/h) to be a candidate stray — an implausible launch from
     *  a drive start (40 km/h in a ~1 s opening seam is ~1 g of acceleration from standstill). */
    private const val LEADING_STRAY_MIN_KMH = 40.0

    /** …and at least this many times the following real pace, so genuine fast starts (first seam
     *  ≈ the rest of the track) aren't flagged — only the fast-first-then-slow stray shape is. */
    private const val LEADING_STRAY_FACTOR = 4.0

    /** How many seams after the first to sample for the track's real early pace. */
    private const val LEADING_STRAY_LOOKAHEAD = 4

    /** How many leading points [leadingPointIsJump] inspects — a prefix this long decides it. */
    const val LEADING_CHECK_POINT_COUNT = LEADING_STRAY_LOOKAHEAD + 1

    /** Speed (km/h) across a seam of [meters], or null when its time gap is non-positive (can't
     *  derive). */
    private fun seamSpeedKmh(a: TrackPoint, b: TrackPoint, meters: Double): Double? {
        val dtSec = (b.timestamp - a.timestamp) / 1000.0
        if (dtSec <= 0) return null
        return meters / dtSec * 3.6
    }

    /**
     * Whether the track opens with a stray point: the first seam's speed is implausibly high for a
     * drive *start* — well above [LEADING_STRAY_MIN_KMH] and at least [LEADING_STRAY_FACTOR]× the
     * real pace of the seams that follow (a car pulling out does a few km/h, not 180). The classic
     * recorder cold-start artifact — first fix far off, then a consistent track — common in
     * imported GPX, which bypasses live ingest filtering. An absolute jump ceiling misses it (a
     * stray commonly reads 40–200 km/h, under the driving ceiling yet impossible one second after
     * setting off); comparing against the track's own following pace catches the sub-ceiling cases
     * the forward jump check can't. Repair: ignore the first point.
     */
    fun leadingPointIsJump(
        points: List<TrackPoint>,
        distance: DistanceFn = AndroidDistance,
    ): Boolean {
        if (points.size < 3) return false
        fun seam(i: Int) = seamSpeedKmh(points[i - 1], points[i], distanceMeters(points[i - 1], points[i], distance))
        val firstSeam = seam(1) ?: return false
        if (firstSeam < LEADING_STRAY_MIN_KMH) return false
        val followPace = (2..LEADING_STRAY_LOOKAHEAD.coerceAtMost(points.size - 1))
            .mapNotNull { seam(it) }
            .maxOrNull() ?: return false
        return firstSeam >= LEADING_STRAY_FACTOR * followPace
    }

    /**
     * Everything outside the two points themselves that decides a fix's verdict — one field per
     * reason [badFixReason] can report: [gnssBacked] → [IgnoreReason.NO_GNSS], the cross-check's
     * verdict, or null when it's switched off (not the same as false — see [badFixReason]);
     * [maxAccuracyM] → [IgnoreReason.ACCURACY], the configured limit; [motion] →
     * [IgnoreReason.JUMP], what the position stream says the ground is doing, which with the
     * activity sets how high the jump gate stands (see [jumpCeilingKmh]) — [Motion.Unknown], the
     * default and what the recorder passes with the cross-check off, leaves that gate at the
     * activity's own height.
     */
    data class Gates(
        val maxAccuracyM: Float,
        val gnssBacked: Boolean? = null,
        val motion: Motion = Motion.Unknown,
    )

    /**
     * Whether [point] is a bad fix relative to the last accepted ("good") point, and why — or null
     * for a good fix. The whole rule lives here, all three reasons and the order between them:
     *  1. [IgnoreReason.NO_GNSS] — [Gates.gnssBacked] is false: no recent real satellite fix stands
     *     behind this position. Null is not false — off means "don't ask", false means "asked, and
     *     it isn't backed". Unbacked wins over the rest because a fabricated position's *other*
     *     numbers say nothing — a fused fix invented in a tunnel can report a tight accuracy radius
     *     and a plausible step.
     *  2. [IgnoreReason.ACCURACY] — the accuracy radius is at least [Gates.maxAccuracyM].
     *  3. [IgnoreReason.JUMP] — reaching [point] from [lastGood] needs an implausible speed for
     *     [activity]; teleports can carry good reported accuracy, so this is independent of gate 2.
     * [lastGood] is null for the first point of a track (or segment); [distance] is injectable so
     * the speed logic is host-testable. The GNSS evidence is a plain Boolean because deciding it is
     * `GnssSnapshot.backed`'s job — the caller reads two platform timestamps and nothing more.
     * [Gates.motion] reaches only the jump ceiling (see [jumpCeilingKmh]); its [Motion.Unknown]
     * default — also what the recorder passes with the cross-check switched off — makes the rule
     * behave as if no second witness existed. The two reasons above it are untouched by it on
     * purpose: they judge a fix on its own merits rather than against a label, and they are the
     * very gates that decide which fixes the position stream's witness may be fed at all.
     */
    fun badFixReason(
        lastGood: TrackPoint?,
        point: TrackPoint,
        activity: ActivityType,
        gates: Gates,
        distance: DistanceFn = AndroidDistance,
    ): IgnoreReason? {
        if (gates.gnssBacked == false) return IgnoreReason.NO_GNSS
        val accuracy = point.accuracy
        if (accuracy != null && accuracy >= gates.maxAccuracyM) return IgnoreReason.ACCURACY
        if (lastGood == null) return null
        return if (stepSpeedKmh(lastGood, point, distance) > jumpCeilingKmh(activity, gates.motion)) {
            IgnoreReason.JUMP
        } else {
            null
        }
    }

    /**
     * The speed (km/h) the step from [lastGood] to [point] implies — the one number the jump rule
     * judges, shared by the live check and the re-derivation below so the two can't drift. A
     * non-positive time gap can't be divided by: over one, a step longer than [MIN_JUMP_M] is
     * infinitely fast (nowhere to have travelled it in) and a shorter one is standing still.
     */
    private fun stepSpeedKmh(lastGood: TrackPoint, point: TrackPoint, distance: DistanceFn): Double {
        val gapMeters = distanceMeters(lastGood, point, distance)
        return seamSpeedKmh(lastGood, point, gapMeters)
            ?: if (gapMeters > MIN_JUMP_M) Double.MAX_VALUE else 0.0
    }

    /**
     * Which [IgnoreReason.JUMP] flags in [points] the [activity] ceiling accepts — the indices a
     * retype hands back to the path, by position in the list given (the
     * [EdgeStayIgnore][io.github.valeronm.breadcrumb.domain.EdgeStayIgnore] convention).
     * Withdraws flags, never adds them: a misdetected activity judged fixes by the wrong ceiling
     * (a drive taken for walking is measured against 12 km/h) and correcting it says the ceiling
     * was wrong, but retyping *down* would flag a real journey as noise on the strength of a label,
     * so there the rule stays silent and a track settles on the most permissive activity it has
     * ever carried. Three things the walk gets right that a per-point filter wouldn't: a restored
     * fix becomes the next fix's baseline, so a run of rejects unwinds from the front (the same
     * cascade the live rule produced going in); with no preceding good fix there is no step to
     * re-measure, so the flag stands — the imported leading stray ([leadingPointIsJump]), whose
     * verdict came from the track's own following pace and isn't a ceiling's to overturn; and
     * edge-stay fixes are good fixes (a phone that had already arrived, not a bad reading), so they
     * carry the baseline rather than stretching the gap across a stop. The ceiling is the *track's*
     * activity's, not a replay of the live pass, which gated each fix on the activity confirmed as
     * it arrived — within a group that can differ per fix (a run inside a walking track) — and
     * restore-only keeps the difference harmless: the coarser ceiling can hand fixes back, never
     * take them.
     */
    fun jumpRestores(
        points: List<TrackPoint>,
        activity: ActivityType,
        distance: DistanceFn = AndroidDistance,
    ): Set<Int> {
        val ceiling = jumpCeilingKmh(activity)
        val restores = mutableSetOf<Int>()
        var lastGood: TrackPoint? = null
        for ((i, point) in points.withIndex()) {
            if (!point.ignored || point.ignoreReason == IgnoreReason.EDGE_STAY.code) {
                lastGood = point
                continue
            }
            // Accuracy and no-GNSS rejections don't depend on the activity, and a legacy ignored
            // point (no reason stored) could be either — neither is this rule's to withdraw.
            if (point.ignoreReason != IgnoreReason.JUMP.code) continue
            val baseline = lastGood ?: continue
            if (stepSpeedKmh(baseline, point, distance) <= ceiling) {
                restores += i
                lastGood = point
            }
        }
        return restores
    }
}
