package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.domain.TrackOrigin

internal enum class TrackAction { CHANGE_TYPE, EDIT_TRIP, SHARE, DELETE, DELETE_PART }

/**
 * The sheet lists them in this order. An entered track's whole content is what its form asks for,
 * type included; any other track's fixes are a measurement, so only its type is the user's. A track
 * still recording has no settled row to delete.
 */
internal fun trackActions(
    summary: TrackSummary,
    partOfTrip: Boolean,
    canOpenForm: Boolean,
    canDelete: Boolean,
): List<TrackAction> = buildList {
    val entered = TrackOrigin.fromCode(summary.source) == TrackOrigin.MANUAL
    add(if (entered && canOpenForm) TrackAction.EDIT_TRIP else TrackAction.CHANGE_TYPE)
    add(TrackAction.SHARE)
    if (canDelete && summary.endedAt != null) add(if (partOfTrip) TrackAction.DELETE_PART else TrackAction.DELETE)
}
