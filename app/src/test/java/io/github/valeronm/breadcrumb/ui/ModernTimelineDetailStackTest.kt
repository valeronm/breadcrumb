package io.github.valeronm.breadcrumb.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ModernTimelineDetailStackTest {
    @Test
    fun `back returns through nested details before reaching day list`() {
        val day = listOf("day")
        val track = pushModernTimelineDetail(day, "track:17")
        val place = pushModernTimelineDetail(track, "place:home")

        assertEquals(listOf("day", "track:17"), popModernTimelineDetail(place))
        assertEquals(day, popModernTimelineDetail(track))
    }

    @Test
    fun `changing day clears detail stack and backing an empty stack is safe`() {
        val stack = pushModernTimelineDetail(listOf("day"), "track:17")

        assertEquals(emptyList<String>(), resetModernTimelineDetails<String>())
        assertEquals(emptyList<String>(), popModernTimelineDetail(emptyList<String>()))
        assertEquals(listOf("day"), popModernTimelineDetail(stack))
    }
}
