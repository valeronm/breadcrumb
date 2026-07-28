package io.github.valeronm.breadcrumb.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Names here are invented; the point is the shape real ones have (accents, chain + location). */
class PlaceSearchTest {

    @Test fun `matches anywhere in the name, not just its start`() {
        assertTrue(PlaceSearch.matches("Corner Shop Riverside", "riverside"))
        assertTrue(PlaceSearch.matches("Corner Shop Riverside", "corner"))
        assertTrue(PlaceSearch.matches("Corner Shop Riverside", "shop riv"))
    }

    @Test fun `case doesn't matter`() {
        assertTrue(PlaceSearch.matches("Corner Shop", "CORNER"))
        assertTrue(PlaceSearch.matches("CORNER SHOP", "corner"))
    }

    /** The reachable-from-the-keyboard rule: an unaccented query finds an accented name. */
    @Test fun `an unaccented query finds an accented name`() {
        assertTrue(PlaceSearch.matches("Padaria São José", "sao jose"))
        assertTrue(PlaceSearch.matches("Estação Central", "estacao"))
        assertTrue(PlaceSearch.matches("Ótica do Miradouro", "otica"))
    }

    /** …and symmetrically, so a user who does type the accent isn't punished for it. */
    @Test fun `an accented query finds an unaccented name`() {
        assertTrue(PlaceSearch.matches("Clinica Moderna", "clínica"))
    }

    @Test fun `a blank query matches nothing`() {
        assertFalse(PlaceSearch.matches("Corner Shop", ""))
        assertFalse(PlaceSearch.matches("Corner Shop", "   "))
    }

    @Test fun `a non-matching query matches nothing`() {
        assertFalse(PlaceSearch.matches("Corner Shop", "airport"))
    }

    @Test fun `surrounding whitespace in the query is ignored`() {
        assertTrue(PlaceSearch.matches("Corner Shop", "  shop "))
    }
}
