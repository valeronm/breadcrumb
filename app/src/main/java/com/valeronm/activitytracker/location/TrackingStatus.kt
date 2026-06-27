package com.valeronm.activitytracker.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Process-wide snapshot of the recorder, observed by the UI. */
object TrackingStatus {

    data class State(
        val tracking: Boolean = false,
        val activityLabel: String = "Idle",
        val recording: Boolean = false,
        val distanceMeters: Double = 0.0,
        val points: Int = 0,
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
