package io.github.valeronm.breadcrumb.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TripChainsTest {
    private val drive = ActivityType.DRIVING.name
    private val manual = TrackOrigin.MANUAL.code

    private val enteredStart = trackSummary(1, drive, 60 * MIN, 70 * MIN, meters = 2_000.0, source = manual)
    private val recorded = trackSummary(2, drive, 70 * MIN, 100 * MIN, meters = 30_000.0)
    private val later = trackSummary(3, drive, 160 * MIN, 170 * MIN, meters = 5_000.0)

    /** Newest first, as the timeline holds them. */
    private val history = listOf(later, recorded, enteredStart)

    @Test fun `tracks sharing an instant and a type are one trip, oldest part first`() {
        assertEquals(listOf(Trip(later), Trip(listOf(enteredStart, recorded))), TripChains.of(history))
    }

    @Test fun `a part of another type stays a trip of its own`() {
        val walkedStart = enteredStart.copy(activityType = ActivityType.WALKING.name)
        assertEquals(3, TripChains.of(listOf(later, recorded, walkedStart)).size)
    }

    @Test fun `a trip is of the part not entered by hand, and adds up every part`() {
        val trip = Trip(listOf(enteredStart, recorded))
        assertEquals(recorded, trip.main)
        assertEquals(enteredStart.startedAt, trip.startedAt)
        assertEquals(recorded.endedAt, trip.endedAt)
        assertEquals(32_000.0, trip.distanceMeters, 0.0)
        assertEquals(enteredStart, Trip(enteredStart).main)
    }

    @Test fun `a trip is found by any of its parts`() {
        assertEquals(Trip(listOf(enteredStart, recorded)), TripChains.containing(history, enteredStart.id))
        assertEquals(Trip(listOf(enteredStart, recorded)), TripChains.containing(history, recorded.id))
        assertNull(TripChains.containing(history, 99))
    }

    @Test fun `a trip is one timeline row, and its day totals hold every part`() {
        val rows = TimelineRows.interleave(history, emptyList()).filterIsInstance<TimelineItem.TrackItem>()
        assertEquals(2, rows.size)

        val totals = TimelineTotalsBuilder().apply { add(rows[1], nowMs = 1_000 * MIN) }.build()
        assertEquals(32_000.0, totals.activities.getValue(drive).meters, 0.0)
    }

    @Test fun `a page opened on a part shows that part of its trip`() {
        val trip = Trip(listOf(enteredStart, recorded))
        assertEquals(TripFocus.Part(trip, enteredStart), TripChains.focus(history, enteredStart.id, whole = false))
    }

    @Test fun `a page asked for the whole trip shows it`() {
        val trip = Trip(listOf(enteredStart, recorded))
        assertEquals(TripFocus.Whole(trip), TripChains.focus(history, enteredStart.id, whole = true))
    }

    @Test fun `the whole trip of a lone track is that track`() {
        assertEquals(TripFocus.Part(Trip(later), later), TripChains.focus(history, later.id, whole = true))
    }

    @Test fun `a part retyped out of its trip shows alone`() {
        val walkedStart = enteredStart.copy(activityType = ActivityType.WALKING.name)
        assertEquals(
            TripFocus.Part(Trip(walkedStart), walkedStart),
            TripChains.focus(listOf(later, recorded, walkedStart), walkedStart.id, whole = false),
        )
    }

    @Test fun `a whole trip shows every part, as the trip`() {
        val trip = Trip(listOf(enteredStart, recorded))
        val whole = TripFocus.Whole(trip)
        assertEquals(trip, whole.shownTrip)
    }

    @Test fun `a part shows itself, as a trip of its own`() {
        val part = TripFocus.Part(Trip(listOf(enteredStart, recorded)), recorded)
        assertEquals(Trip(recorded), part.shownTrip)
    }

    @Test fun `a track no longer held shows nothing`() {
        assertNotNull(TripChains.focus(history, later.id, whole = false))
        assertNull(TripChains.focus(history, 99, whole = false))
    }
}
