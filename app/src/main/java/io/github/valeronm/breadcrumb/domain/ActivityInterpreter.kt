package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.ActivityType

/**
 * The decision logic behind [ActivityTransitionReceiver]: given an already-unpacked Activity-
 * Recognition reading, decide what (if anything) to forward to the recorder. Pure and Android-free —
 * the receiver keeps the Intent unpacking, Play Services calls, and logging; this just decides.
 */
object ActivityInterpreter {

    /** Staleness cutoff for transitions — mirrors the poll setting. */
    data class TransitionConfig(val pollEnabled: Boolean, val pollIntervalMs: Long)

    sealed interface TransitionDecision {
        /** Older than the poll refreshes — a (usually replayed) stale event; drop it. */
        data class Stale(val ageMs: Long) : TransitionDecision

        /** Tells us nothing about the new state (EXIT of a non-moving activity); drop it. */
        data object Ignore : TransitionDecision

        /** Forward [activity] to the recorder. [exitMapped] = an EXIT of a moving activity read as STILL. */
        data class Forward(val activity: ActivityType, val exitMapped: Boolean = false) : TransitionDecision
    }

    /**
     * Interpret the latest transition event. ENTER sets the activity directly; EXIT of a moving
     * activity is an early "stopped" hint mapped to STILL; EXIT of anything else is uninformative. A
     * transition older than the poll cadence is ignored *while the poll is on* (it's usually a stale
     * registration replay and the poll has a fresher reading); with the poll off it's honoured at any
     * age, since nothing else would recover that start/stop.
     */
    fun interpretTransition(
        detected: ActivityType,
        isExit: Boolean,
        ageMs: Long,
        config: TransitionConfig,
    ): TransitionDecision {
        if (config.pollEnabled && ageMs > config.pollIntervalMs) return TransitionDecision.Stale(ageMs)
        return when {
            !isExit -> TransitionDecision.Forward(detected)
            detected.recording -> TransitionDecision.Forward(ActivityType.STILL, exitMapped = true)
            else -> TransitionDecision.Ignore
        }
    }

    /**
     * A snapshot's most-probable activity, if it's confident enough to act on — else null to ignore.
     * Bidirectional (a confident reading drives the state either way); UNKNOWN and low-confidence
     * readings (e.g. a cold engine reporting a flat distribution) are dropped.
     */
    fun interpretSnapshot(mostProbable: ActivityType, confidence: Int, confidenceThreshold: Int): ActivityType? =
        if (confidence >= confidenceThreshold && mostProbable != ActivityType.UNKNOWN) mostProbable else null
}
