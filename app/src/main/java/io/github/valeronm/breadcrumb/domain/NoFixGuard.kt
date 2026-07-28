package io.github.valeronm.breadcrumb.domain

/**
 * Pure state machine for the no-fix give-up guard: a GPS probe that runs the configured window
 * without one accepted fix (indoors on an activity-recognition false positive, or parked
 * underground) turns GPS off to wait for a cheap resume signal — significant motion, a passive fix
 * from another app, or an activity transition. Consecutive failed probes back off ([retryBaseMs] ×
 * 2^(failures−1), capped at [retryCapMs]) so pacing around indoors doesn't degenerate into
 * GPS-always-on; only motion-triggered retries respect that gate, since a transition or a passive
 * fix is evidence in itself. Android-free like [ActivityGate]: clocks are injected (the recorder
 * passes elapsedRealtime), and the side effects — stopping/starting location updates, arming
 * sensors — stay in [io.github.valeronm.breadcrumb.location.LocationRecordingService].
 */
class NoFixGuard(
    private val retryBaseMs: Long = RETRY_BASE_MS,
    private val retryCapMs: Long = RETRY_CAP_MS,
) {

    /** True while GPS is handed off to the resume signals (track still active, GPS off). */
    var suspended = false
        private set

    private var probeStartedMs = 0L
    private var lastAcceptedMs = 0L
    private var failedProbes = 0
    private var nextProbeAllowedMs = 0L

    /** A GPS request just started — a fresh track, a stitch resume, or a retry probe. */
    fun onProbeStarted(nowMs: Long) {
        probeStartedMs = nowMs
        lastAcceptedMs = 0L
        suspended = false
    }

    /** A fix passed the quality gates; the receiver is clearly converged. */
    fun onFixAccepted(nowMs: Long) {
        lastAcceptedMs = nowMs
        failedProbes = 0
    }

    /**
     * Whether the current probe has run [giveUpMs] with nothing accepted — measured from the probe
     * start or the last accepted fix, whichever is later, so a long mid-track signal loss counts
     * too; [giveUpMs] ≤ 0 disables the guard. **A [Motion.Moving] verdict vetoes the give-up**: it
     * is positive evidence that fixes are arriving and the sky is visible, so the guard's premise —
     * GPS cannot get a fix here — is simply false. The guard is *likelier* to reach that state on a
     * carrier than anywhere else: [onFixAccepted] sees only fixes that passed the quality gates, so
     * a journey whose fixes are all rejected against a pedestrian ceiling starves the guard despite
     * perfect reception, and turning GPS off there would lose exactly the journey the cross-check
     * exists to keep. [motion] defaults to [Motion.Unknown] — also what the recorder passes with
     * the cross-check switched off — making the guard bit-for-bit the guard with no cross-check;
     * [Motion.Stopped] gives up like [Motion.Unknown], a standstill being no reason to keep a
     * fruitless probe running.
     */
    fun shouldGiveUp(nowMs: Long, giveUpMs: Long, motion: Motion = Motion.Unknown): Boolean =
        giveUpMs > 0 &&
            !suspended &&
            motion !is Motion.Moving &&
            nowMs - maxOf(probeStartedMs, lastAcceptedMs) >= giveUpMs

    /** Record the failed probe and suspend. Returns how long motion-triggered retries are gated. */
    fun onGaveUp(nowMs: Long): Long {
        failedProbes++
        val backoffMs = (retryBaseMs shl (failedProbes - 1).coerceAtMost(2)).coerceAtMost(retryCapMs)
        nextProbeAllowedMs = nowMs + backoffMs
        suspended = true
        return backoffMs
    }

    /** Whether a resume signal should start a probe now. Only motion respects the backoff gate. */
    fun shouldProbe(nowMs: Long, respectBackoff: Boolean): Boolean =
        suspended && (!respectBackoff || nowMs >= nextProbeAllowedMs)

    /** The track paused or closed — GPS is off for its own reasons; nothing to resume into. */
    fun onStopped() {
        suspended = false
    }

    /** A new track opened: its probes start with a fresh failure count. */
    fun onTrackOpened() {
        failedProbes = 0
    }

    companion object {
        const val RETRY_BASE_MS = 120_000L
        const val RETRY_CAP_MS = 480_000L
    }
}
