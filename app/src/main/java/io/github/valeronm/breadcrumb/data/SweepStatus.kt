package io.github.valeronm.breadcrumb.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide progress of a history sweep, observed by the timeline — the same bridge pattern as
 * `TrackingStatus`, and for the same reason: a sweep runs from `App.onCreate`, long before any
 * ViewModel exists, and outlives whatever screen happens to be open.
 *
 * It exists because a sweep is otherwise invisible work that changes what the user sees: it walks
 * the whole point history for half a minute while distances and end times shift behind it. Only one
 * runs at a time — `App.onCreate` starts them in sequence — so one flow carries whichever it is.
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
