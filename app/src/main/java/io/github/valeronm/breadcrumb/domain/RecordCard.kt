package io.github.valeronm.breadcrumb.domain

/** What the Record tab's main area shows. Decided by [recordCardState]; strings live in the UI. */
enum class RecordCardState {
    /** Auto-record is off — just the recorded period stats. */
    STATS_ONLY,

    /** Armed but the recording service hasn't come up yet. */
    STARTING,

    /** Armed and idle/paused — waiting for a moving activity. */
    WAITING_FOR_MOVEMENT,

    /** Recording but the track has no drawable geometry yet (fewer than [MIN_MAP_POINTS] fixes). */
    WAITING_FOR_GPS,

    /** Recording but the no-fix guard has switched GPS off; waiting for a resume signal. */
    NO_GPS_SIGNAL,

    /** Recording with enough points to draw — the live map card. */
    LIVE_MAP,
}

/** A line needs two points; below this the map card has nothing to draw. */
const val MIN_MAP_POINTS = 2

/**
 * Pure decision for the Record tab's main area.
 *
 * The live map wins as soon as the open track is drawable — including while the no-fix guard is
 * suspended mid-track (there's real geometry to show; the guard state is the notification's job).
 * [hasOpenTrack] can briefly disagree with [recording] around track finalization; the waiting
 * states cover that gap rather than flashing an empty map.
 */
fun recordCardState(
    armed: Boolean,
    tracking: Boolean,
    recording: Boolean,
    gpsSuspended: Boolean,
    points: Int,
    hasOpenTrack: Boolean,
): RecordCardState = when {
    !armed -> RecordCardState.STATS_ONLY
    recording && hasOpenTrack && points >= MIN_MAP_POINTS -> RecordCardState.LIVE_MAP
    recording && gpsSuspended -> RecordCardState.NO_GPS_SIGNAL
    recording -> RecordCardState.WAITING_FOR_GPS
    tracking -> RecordCardState.WAITING_FOR_MOVEMENT
    else -> RecordCardState.STARTING
}
