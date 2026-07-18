package io.github.valeronm.breadcrumb.domain

/**
 * Sanitizes Activity-Recognition event timestamps into the reading time fed to [ActivityGate].
 *
 * Event times let the gate judge resume-vs-new-track by when things actually *happened* rather
 * than when the (possibly Doze-delayed) apply ran: a stop and a return drained together from a
 * frozen queue must not read as a quick return when they were really ten minutes apart. But the
 * stamps can't be trusted blindly: they range from small negatives (future skew) to a 22.5-hour
 * "ago" on an event delivered live. Rules:
 *  - missing / future / older than [maxAgeMs] stamps fall back to [nowMs] (garbage in, wall
 *    clock out — an age cap far above any real drain delay, so legitimate replays still count);
 *  - readings never regress: they're clamped monotonically non-decreasing, so a stale stamp can
 *    never re-open a grace window an earlier reading already moved past.
 */
class ReadingClock {

    /** The last sanitized reading time; lets callers tell a clock-advancing reading from a repeat. */
    var lastReadingMs = Long.MIN_VALUE
        private set

    fun sanitize(eventMs: Long?, nowMs: Long, maxAgeMs: Long): Long {
        val plausible =
            if (eventMs == null || eventMs > nowMs || nowMs - eventMs > maxAgeMs) nowMs else eventMs
        val reading = maxOf(plausible, lastReadingMs)
        lastReadingMs = reading
        return reading
    }
}
