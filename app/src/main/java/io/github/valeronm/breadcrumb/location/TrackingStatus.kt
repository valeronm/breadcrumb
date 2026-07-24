package io.github.valeronm.breadcrumb.location

import io.github.valeronm.breadcrumb.domain.ActivityType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Process-wide snapshot of the recorder, observed by the UI. */
object TrackingStatus {

    data class State(
        val tracking: Boolean = false,
        /** Confirmed activity, null while idle (not armed). The UI derives its label. */
        val activity: ActivityType? = null,
        val recording: Boolean = false,
        /** Id of the currently open track (recording or paused), or null. */
        val activeTrackId: Long? = null,
        val distanceMeters: Double = 0.0,
        val points: Int = 0,
        /** Wall-clock start of the current track, null when not recording. */
        val startedAtMillis: Long? = null,
        /** Latest good fix's speed (m/s) and altitude (m), null when unknown or not recording. */
        val speedMps: Float? = null,
        val altitudeM: Double? = null,
        /** True while the no-fix guard has GPS off, waiting for a resume signal. */
        val gpsSuspended: Boolean = false,
        /** When the no-fix guard switched GPS off; null while GPS is on. */
        val gpsSuspendedSinceMillis: Long? = null,
        /** While a track is auto-paused: its activity and the resume-window deadline. */
        val pausedActivity: ActivityType? = null,
        val pausedUntilMillis: Long? = null,
        /** When the latest raw activity reading arrived — proof detection is alive. */
        val lastReadingAtMillis: Long? = null,
        /** Activity detection has stopped responding and isn't recovering — see DeafnessWarning. */
        val deaf: Boolean = false,
        /** Last fix's accuracy radius (m) and whether the accuracy gate rejected it — feedback
         *  for the "waiting for GPS" card when fixes arrive but aren't good enough. */
        val lastFixAccuracyM: Float? = null,
        val lastFixRejectedByAccuracy: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    internal fun update(transform: (State) -> State) {
        // CAS loop, not a bare read-modify-write: callers race across the main thread and the
        // service's IO coroutines, and a lost update would leave a stale field until the next
        // publish.
        _state.update(transform)
    }

    internal fun reset() {
        _state.value = State()
    }
}
