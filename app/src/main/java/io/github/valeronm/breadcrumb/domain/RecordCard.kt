package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.ActivityType

/** What the Record tab's main area shows. Decided by [recordCardState]. */
enum class RecordCardState {
    /** Auto-record is off — just the recorded period stats. */
    STATS_ONLY,

    /** Armed but the recording service hasn't come up yet. */
    STARTING,

    /** Armed and idle — waiting for a moving activity. */
    WAITING_FOR_MOVEMENT,

    /** A track is auto-paused: it resumes if the same activity returns within the window. */
    PAUSED,

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
    paused: Boolean,
    gpsSuspended: Boolean,
    points: Int,
    hasOpenTrack: Boolean,
): RecordCardState = when {
    !armed -> RecordCardState.STATS_ONLY
    recording && hasOpenTrack && points >= MIN_MAP_POINTS -> RecordCardState.LIVE_MAP
    recording && gpsSuspended -> RecordCardState.NO_GPS_SIGNAL
    recording -> RecordCardState.WAITING_FOR_GPS
    tracking && paused -> RecordCardState.PAUSED
    tracking -> RecordCardState.WAITING_FOR_MOVEMENT
    else -> RecordCardState.STARTING
}

/**
 * The one-line text of the recorder state card: the recording status leads, the blocker or
 * progress fact trails — "Recording walking · positioning ±78 m", "Paused · walking resumes
 * within 1m 40s", "Not recording · waiting for activity · none for 17m".
 * Pure: the UI supplies the clock/duration renderings ([formatClock] as "14:36", [formatDuration]
 * as "17m" / "2h 05m") so this composes phrases without touching Android formatters.
 */
fun recorderCardTitle(
    state: RecordCardState,
    nowMs: Long,
    activity: ActivityType?,
    pausedActivity: ActivityType?,
    pausedUntilMs: Long?,
    lastReadingAtMs: Long?,
    lastFixAccuracyM: Float?,
    lastFixRejectedByAccuracy: Boolean,
    gpsSuspendedSinceMs: Long?,
    formatClock: (Long) -> String,
    formatDuration: (Long) -> String,
): String = when (state) {
    RecordCardState.NO_GPS_SIGNAL ->
        "Recording${labelSuffix(activity)} · no GPS" +
            (gpsSuspendedSinceMs?.let { " since ${formatClock(it)}" } ?: "")
    RecordCardState.WAITING_FOR_GPS -> {
        // While the accuracy gate rejects fixes, the shrinking radius is the progress indicator.
        val radius = lastFixAccuracyM?.takeIf { lastFixRejectedByAccuracy }
            ?.let { " ±${it.toInt()} m" } ?: ""
        "Recording${labelSuffix(activity)} · positioning$radius"
    }
    RecordCardState.PAUSED -> {
        val left = pausedUntilMs?.let { it - nowMs }
        // Past the deadline nothing resumes into the track — the next activity starts a new
        // one — so it's idle in every way that matters to the user; only the close is pending.
        if (left != null && left <= 0) {
            idleTitle(nowMs, lastReadingAtMs, formatDuration)
        } else {
            val label = (pausedActivity ?: activity)?.label?.lowercase() ?: "activity"
            if (left != null) "Paused · $label resumes within ${formatCountdown(left)}"
            else "Paused · $label"
        }
    }
    RecordCardState.WAITING_FOR_MOVEMENT -> idleTitle(nowMs, lastReadingAtMs, formatDuration)
    else -> "Starting…"
}

/** The reading's age is how long there's been nothing to record; under a minute goes unsaid. */
private fun idleTitle(
    nowMs: Long,
    lastReadingAtMs: Long?,
    formatDuration: (Long) -> String,
): String {
    val quiet = lastReadingAtMs?.let { nowMs - it }?.takeIf { it >= 60_000 }
    return "Idle · waiting for activity" + (quiet?.let { " · none for ${formatDuration(it)}" } ?: "")
}

private fun labelSuffix(activity: ActivityType?): String =
    activity?.let { " ${it.label.lowercase()}" } ?: ""

/** "1m 40s" / "25s" — the pause card's live countdown, rounded up to whole seconds. */
fun formatCountdown(ms: Long): String {
    val totalSec = (ms + 999) / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}
