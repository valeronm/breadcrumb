package io.github.valeronm.breadcrumb.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PlotColumnTest {

    private fun columns(
        values: List<Float?>,
        pixelsPerValue: Float,
        breakAt: Set<Int> = emptySet(),
    ) = plotColumns(values, xOf = { it * pixelsPerValue }, breaksBefore = { it in breakAt })

    @Test fun `a series sparser than the pixels keeps every value as its own column`() {
        val got = columns(listOf(3f, 5f, 4f), pixelsPerValue = 2.5f)

        assertEquals(
            listOf(
                PlotColumn(first = 0, x = 0f, min = 3f, max = 3f, mean = 3f, nearestMean = 0, startsSegment = true),
                PlotColumn(first = 1, x = 2.5f, min = 5f, max = 5f, mean = 5f, nearestMean = 1, startsSegment = false),
                PlotColumn(first = 2, x = 5f, min = 4f, max = 4f, mean = 4f, nearestMean = 2, startsSegment = false),
            ),
            got,
        )
    }

    @Test fun `values sharing a pixel become one column with their range and mean`() {
        val got = columns(listOf(2f, 10f, 4f, 8f, 1f, 1f, 1f, 5f), pixelsPerValue = 0.25f)

        assertEquals(listOf(2f to 10f, 1f to 5f), got.map { it.min to it.max })
        assertEquals(listOf(6f, 2f), got.map { it.mean })
        assertEquals(listOf(0.375f, 1.375f), got.map { it.x })
    }

    @Test fun `a column names its value nearest the mean, the earlier of two equally near`() {
        val got = columns(listOf(2f, 10f, 4f, 8f), pixelsPerValue = 0.25f)

        assertEquals(2, got.single().nearestMean)
    }

    @Test fun `a break inside a pixel splits its column and starts a segment`() {
        val got = columns(listOf(1f, 2f, 3f, 4f), pixelsPerValue = 0.25f, breakAt = setOf(2))

        assertEquals(listOf(1.5f, 3.5f), got.map { it.mean })
        assertEquals(listOf(true, true), got.map { it.startsSegment })
    }

    @Test fun `a missing value breaks the line and joins no column`() {
        val got = columns(listOf(1f, null, 3f, 5f), pixelsPerValue = 0.25f)

        assertEquals(listOf(1f, 4f), got.map { it.mean })
        assertEquals(listOf(true, true), got.map { it.startsSegment })
    }

    @Test fun `a series with no values has no columns`() {
        assertEquals(emptyList<PlotColumn>(), columns(listOf(null, null), pixelsPerValue = 1f))
    }

    private fun drawnAt(values: List<Float?>, pixelsPerValue: Float, breakAt: Set<Int> = emptySet()) =
        drawnMembers(values, columns(values, pixelsPerValue, breakAt)).withIndex().filter { it.value }.map { it.index }

    @Test fun `every value on a joined line is drawn`() {
        assertEquals(listOf(0, 1, 2), drawnAt(listOf(3f, 5f, 4f), pixelsPerValue = 2f))
    }

    @Test fun `a stretch broken off on both sides inside one pixel is not drawn`() {
        val got = drawnAt(List(20) { 5f }, pixelsPerValue = 0.3f, breakAt = setOf(7, 10))
        assertEquals((0..19).toList() - (7..9).toSet(), got)
    }

    @Test fun `the same stretch spread across pixels is drawn`() {
        assertEquals((0..19).toList(), drawnAt(List(20) { 5f }, pixelsPerValue = 2f, breakAt = setOf(7, 10)))
    }

    @Test fun `a lone value between nulls is not drawn`() {
        assertEquals(listOf(0, 1, 5, 6), drawnAt(listOf(1f, 2f, null, 3f, null, 4f, 5f), pixelsPerValue = 2f))
    }
}
