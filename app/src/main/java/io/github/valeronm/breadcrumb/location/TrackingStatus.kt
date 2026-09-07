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
        /** The track being recorded into, null while none is open. Can briefly disagree with
         *  [recording] around a track's finalization. */
        val openTrack: OpenTrack? = null,
        val distanceMeters: Double = 0.0,
        val points: Int = 0,
        /** Latest good fix's speed (m/s) and altitude (m), null when unknown or not recording. */
        val speedMps: Float? = null,
        val altitudeM: Double? = null,
        /** True while the no-fix guard has GPS off, waiting for a resume signal. */
        val gpsSuspended: Boolean = false,
        /** When the no-fix guard switched GPS off; null while GPS is on. */
        val gpsSuspendedSinceMillis: Long? = null,
        /** When the latest raw activity reading arrived — proof detection is alive. */
        val lastReadingAtMillis: Long? = null,
        /** Activity detection has stopped responding and isn't recovering — see DeafnessWarning. */
        val deaf: Boolean = false,
        /** Last fix's accuracy radius (m) and whether the accuracy gate rejected it — feedback
         *  for the "waiting for GPS" card when fixes arrive but aren't good enough. */
        val lastFixAccuracyM: Float? = null,
        val lastFixRejectedByAccuracy: Boolean = false,
    )

    /**
     * [startedAt] is the row's, which for a continued track precedes the stretch that resumed it.
     * [label] is the row's too, and the ground never overrules it the way it overrules
     * [State.activity] to "Moving".
     */
    data class OpenTrack(
        val id: Long,
        val label: ActivityType,
        val startedAt: Long,
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
