package io.github.valeronm.breadcrumb.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide progress of a history sweep, observed by the timeline — the `TrackingStatus` bridge
 * pattern, for the same reason: sweeps run from `App.onCreate`, before any ViewModel exists, and
 * outlive the open screen. Without it a sweep is invisible work that changes what the user sees —
 * half a minute walking the whole point history while distances and end times shift behind it.
 * Only one runs at a time (`App.onCreate` sequences them), so one flow carries whichever it is.
 */
object SweepStatus {

    /** Which rule is being re-derived. The user-facing wording lives in the banner, not here. */
    enum class Kind { EDGE_STAYS, STATS }

    data class Progress(val kind: Kind, val done: Int, val total: Int)

    private val _state = MutableStateFlow<Progress?>(null)

    /** Non-null only while a sweep is running. */
    val state: StateFlow<Progress?> = _state

    fun start(kind: Kind, total: Int) {
        _state.value = Progress(kind, 0, total)
    }

    fun advance(done: Int) {
        _state.value = _state.value?.copy(done = done)
    }

    fun finish() {
        _state.value = null
    }
}
