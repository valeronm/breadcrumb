package io.github.valeronm.breadcrumb.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PrefTest {

    @Test fun `a drag across one step writes each new value once`() {
        val written = mutableListOf<Int>()
        val pref = Pref(initial = 60, default = 180) { written += it }

        listOf(60, 60, 120, 120, 120, 180).forEach(pref::set)

        assertEquals(listOf(120, 180), written)
        assertEquals(180, pref.value)
    }
}
