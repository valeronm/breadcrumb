package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.domain.Coordinate
import kotlin.math.IEEErem
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.tan

/**
 * A line drawn with a casing: its fixes; for the leg arriving at each, its color and whether nobody watched it; and
 * whether its writer implied the motion. A line in one color is given that color alone rather than one per fix. It is
 * drawn in [runs] ([lineRuns]), inclusive ranges of its fixes.
 */
internal class CasedLine private constructor(
    /**
     * Each fix's longitude and latitude, interleaved ([lonLatOf]); never written, since whoever built the line may hold
     * it too.
     */
    val lonLat: DoubleArray,
    private val colors: IntArray,
    val unwatched: BooleanArray,
    val unmeasured: Boolean,
    val greatCircleLegs: Boolean,
    /** The fixes on the map's plane ([worldOf]), x and y interleaved. */
    private val world: DoubleArray,
) {
    constructor(
        lonLat: DoubleArray,
        colors: IntArray,
        unwatched: BooleanArray,
        unmeasured: Boolean,
        greatCircleLegs: Boolean,
    ) : this(lonLat, colors, unwatched, unmeasured, greatCircleLegs, worldOf(lonLat))

    constructor(
        positions: List<Coordinate>,
        colors: IntArray,
        unwatched: BooleanArray,
        unmeasured: Boolean,
        greatCircleLegs: Boolean,
    ) : this(lonLatOf(positions), colors, unwatched, unmeasured, greatCircleLegs)

    constructor(points: List<TrackPoint>, colors: IntArray, unmeasured: Boolean, greatCircleLegs: Boolean = false) : this(
        DoubleArray(points.size * 2) { if (it % 2 == 0) points[it / 2].longitude else points[it / 2].latitude },
        colors,
        BooleanArray(points.size) { points[it].segmentStart },
        unmeasured,
        greatCircleLegs,
    )

    init {
        require(colors.size == 1 || colors.size == lonLat.size / 2)
    }

    val positions: List<Coordinate> = coordinatesOf(lonLat)

    val runs: List<IntRange> = lineRuns(this)

    /** The color of the leg arriving at [fix]. */
    fun color(fix: Int): Int = colors[if (colors.size == 1) 0 else fix]

    fun x(fix: Int): Double = world[2 * fix]

    fun y(fix: Int): Double = world[2 * fix + 1]

    /** The square of how far apart fixes [a] and [b] lie on the map's plane. */
    fun squaredSpan(a: Int, b: Int): Double = squaredLength(x(b) - x(a), y(b) - y(a))

    fun without(cut: BooleanArray): CasedLine {
        val keptCount = cut.count { !it }
        if (keptCount == positions.size) return this
        val kept = IntArray(keptCount)
        var next = 0
        for (fix in cut.indices) if (!cut[fix]) kept[next++] = fix
        return CasedLine(
            DoubleArray(kept.size * 2) { lonLat[2 * kept[it / 2] + it % 2] },
            if (colors.size == 1) colors else IntArray(kept.size) { colors[kept[it]] },
            BooleanArray(kept.size) { unwatched[kept[it]] },
            unmeasured,
            greatCircleLegs,
            DoubleArray(kept.size * 2) { world[2 * kept[it / 2] + it % 2] },
        )
    }
}

/**
 * [lonLat]'s fixes on the Web Mercator plane the map draws, [WORLD_DP] wide: a distance there is the distance on screen
 * at zoom 0, at every latitude.
 */
private fun worldOf(lonLat: DoubleArray): DoubleArray {
    val world = DoubleArray(lonLat.size)
    for (i in lonLat.indices step 2) {
        world[i] = (lonLat[i] + HALF_TURN_DEG) / FULL_TURN_DEG * WORLD_DP
        world[i + 1] = ln(tan(PI / 4 + Math.toRadians(lonLat[i + 1]) / 2)) / (2 * PI) * WORLD_DP
    }
    return world
}

/**
 * Where the drawn line starts and stops, as inclusive index ranges into its fixes. A color change
 * shares its boundary fix between the run that ends on it and the one that starts from it, so the
 * line stays unbroken. A segment break does not: [unwatched] marks a fix whose arriving leg nobody
 * watched ([TrackPoint.segmentStart]), so the run ends before it, the next starts on it, and the leg
 * across is never drawn. A run needs two fixes to be a line, so a lone fix between two cuts draws
 * nothing.
 */
private fun lineRuns(line: CasedLine): List<IntRange> {
    val count = line.unwatched.size
    val runs = ArrayList<IntRange>()
    fun add(first: Int, untilExclusive: Int) {
        if (untilExclusive - first >= 2) runs.add(first..untilExclusive - 1)
    }
    var from = 0
    // A fix's color is its arriving leg's (TrackColoring.colors), so a run's color is the one at its
    // last index.
    for (arrival in 1 until count) {
        if (line.unwatched[arrival]) {
            add(from, arrival)
            from = arrival
        } else if (arrival >= 2 && line.color(arrival) != line.color(arrival - 1)) {
            add(from, arrival)
            from = arrival - 1
        }
    }
    add(from, count)
    return runs
}

/** Widest first: each pen covers the one before it but for a rim on either side. */
internal enum class Pen(val widthDp: Float) {
    CASING_BORDER(6.5f),
    CASING_BAND(5f),
    COLOR(TRACK_LINE_WIDTH),
}

/** A pen drawn along [fixes] of line [line], the [stretch]th of the stretches [casedStrokes] cut. */
internal class Stroke(val line: Int, val stretch: Int, val fixes: IntRange, val pen: Pen, val argb: Int, val dashed: Boolean)

/**
 * Every stroke [lines] are drawn with, in drawing order: line by line and stretch by stretch, so where a track
 * crosses itself, or a later part crosses an earlier one, the later pass covers the earlier one casing and all.
 *
 * Stretches are ordered in pieces, each reaching at least the casing's width at [zoom] from where it starts, and a
 * turn is read only between fixes that far apart: anything narrower draws as a ring across the line rather than as
 * one pass over another. A stop's flicker of colors is one piece. [zoom] is the lowest the strokes are drawn at.
 *
 * A piece's band is drawn after the next piece's border, and its color after the next piece's band, so where two
 * pieces meet a color change shows no ring of casing across the line. No order also lets a piece fully cover the one
 * two before it: its border is drawn under that piece's color. Where the line turns back on itself ([FOLD_TURN]), two
 * stretches overlap beyond their meeting fix, and the later one starts a piece drawn after every stroke before it.
 *
 * A dash is set per layer, so a dashed line's casing is drawn solid and in order, and only its color is dashed.
 */
internal fun casedStrokes(lines: List<CasedLine>, zoom: Int): List<Stroke> {
    val casing = casing(zoom)
    val stretches = lines.flatMapIndexed { index, line -> line.runs.flatMap { run -> stretchesOf(index, line, run, casing) } }
    val pens = Pen.entries
    val strokes = ArrayList<Stroke>(stretches.size * pens.size)
    // Each stroke's drawing key above its index, so a sort keeps the strokes of one key in the order they were made.
    val order = LongArray(stretches.size * pens.size)
    var step = 0
    // Where the open piece starts on the map's plane; NaN while none is open.
    var pieceX = Double.NaN
    var pieceY = Double.NaN
    stretches.forEachIndexed { k, stretch ->
        val line = lines[stretch.line]
        val folds = k > 0 && turn(stretches[k - 1].exit, stretch.entry) >= FOLD_TURN
        if (folds) {
            step += pens.size
        } else if (k > 0 && pieceX.isNaN()) {
            step++
        }
        if (folds || pieceX.isNaN()) {
            pieceX = line.x(stretch.fixes.first)
            pieceY = line.y(stretch.fixes.first)
        }
        if (line.reaches(stretch.fixes, pieceX, pieceY, casing)) {
            pieceX = Double.NaN
            pieceY = Double.NaN
        }
        for (pen in pens) {
            val argb = when (pen) {
                Pen.CASING_BORDER -> CASING_BORDER_ARGB
                Pen.CASING_BAND -> CASING_BAND_ARGB
                Pen.COLOR -> line.color(stretch.fixes.last)
            }
            order[strokes.size] = (pens.size * (step + pen.ordinal) + pen.ordinal).toLong() shl Int.SIZE_BITS or strokes.size.toLong()
            strokes += Stroke(stretch.line, k, stretch.fixes, pen, argb, dashed = pen == Pen.COLOR && line.unmeasured)
        }
    }
    order.sort()
    return List(order.size) { strokes[order[it].toInt()] }
}

private fun CasedLine.reaches(fixes: IntRange, fromX: Double, fromY: Double, distance: Double): Boolean {
    for (i in fixes) {
        if (squaredLength(x(i) - fromX, y(i) - fromY) >= distance * distance) return true
    }
    return false
}

/**
 * [line] as a zoom band draws it at [zoom] and closer: its narrow loops cut out ([cutLoops]), then each run kept to
 * both its ends and, between them, no fix nearer than half the casing's width to the one kept before it. MapLibre trims
 * a leg's ends back at a corner by a share of the line's width that grows with the angle, so a leg much shorter than
 * the line is wide folds over and shows as a spike.
 */
internal fun bandLine(line: CasedLine, zoom: Int): CasedLine {
    val looped = cutLoops(line, zoom)
    val cut = BooleanArray(looped.positions.size) { true }
    for (run in looped.runs) for (fix in spaced(looped, run, casing(zoom) / 2)) cut[fix] = false
    return looped.without(cut)
}

/** Both ends of [fixes], and between them none nearer than [gap] to the one kept before it or to the last. */
private fun spaced(line: CasedLine, fixes: IntRange, gap: Double): IntArray {
    val kept = IntArray(fixes.last - fixes.first + 2)
    var size = 0
    kept[size++] = fixes.first
    for (i in fixes.first + 1 until fixes.last) {
        if (line.squaredSpan(kept[size - 1], i) >= gap * gap) kept[size++] = i
    }
    if (size > 1 && line.squaredSpan(kept[size - 1], fixes.last) < gap * gap) size--
    kept[size++] = fixes.last
    return kept.copyOf(size)
}

/**
 * [line] with every loop narrower than the casing at [zoom] cut out, so the line passes straight through the loop's
 * crossing. Drawn whole, such a loop is a bead with no hole, and thinned ([bandLine]) a jog off the line. A loop is
 * looked for along each stretch of watched legs, across changes of color.
 */
internal fun cutLoops(line: CasedLine, zoom: Int): CasedLine {
    val size = line.positions.size
    val cut = BooleanArray(size)
    var start = 0
    for (k in 1..size) {
        if (k == size || line.unwatched[k]) {
            markLoops(line, start until k, casing(zoom), cut)
            start = k
        }
    }
    return line.without(cut)
}

/** Marks in [cut] the fixes inside each loop along [path] narrower than [width], the ends of its crossing legs kept. */
private fun markLoops(line: CasedLine, path: IntRange, width: Double, cut: BooleanArray) {
    val probes = spaced(line, path, width / LOOP_PROBES)
    var a = 0
    while (a < probes.lastIndex) {
        val b = loopEnd(line, probes, a, width)
        if (b == null) {
            a++
        } else {
            for (k in probes[a] + 1 until probes[b + 1]) cut[k] = true
            a = b + 1
        }
    }
}

/**
 * The probe whose leg crosses the leg from probe [a], the probes between staying narrower than [width]; null once they
 * spread wider first.
 */
private fun loopEnd(line: CasedLine, probes: IntArray, a: Int, width: Double): Int? {
    val start = probes[a + 1]
    var west = line.x(start)
    var east = west
    var south = line.y(start)
    var north = south
    for (b in a + 2 until probes.lastIndex) {
        val p = probes[b]
        west = min(west, line.x(p))
        east = max(east, line.x(p))
        south = min(south, line.y(p))
        north = max(north, line.y(p))
        if (squaredLength(east - west, north - south) >= width * width) return null
        if (crosses(line, probes[a], start, p, probes[b + 1])) return b
    }
    return null
}

/** Whether legs [a]–[b] and [c]–[d] of [line] cross, each passing strictly between the other's ends. */
private fun crosses(line: CasedLine, a: Int, b: Int, c: Int, d: Int): Boolean =
    side(line, c, d, a) * side(line, c, d, b) < 0 && side(line, a, b, c) * side(line, a, b, d) < 0

/** Which side of the leg [from]–[to] of [line] its fix [p] lies on. */
private fun side(line: CasedLine, from: Int, to: Int, p: Int): Double =
    sign((line.x(to) - line.x(from)) * (line.y(p) - line.y(from)) - (line.y(to) - line.y(from)) * (line.x(p) - line.x(from)))

/** Fixes of one line drawn as a unit, with the headings of its first and last legs: NaN where every leg has no length. */
private class Stretch(val line: Int, val fixes: IntRange, val entry: Double, val exit: Double)

/**
 * [run] cut wherever it has turned a quarter circle, the stretches either side sharing the fix. A stretch turning
 * less than a half circle cannot cross itself, and one held under a quarter cannot cross its neighbor but across a
 * sharp corner. A heading is read between fixes at least [shortestLeg] apart.
 */
private fun stretchesOf(index: Int, line: CasedLine, run: IntRange, shortestLeg: Double): List<Stretch> {
    val stretches = ArrayList<Stretch>(1)
    var start = run.first
    var anchor = run.first
    var entry = Double.NaN
    var heading = Double.NaN
    var turned = 0.0
    for (i in run.first + 1..run.last) {
        if (line.squaredSpan(anchor, i) < shortestLeg * shortestLeg) continue
        val next = atan2(line.y(i) - line.y(anchor), line.x(i) - line.x(anchor))
        if (heading.isNaN()) {
            entry = next
        } else {
            turned += turn(heading, next)
            if (turned >= PI / 2) {
                stretches += Stretch(index, start..anchor, entry, heading)
                start = anchor
                entry = next
                turned = 0.0
            }
        }
        heading = next
        anchor = i
    }
    stretches += Stretch(index, start..run.last, entry, heading)
    return stretches
}

private fun turn(from: Double, to: Double): Double = abs((to - from).IEEErem(2 * PI))

private fun squaredLength(dx: Double, dy: Double): Double = dx * dx + dy * dy

/** The casing's width at [zoom] on the map's plane ([worldOf]). */
private fun casing(zoom: Int): Double = Pen.CASING_BORDER.widthDp / 2.0.pow(zoom)

/** A turn past which two stretches meeting at a fix lie over each other for more than their caps. */
private const val FOLD_TURN = 2 * PI / 3
private const val WORLD_DP = 512.0
private const val HALF_TURN_DEG = 180.0
private const val FULL_TURN_DEG = 360.0

/** Probes per casing width: a loop narrower than one probe step goes unfound. */
private const val LOOP_PROBES = 16
private const val CASING_BORDER_ARGB = 0xFF000000.toInt()
private const val CASING_BAND_ARGB = 0xFFFFFFFF.toInt()
