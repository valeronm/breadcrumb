package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.MIN
import io.github.valeronm.breadcrumb.domain.TrackOrigin
import io.github.valeronm.breadcrumb.domain.Trip
import io.github.valeronm.breadcrumb.domain.TripFocus
import io.github.valeronm.breadcrumb.domain.trackSummary
import io.github.valeronm.breadcrumb.ui.TrackAction.CHANGE_TYPE
import io.github.valeronm.breadcrumb.ui.TrackAction.DELETE
import io.github.valeronm.breadcrumb.ui.TrackAction.DELETE_PART
import io.github.valeronm.breadcrumb.ui.TrackAction.EDIT_TRIP
import io.github.valeronm.breadcrumb.ui.TrackAction.SHARE
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackActionTest {

    private fun actions(
        source: TrackOrigin = TrackOrigin.RECORDED,
        endedAt: Long? = 10 * MIN,
        partOfTrip: Boolean = false,
        canOpenForm: Boolean = true,
        canDelete: Boolean = true,
    ) = trackActions(
        trackSummary(1, ActivityType.WALKING.name, 0, endedAt, source = source.code),
        partOfTrip,
        canOpenForm,
        canDelete,
    )

    @Test fun `a recorded track offers its type, a share and a delete`() {
        assertEquals(listOf(CHANGE_TYPE, SHARE, DELETE), actions())
    }

    @Test fun `an entered track opens the form that entered it`() {
        assertEquals(listOf(EDIT_TRIP, SHARE, DELETE), actions(source = TrackOrigin.MANUAL))
    }

    @Test fun `an entered track on a page with no form offers its type`() {
        assertEquals(listOf(CHANGE_TYPE, SHARE, DELETE), actions(source = TrackOrigin.MANUAL, canOpenForm = false))
    }

    @Test fun `a part of a longer trip deletes alone`() {
        assertEquals(listOf(CHANGE_TYPE, SHARE, DELETE_PART), actions(partOfTrip = true))
    }

    @Test fun `a page that cannot delete offers no delete`() {
        assertEquals(listOf(CHANGE_TYPE, SHARE), actions(canDelete = false))
    }

    @Test fun `a track still recording offers no delete`() {
        assertEquals(listOf(CHANGE_TYPE, SHARE), actions(endedAt = null))
    }

    private val part = trackSummary(1, ActivityType.WALKING.name, 0, 10 * MIN)
    private val rest = trackSummary(2, ActivityType.WALKING.name, 10 * MIN, 20 * MIN, source = TrackOrigin.MANUAL.code)
    private val trip = Trip(listOf(part, rest))

    @Test fun `a whole trip offers its delete once it has ended`() {
        assertEquals(listOf(DELETE), pageActions(TripFocus.Whole(trip), canOpenForm = true, canDelete = true))
        val recording = Trip(listOf(part, rest.copy(endedAt = null)))
        assertEquals(emptyList<TrackAction>(), pageActions(TripFocus.Whole(recording), canOpenForm = true, canDelete = true))
    }

    @Test fun `a part of a trip offers its own actions and deletes alone`() {
        assertEquals(listOf(CHANGE_TYPE, SHARE, DELETE_PART), pageActions(TripFocus.Part(trip, part), canOpenForm = true, canDelete = true))
    }

    @Test fun `a lone track offers its own actions`() {
        assertEquals(listOf(CHANGE_TYPE, SHARE, DELETE), pageActions(TripFocus.Part(Trip(part), part), canOpenForm = true, canDelete = true))
    }
}
