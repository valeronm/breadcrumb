package io.github.valeronm.breadcrumb.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ActivityTypeTest {

    // --- Track grouping ------------------------------------------------------

    @Test
    fun `activities in the same motion family share a track`() {
        assertTrue(ActivityType.WALKING.sharesTrackWith(ActivityType.RUNNING))
        assertTrue(ActivityType.RUNNING.sharesTrackWith(ActivityType.WALKING))
        assertTrue(ActivityType.DRIVING.sharesTrackWith(ActivityType.TAXI))
        assertTrue(ActivityType.DRIVING.sharesTrackWith(ActivityType.FERRY))
        assertTrue(ActivityType.DRIVING.sharesTrackWith(ActivityType.TRANSIT))
        assertTrue(ActivityType.CYCLING.sharesTrackWith(ActivityType.CYCLING))
    }

    @Test
    fun `a cross-family switch splits the track`() {
        assertFalse(ActivityType.WALKING.sharesTrackWith(ActivityType.CYCLING))
        assertFalse(ActivityType.CYCLING.sharesTrackWith(ActivityType.DRIVING))
        assertFalse(ActivityType.DRIVING.sharesTrackWith(ActivityType.STILL))
        assertFalse(ActivityType.UNKNOWN.sharesTrackWith(ActivityType.WALKING))
        // AIR is its own family, so a flight never absorbs the drive to the airport.
        assertFalse(ActivityType.FLIGHT.sharesTrackWith(ActivityType.DRIVING))
    }

    // --- Persisted-name lookups ----------------------------------------------

    @Test
    fun `ofName resolves stored names and rejects strangers`() {
        assertEquals(ActivityType.TAXI, ActivityType.ofName("TAXI"))
        assertNull(ActivityType.ofName("HOVERCRAFT"))
        assertNull(ActivityType.ofName("walking")) // stored names are exact, not case-folded
    }

    /**
     * What a code *does* map to is named in the UI layer now, so only the fallback is testable
     * here — and it is the half with a rule worth pinning: title-cased in a fixed locale, since the
     * input is an enum name rather than language.
     */
    @Test
    fun `a legacy name title-cases itself, whatever the device locale`() {
        assertEquals("Hovercraft", ActivityType.legacyLabelFor("HOVERCRAFT"))
        assertEquals("Driving", ActivityType.legacyLabelFor("DRIVING"))
        val turkish = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            // A Turkish default lowercases `I` to a dotless `ı`; the fixed locale is what stops
            // `DRIVING` reading as `Drivıng`.
            assertEquals("Driving", ActivityType.legacyLabelFor("DRIVING"))
        } finally {
            Locale.setDefault(turkish)
        }
    }
}
