package io.github.valeronm.breadcrumb.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Hours here are arbitrary and invented; only their proportions matter to the rule. */
class TravelNamingTest {

    private val hour = 3_600_000L

    @Test fun `every city a journey was really spent in makes the name`() {
        val names = TravelNaming.ranked(
            linkedMapOf("Lisbon" to 20 * hour, "Porto" to 18 * hour, "Braga" to 16 * hour),
        )

        assertEquals(listOf("Lisbon", "Porto", "Braga"), names)
        assertEquals("Lisbon · Porto · Braga", TravelNaming.title(names))
    }

    @Test fun `a passing stop does not get to name the journey`() {
        // Half an hour on the way to a week is not one of the places the journey was to.
        val names = TravelNaming.ranked(
            linkedMapOf("Lisbon" to 140 * hour, "Service station town" to hour / 2),
        )

        assertEquals(listOf("Lisbon"), names)
    }

    @Test fun `an afternoon somewhere names the journey alongside the place it slept`() {
        // The shape of a one-night trip taken to see somewhere: the hotel holds the hours, and the
        // place the journey was actually for holds an afternoon. Both are where the traveller went.
        val names = TravelNaming.ranked(linkedMapOf("Hotel town" to 17 * hour, "Hill village" to 3 * hour))

        assertEquals(listOf("Hotel town", "Hill village"), names)
    }

    @Test fun `where nothing clears the floor the longest stay still names the journey`() {
        // A journey the recorder saw little of went somewhere all the same.
        val names = TravelNaming.ranked(linkedMapOf("Brief" to hour / 4, "Briefer" to hour / 10))

        assertEquals(listOf("Brief"), names)
    }

    @Test fun `one name repeated across two stays counts once, with its time summed`() {
        // The caller sums by name before ranking; what this pins is that a city slept in twice
        // outranks one slept in once, rather than being split into two smaller entries.
        val names = TravelNaming.ranked(linkedMapOf("Porto" to 10 * hour, "Lisbon" to 18 * hour))

        assertEquals(listOf("Lisbon", "Porto"), names)
    }

    @Test fun `beyond three, the name lists two and counts the rest`() {
        val names = TravelNaming.ranked(
            linkedMapOf(
                "Lisbon" to 10 * hour, "Porto" to 9 * hour,
                "Braga" to 8 * hour, "Coimbra" to 7 * hour, "Faro" to 6 * hour,
            ),
        )

        assertEquals(5, names.size)
        assertEquals("Lisbon · Porto +3", TravelNaming.title(names))
    }

    @Test fun `equal stays keep the order they were visited in`() {
        val names = TravelNaming.ranked(linkedMapOf("First" to 12 * hour, "Second" to 12 * hour))

        assertEquals(listOf("First", "Second"), names)
    }

    @Test fun `a journey nothing names has no title of its own`() {
        assertNull(TravelNaming.title(TravelNaming.ranked(emptyMap())))
    }

    @Test fun `a journey nothing names falls back to its nights, for the host to word`() {
        assertEquals(TravelLabel.NightsAway(3), TravelNaming.label(emptyList(), nightCount = 3))
    }

    @Test fun `a journey with destinations is headed by them, not by its nights`() {
        assertEquals(
            TravelLabel.Destinations("Somewhere"),
            TravelNaming.label(listOf("Somewhere"), nightCount = 3),
        )
    }

    @Test fun `names with no time at all are kept rather than filtered to nothing`() {
        // A journey whose stays are all zero-length still happened somewhere; the floor is a
        // proportion, and a proportion of nothing must not empty the list.
        val names = TravelNaming.ranked(linkedMapOf("Somewhere" to 0L))

        assertEquals(listOf("Somewhere"), names)
    }
}
