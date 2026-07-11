package io.github.valeronm.breadcrumb.location

import io.github.valeronm.breadcrumb.data.ActivityType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    internal fun update(transform: (State) -> State) {
        _state.value = transform(_state.value)
    }

    internal fun reset() {
        _state.value = State()
    }
}
