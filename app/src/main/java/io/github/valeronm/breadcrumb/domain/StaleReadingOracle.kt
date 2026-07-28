package io.github.valeronm.breadcrumb.domain

/**
 * Decides whether a just-applied activity reading proves the Activity-Recognition registration has
 * gone silently deaf — GMS stopped delivering live transitions and this reading only reached us as
 * a registration replay. Purely the verdict: the caller rate-limits and issues the re-registration.
 * The signature: a reading whose event fired well in the past *while armed* ([staleMs] behind) that
 * also **advances** the reading clock past the last one applied — a live delivery runs only seconds
 * behind its event, so a stale-yet-advancing one can only be a replay of a transition we never got
 * live. The advance must clear [minAdvanceMs], not merely be positive: the event stamp is monotonic
 * (`elapsedRealTimeNanos`) but reconverted to wall-clock time via `currentTimeMillis()` on every
 * delivery, so the *same* replayed event, re-timed 50 minutes later, picks up a few milliseconds of
 * phantom advance from wall-clock drift over a long stationary stretch — enough to trip a bare `>`
 * and fire a spurious restart. A genuinely missed transition advances by seconds, far above the
 * floor, so a coarse threshold keeps every real catch while rejecting the jitter.
 */
object StaleReadingOracle {

    fun provesDeaf(
        eventTimeMs: Long?,
        readingMs: Long,
        lastReadingMs: Long,
        armedAtMs: Long,
        nowMs: Long,
        staleMs: Long,
        minAdvanceMs: Long,
    ): Boolean =
        eventTimeMs != null &&
            readingMs > lastReadingMs + minAdvanceMs &&
            readingMs > armedAtMs &&
            nowMs - readingMs > staleMs
}
