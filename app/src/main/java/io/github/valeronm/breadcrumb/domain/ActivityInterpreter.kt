package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.ActivityType

/**
 * The decision logic behind [ActivityTransitionReceiver]: given an already-unpacked Activity-
 * Recognition reading, decide what (if anything) to forward to the recorder. Pure and Android-free —
 * the receiver keeps the Intent unpacking, Play Services calls, and logging; this just decides.
 */
object ActivityInterpreter {

    sealed interface TransitionDecision {
        /** Tells us nothing about the new state (EXIT of a non-moving activity); drop it. */
        data object Ignore : TransitionDecision

        /** Forward [activity] to the recorder. [exitMapped] = an EXIT of a moving activity read as STILL. */
        data class Forward(val activity: ActivityType, val exitMapped: Boolean = false) : TransitionDecision
    }

    /**
     * Interpret the latest transition event. ENTER sets the activity directly; EXIT of a moving
     * activity is an early "stopped" hint mapped to STILL; EXIT of anything else is uninformative.
     * Honored at any age — transitions are the only signal, so even a laggy one is worth acting on.
     */
    fun interpretTransition(detected: ActivityType, isExit: Boolean): TransitionDecision = when {
        !isExit -> TransitionDecision.Forward(detected)
        detected.recording -> TransitionDecision.Forward(ActivityType.STILL, exitMapped = true)
        else -> TransitionDecision.Ignore
    }

    /**
     * A snapshot's most-probable activity, if it's confident enough to act on — else null to ignore.
     * Bidirectional (a confident reading drives the state either way); UNKNOWN and low-confidence
     * readings (e.g. a cold engine reporting a flat distribution) are dropped.
     *
     * The snapshot only exists to cover the gap between arming and the first transition. Once any
     * transition has been applied since arming ([transitionApplied]), the transition stream is
     * authoritative and the snapshot is dropped: it samples the *raw* classifier with none of the
     * transition API's smoothing, so a momentary state the transition machinery would ride out —
     * STILL at a red light, say, arriving moments after a replayed ENTER IN_VEHICLE — would pause
     * (and eventually discard) a genuine drive.
     */
    fun interpretSnapshot(
        mostProbable: ActivityType,
        confidence: Int,
        confidenceThreshold: Int,
        transitionApplied: Boolean,
    ): ActivityType? = when {
        transitionApplied -> null
        confidence >= confidenceThreshold && mostProbable != ActivityType.UNKNOWN -> mostProbable
        else -> null
    }
}
