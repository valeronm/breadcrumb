package io.github.valeronm.breadcrumb.location.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StepHistoryTest {

    @Test fun `a time between readings reads the earlier one`() {
        val history = StepHistory.EMPTY.with(1_000, 10).with(5_000, 14)
        assertEquals(10L, history.totalAt(4_999))
        assertEquals(14L, history.totalAt(5_000))
    }

    @Test fun `the last reading holds however long the counter stays silent`() {
        val history = StepHistory.EMPTY.with(1_000, 10)
        assertEquals(10L, history.totalAt(3_600_000))
    }

    @Test fun `a time before the first reading has no total`() {
        val history = StepHistory.EMPTY.with(1_000, 10)
        assertNull(history.totalAt(999))
    }

    @Test fun `readings past the retention keep only their newest, as the anchor`() {
        val history = StepHistory.EMPTY.with(0, 1).with(100_000, 2).with(800_000, 3)
        assertEquals(2L, history.totalAt(150_000))
        assertNull(history.totalAt(50_000))
    }

    @Test fun `a reading older than the newest is refused`() {
        val history = StepHistory.EMPTY.with(5_000, 14).with(1_000, 10)
        assertNull(history.totalAt(2_000))
        assertEquals(14L, history.totalAt(6_000))
    }

    @Test fun `a window counts the steps between its two ends`() {
        val history = StepHistory.EMPTY.with(0, 100).with(10_000, 104).with(40_000, 140)
        assertEquals(36L, history.countedInWindowTo(40_000))
        assertEquals(4L, history.countedInWindowTo(39_999))
    }

    @Test fun `a counter silent through the window counts nothing`() {
        val history = StepHistory.EMPTY.with(0, 100)
        assertEquals(0L, history.countedInWindowTo(3_600_000))
    }

    @Test fun `a window starting before the first reading has no count`() {
        val history = StepHistory.EMPTY.with(10_000, 100).with(20_000, 100)
        assertNull(history.countedInWindowTo(35_000))
        assertEquals(0L, history.countedInWindowTo(40_000))
    }

    @Test fun `a window holding a counter restart has no count`() {
        val history = StepHistory.EMPTY.with(0, 5_000).with(20_000, 3).with(25_000, 5)
        assertNull(history.countedInWindowTo(30_000))
        assertEquals(2L, history.countedInWindowTo(50_000))
    }
}
