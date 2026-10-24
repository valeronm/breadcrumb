package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.TripPlaces.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

class TripPlacesTest {

    private fun place(id: Long, east: Double, label: String?) = Place(
        id = id,
        label = label,
        lat = ORIGIN_LAT,
        lon = lonAt(east),
        createdAt = 0,
        radiusM = PlaceClusterer.DEFAULT_RADIUS_M,
    )

    private fun plan(places: List<Place>, vararg ends: TripPlaces.End) =
        TripPlaces.plan(ends.toList(), places, flatDistance, createdAt = 0).outcomes.map { it::class to it.name }

    private fun end(east: Double, name: String? = null) = TripPlaces.End(at(east), name)

    @Test fun `a named place holding the pin keeps its own name, whatever the pin was picked by`() {
        val airport = place(1, 0.0, "Airport")
        assertEquals(
            listOf(Outcome.Held::class to "Airport", Outcome.Held::class to "Airport"),
            plan(listOf(airport), end(50.0, "Terminal 4"), end(-50.0)),
        )
    }

    @Test fun `a picked name names the unnamed place holding the pin`() {
        val unnamed = place(1, 0.0, null)
        val outcome = TripPlaces.plan(listOf(end(20.0, "Hotel")), listOf(unnamed), flatDistance, createdAt = 0).outcomes.single()
        assertEquals(unnamed.copy(label = "Hotel", source = PlaceOrigin.MANUAL.code), (outcome as Outcome.Names).row)
    }

    @Test fun `a picked name nothing holds founds a place at the pin, which a later end inside it shares`() {
        val outcomes = TripPlaces.plan(
            listOf(end(0.0, "Hotel"), end(100.0, "Hotel lobby")),
            emptyList(),
            flatDistance,
            createdAt = 7,
        ).outcomes
        val row = (outcomes[0] as Outcome.Creates).row
        assertEquals(listOf("Hotel", ORIGIN_LAT, lonAt(0.0), 7L), listOf(row.label, row.lat, row.lon, row.createdAt))
        assertEquals(Outcome.Shares("Hotel"), outcomes[1])
    }

    @Test fun `the first name given to an unnamed place is the one both ends show`() {
        val unnamed = place(1, 0.0, null)
        assertEquals(
            listOf(Outcome.Names::class to "Home", Outcome.Shares::class to "Home"),
            plan(listOf(unnamed), end(10.0, "Home"), end(-10.0, "House")),
        )
    }

    @Test fun `a place an earlier end names outranks a nearer unnamed one, as once named it will`() {
        val named = place(1, 0.0, null)
        val nearer = place(2, 120.0, null)
        assertEquals(
            listOf(Outcome.Names::class to "Hotel", Outcome.Shares::class to "Hotel"),
            plan(listOf(named, nearer), end(-20.0, "Hotel"), end(100.0)),
        )
    }

    @Test fun `an unnamed pin is no place unless a named one holds it`() {
        val unnamed = place(1, 0.0, null)
        assertEquals(
            listOf(Outcome.None::class to null, Outcome.None::class to null),
            plan(listOf(unnamed), end(10.0), end(5_000.0)),
        )
    }

    @Test fun `a blank name is no name, and an end with no pin is no place`() {
        assertEquals(
            listOf(Outcome.None::class to null, Outcome.None::class to null),
            plan(emptyList(), end(0.0, "  "), TripPlaces.End(null, "Hotel")),
        )
    }

    @Test fun `the seeds after a save carry the places it names and creates`() {
        val unnamed = place(1, 0.0, null)
        val seeds = TripPlaces.plan(listOf(end(20.0, "Hotel"), end(5_000.0, "Office")), listOf(unnamed), flatDistance, createdAt = 0).seeds
        assertEquals(listOf(true, true), seeds.map { it.named })
        assertEquals(listOf(at(0.0), at(5_000.0)), seeds.map { it.anchor })
    }

    @Test fun `an end carrying on a track is a point, whatever place it is at`() {
        assertEquals(TripPlaces.Marker.POINT, TripPlaces.markerOf(Outcome.Held(place(1, 0.0, "Home")), continues = true))
        assertEquals(TripPlaces.Marker.POINT, TripPlaces.markerOf(Outcome.None, continues = true))
    }

    @Test fun `an end that stops is its place where it has one, and a spot where not`() {
        assertEquals(TripPlaces.Marker.PLACE, TripPlaces.markerOf(Outcome.Held(place(1, 0.0, "Home")), continues = false))
        assertEquals(TripPlaces.Marker.PLACE, TripPlaces.markerOf(Outcome.Shares("Home"), continues = false))
        assertEquals(TripPlaces.Marker.SPOT, TripPlaces.markerOf(Outcome.None, continues = false))
    }
}
