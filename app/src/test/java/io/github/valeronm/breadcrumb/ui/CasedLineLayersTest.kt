package io.github.valeronm.breadcrumb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CasedLineLayersTest {

    @Test fun `only the bands drawn at a zoom, or about to be, are in range there`() {
        assertEquals(listOf(0), bandsInRange(0f))
        assertEquals(listOf(6), bandsInRange(13f))
        assertEquals(listOf(10), bandsInRange(21f))
    }

    @Test fun `the band drawn at a zoom comes before the one whose head start it is in`() {
        assertEquals(listOf(6, 7), bandsInRange(13.7f))
        assertEquals(listOf(6, 5), bandsInRange(12.2f))
    }

    @Test fun `with every band ready, each is drawn from its own zoom up to the next's`() {
        val spans = bandSpans(ready(*CASED_ZOOMS.indices.toList().toIntArray()))

        assertEquals(BandSpan(0f, 2f), spans[0])
        assertEquals(BandSpan(12f, 14f), spans[6])
        assertEquals(BandSpan(20f, null), spans[10])
    }

    @Test fun `a zoom no ready band covers is drawn by the nearest farther one, or below them all the farthest`() {
        val spans = bandSpans(ready(6, 8))

        assertEquals(BandSpan(0f, 16f), spans[6])
        assertNull(spans[7])
        assertEquals(BandSpan(16f, null), spans[8])
    }

    @Test fun `the first band handed over is drawn at once`() {
        val handover = BandHandover()

        assertTrue(handover.handing(6))
        assertTrue(handover.ready[6])
    }

    @Test fun `a band handed over beside a drawn one is drawn once MapLibre has laid it out`() {
        val handover = BandHandover()
        handover.handing(6)

        assertFalse(handover.handing(7))
        assertFalse(handover.frameDrawn(whole = true, zoom = 13.7f))
        assertFalse(handover.frameDrawn(whole = false, zoom = 13.7f))
        assertFalse(handover.ready[7])
        assertTrue(handover.frameDrawn(whole = true, zoom = 13.7f))
        assertTrue(handover.ready[7])
    }

    @Test fun `frames drawn away from a band's zooms leave it waiting`() {
        val handover = BandHandover()
        handover.handing(6)
        handover.handing(7)

        handover.frameDrawn(whole = false, zoom = 5f)
        handover.frameDrawn(whole = true, zoom = 5f)
        assertFalse(handover.ready[7])
        handover.frameDrawn(whole = false, zoom = 13.7f)
        handover.frameDrawn(whole = true, zoom = 13.7f)
        assertTrue(handover.ready[7])
    }

    @Test fun `an emptied band is no longer drawn`() {
        val handover = BandHandover()
        handover.handing(6)

        assertTrue(handover.emptied(6))
        assertFalse(handover.ready[6])
    }

    private fun ready(vararg bands: Int) = BooleanArray(CASED_ZOOMS.size).also { for (band in bands) it[band] = true }
}
