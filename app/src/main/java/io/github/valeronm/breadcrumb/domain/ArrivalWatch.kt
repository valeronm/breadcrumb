package io.github.valeronm.breadcrumb.domain

/**
 * Notices that a journey opened without Play Services has ended: the ground has stood still long
 * enough that the stop reads as an arrival rather than a queue. The stop-side counterpart of
 * [DepartureWatch], and gated to the tracks that side opens — a track opened on a *reading* has a
 * live reporter whose own stop will end it (a lagging report is not a missing one, and edge-stay
 * trimming repairs the clock it lands late on), while a track opened on a *trigger* was opened
 * because that reporter said nothing, and waiting for its stop is waiting on the witness that
 * already failed.
 *
 * **Standstill evidence is held, not demanded live.** A parked phone starves the witness: fixes
 * thin out under min-distance sampling, the confirmer's window drains, and its verdict falls back
 * to [Motion.Unknown] within a minute of the very stop this exists to notice. So a [Motion.Stopped]
 * verdict opens a standstill and only positive contrary evidence — [Motion.Moving] — closes it;
 * [Motion.Unknown] moves nothing either way, which is also the consultation invariant: with the
 * witness silent throughout, the slot never opens and this never fires.
 *
 * The price of held evidence is a fire on stale evidence: a journey that stops once and then
 * creeps on without ever earning a Moving verdict (sparse fixes, a queue oozing under the
 * witness's moving bar) closes a floor's wait after that stop. The close is recoverable where a
 * missed one is not: it arms the departure triggers at the last good fix, and the same machinery
 * that opened the track opens the next stretch when the ground provably leaves — onto this same row
 * where the stitch window still reaches it.
 *
 * **The floor is the stitch window, and never less than [STANDSTILL_FLOOR_MS].** The window and this
 * floor answer the same question — how long a stop stops being part of the trip — so the one setting
 * rules both witnesses; it is honored only upward because downward is not the setting's to give (see
 * the clamp).
 */
class ArrivalWatch {

    private var stoppedSinceMs = 0L

    /**
     * Judge the witness's verdict at [nowMs], against the caller's [stitchWindowMs]. Returns when
     * the standstill began, once it has stood the floor, and null while the journey is (or may still
     * be) under way. A fire spends the standstill: the next one takes a full fresh floor.
     */
    fun onMotion(motion: Motion, nowMs: Long, stitchWindowMs: Long): Long? {
        when (motion) {
            is Motion.Moving -> stoppedSinceMs = 0L
            Motion.Stopped -> if (stoppedSinceMs == 0L) stoppedSinceMs = nowMs
            Motion.Unknown -> Unit
        }
        val sinceMs = stoppedSinceMs
        if (sinceMs == 0L || nowMs - sinceMs < maxOf(stitchWindowMs, STANDSTILL_FLOOR_MS)) return null
        stoppedSinceMs = 0L
        return sinceMs
    }

    /** Forget the standstill: the track it described resumed or closed. */
    fun reset() {
        stoppedSinceMs = 0L
    }

    companion object {
        /**
         * The least standstill ground evidence can tell from a long red light — the clamp under
         * the resume window, not the floor itself. The AR path can honor a short window safely
         * because a body in a car at a light is still IN_VEHICLE; the ground has only duration to
         * separate a signal cycle from an arrival, so a window shorter than this (zero included,
         * which on the AR path means "always start a new track") must not be imported raw. Above
         * any signal's cycle, and the same order as the no-fix guard's patience — the recorder's
         * other on-the-way-down clock.
         */
        const val STANDSTILL_FLOOR_MS = 4 * 60_000L
    }
}
