package io.github.valeronm.breadcrumb.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityTypeTest {

    // --- Track grouping ------------------------------------------------------

    @Test
    fun `activities in the same motion family share a track`() {
        assertTrue(ActivityType.WALKING.sharesTrackWith(ActivityType.RUNNING))
        assertTrue(ActivityType.RUNNING.sharesTrackWith(ActivityType.WALKING))
        assertTrue(ActivityType.DRIVING.sharesTrackWith(ActivityType.TAXI))
        assertTrue(ActivityType.CYCLING.sharesTrackWith(ActivityType.CYCLING))
    }

    @Test
    fun `a cross-family switch splits the track`() {
        assertFalse(ActivityType.WALKING.sharesTrackWith(ActivityType.CYCLING))
        assertFalse(ActivityType.CYCLING.sharesTrackWith(ActivityType.DRIVING))
        assertFalse(ActivityType.DRIVING.sharesTrackWith(ActivityType.STILL))
        assertFalse(ActivityType.UNKNOWN.sharesTrackWith(ActivityType.WALKING))
    }

    // --- Persisted-name lookups ----------------------------------------------

    @Test
    fun `ofName resolves stored names and rejects strangers`() {
        assertEquals(ActivityType.TAXI, ActivityType.ofName("TAXI"))
        assertNull(ActivityType.ofName("HOVERCRAFT"))
        assertNull(ActivityType.ofName("walking")) // stored names are exact, not case-folded
    }

    @Test
    fun `labelFor uses the label for known names and title-cases legacy ones`() {
        assertEquals("Walking", ActivityType.labelFor("WALKING"))
        assertEquals("Stationary", ActivityType.labelFor("STILL"))
        assertEquals("Hovercraft", ActivityType.labelFor("HOVERCRAFT"))
    }
}
