package io.github.valeronm.breadcrumb.ui

import kotlin.math.abs
import kotlin.math.floor

/** The plotted values that share one pixel column of a graph. */
internal data class PlotColumn(
    /** Index of the column's first member. */
    val first: Int,
    /** Mean of the members' own x: a column of one sits exactly where its point does. */
    val x: Float,
    val min: Float,
    val max: Float,
    val mean: Float,
    /** Index of the member whose value is nearest [mean]. */
    val nearestMean: Int,
    /** Nothing is drawn from the column before this one. */
    val startsSegment: Boolean,
)

/**
 * Groups a series into pixel columns: consecutive values whose [xOf] falls in the same whole pixel
 * and that no break separates. A null value breaks the line, and so does [breaksBefore] an index.
 * A track denser than one value per pixel otherwise draws several segments in every column.
 */
internal fun plotColumns(
    values: List<Float?>,
    xOf: (Int) -> Float,
    breaksBefore: (Int) -> Boolean,
): List<PlotColumn> {
    val columns = ArrayList<PlotColumn>()
    var first = 0
    while (first < values.size) {
        if (values[first] == null) {
            first++
            continue
        }
        val pixel = floor(xOf(first)).toInt()
        fun joins(i: Int) = values[i] != null && !breaksBefore(i) && floor(xOf(i)).toInt() == pixel
        var end = first + 1
        while (end < values.size && joins(end)) end++
        val startsSegment = first == 0 || values[first - 1] == null || breaksBefore(first)
        columns += plotColumn(values, xOf, first, end, startsSegment)
        first = end
    }
    return columns
}

private fun plotColumn(
    values: List<Float?>,
    xOf: (Int) -> Float,
    first: Int,
    end: Int,
    startsSegment: Boolean,
): PlotColumn {
    var min = Float.POSITIVE_INFINITY
    var max = Float.NEGATIVE_INFINITY
    var sum = 0.0
    var xSum = 0.0
    for (i in first until end) {
        val v = checkNotNull(values[i])
        if (v < min) min = v
        if (v > max) max = v
        sum += v
        xSum += xOf(i)
    }
    val count = end - first
    val mean = (sum / count).toFloat()
    var nearest = first
    var nearestGap = Float.POSITIVE_INFINITY
    for (i in first until end) {
        val gap = abs(checkNotNull(values[i]) - mean)
        if (gap < nearestGap) {
            nearestGap = gap
            nearest = i
        }
    }
    return PlotColumn(first, (xSum / count).toFloat(), min, max, mean, nearest, startsSegment)
}

/**
 * Index for index with [values]: true where the value belongs to a column a line is drawn to or
 * from.
 */
internal fun drawnMembers(values: List<Float?>, columns: List<PlotColumn>): BooleanArray {
    val drawn = BooleanArray(values.size)
    for (k in columns.indices) {
        val joined = !columns[k].startsSegment || (k + 1 < columns.size && !columns[k + 1].startsSegment)
        if (!joined) continue
        val end = if (k + 1 < columns.size) columns[k + 1].first else values.size
        for (i in columns[k].first until end) drawn[i] = values[i] != null
    }
    return drawn
}
