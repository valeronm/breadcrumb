package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.DistanceFn
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import java.time.Instant
import java.time.ZoneId

/**
 * Derives *stays* — where the user was between recorded tracks — from data the app already has,
 * at zero sensing cost. A stay is the interval between the end of one kept track and the start of
 * the next, located where their endpoint coordinates agree. Endpoint disagreement means movement
 * the recorder missed, and is reported as a [Gap] instead.
 *
 * Honesty rule: silence is only a stay if the app was alive and armed throughout — that evidence
 * is the liveness log ([Armed]/[Disarmed]/[Outage] events). When an outage or a disarm-rearm
 * interrupts an otherwise-agreeing interval, the stay is still emitted but marked
 * [Provenance.INFERRED] (the endpoints agree; the middle is unattested) rather than
 * [Provenance.OBSERVED]. History from before the liveness log existed derives the same way.
 *
 * Pure and Android-free; nothing is persisted — stays re-derive from tracks + liveness on read,
 * so history backfills automatically and track deletions self-heal.
 */
object StayDeriver {

    data class Endpoint(val lat: Double, val lon: Double)

    /** One kept track, projected to what derivation needs. Input list must be ascending by time. */
    data class TrackEnd(
        val trackId: Long,
        val startedAt: Long,
        val endedAt: Long,
        /** First/last good-point coordinates; null only defensively (kept tracks have ≥2 points). */
        val start: Endpoint?,
        val end: Endpoint?,
    )

    /** Recorder-lifecycle evidence, ascending by time. */
    sealed interface Liveness {
        val at: Long
    }

    data class Armed(override val at: Long) : Liveness
    data class Disarmed(override val at: Long) : Liveness

    /** The app was dead (or the phone off) from [at] to [until]. */
    data class Outage(override val at: Long, val until: Long) : Liveness

    data class Params(
        /** Endpoints at most this far apart (metres) count as "the same place". */
        val agreementRadiusM: Double = 100.0,
        /** Inter-track gaps shorter than this emit nothing. 0 = keep every stay (brief stops
         *  included) — the auto-pause resume window already absorbs the truly-momentary ones. */
        val minStayMs: Long = 0L,
        /** Heartbeat staleness after which a restart materializes an outage. Lives here as the
         *  single source of truth; the service reads it when calling materializeOutageIfDead. */
        val heartbeatToleranceMs: Long = 30 * 60_000L,
    )

    enum class Provenance { OBSERVED, INFERRED }

    enum class GapReason { MOVED_UNRECORDED, UNKNOWN_ENDPOINT }

    sealed interface Interval {
        val start: Long
        val end: Long?
    }

    data class Stay(
        override val start: Long,
        /** Null = ongoing (the current stay). */
        override val end: Long?,
        val location: Endpoint,
        val provenance: Provenance,
        /** The track whose end anchors this stay. */
        val afterTrackId: Long,
    ) : Interval

    data class Gap(
        override val start: Long,
        override val end: Long,
        val reason: GapReason,
    ) : Interval

    fun derive(
        tracks: List<TrackEnd>,
        liveness: List<Liveness>,
        nowMs: Long,
        activeRecording: Boolean,
        params: Params = Params(),
        distance: DistanceFn,
    ): List<Interval> {
        val evidence = summarizeLiveness(liveness, nowMs)
        val out = mutableListOf<Interval>()

        for (i in 0 until tracks.size - 1) {
            val prev = tracks[i]
            val next = tracks[i + 1]
            val gapStart = prev.endedAt
            val gapEnd = next.startedAt
            // Zero-length or negative gap (clock stepped backwards between tracks): emit nothing.
            if (gapEnd <= gapStart) continue
            val a = prev.end
            val b = next.start
            if (a == null || b == null) {
                out += Gap(gapStart, gapEnd, GapReason.UNKNOWN_ENDPOINT)
                continue
            }
            if (distance.metres(a.lat, a.lon, b.lat, b.lon) > params.agreementRadiusM) {
                out += Gap(gapStart, gapEnd, GapReason.MOVED_UNRECORDED)
                continue
            }
            if (gapEnd - gapStart < params.minStayMs) continue
            out += Stay(
                start = gapStart,
                end = gapEnd,
                location = midpoint(a, b),
                provenance = evidence.provenanceOver(gapStart, gapEnd),
                afterTrackId = prev.trackId,
            )
        }

        tailStay(tracks.lastOrNull(), evidence, nowMs, activeRecording, params)?.let { out += it }
        return out
    }

    /** The open-ended stay after the last track — where the user is right now. */
    private fun tailStay(
        last: TrackEnd?,
        evidence: LivenessSummary,
        nowMs: Long,
        activeRecording: Boolean,
        params: Params,
    ): Stay? {
        // While a track is being recorded the "gap" after the last finished track isn't a gap.
        if (last == null || activeRecording) return null
        val location = last.end ?: return null
        val start = last.endedAt
        if (start > nowMs) return null
        // If currently disarmed, the app can attest nothing past the disarm — close the stay there.
        val end = evidence.disarmedSince?.coerceAtLeast(start)
        val effectiveEnd = end ?: nowMs
        if (effectiveEnd - start < params.minStayMs) return null
        return Stay(
            start = start,
            end = end,
            location = location,
            provenance = evidence.provenanceOver(start, effectiveEnd),
            afterTrackId = last.trackId,
        )
    }

    private fun midpoint(a: Endpoint, b: Endpoint) =
        Endpoint((a.lat + b.lat) / 2, (a.lon + b.lon) / 2)

    // --- Liveness evidence ----------------------------------------------------

    private class LivenessSummary(
        /** Half-open [start, end) intervals where the app was known dead or disarmed. */
        val deadIntervals: List<Pair<Long, Long>>,
        /** Time of the earliest liveness evidence; anything before it is unattested. */
        val firstEvidenceAt: Long?,
        /** Set when the latest state is "disarmed with no re-arm" — dead from here on. */
        val disarmedSince: Long?,
    ) {
        fun provenanceOver(start: Long, end: Long): Provenance = when {
            firstEvidenceAt == null || start < firstEvidenceAt -> Provenance.INFERRED
            deadIntervals.any { (ds, de) -> ds < end && start < de } -> Provenance.INFERRED
            else -> Provenance.OBSERVED
        }
    }

    private fun summarizeLiveness(liveness: List<Liveness>, nowMs: Long): LivenessSummary {
        val dead = mutableListOf<Pair<Long, Long>>()
        var disarmedSince: Long? = null
        for (event in liveness) {
            when (event) {
                is Outage -> dead += event.at.coerceAtMost(nowMs) to event.until.coerceAtMost(nowMs)
                is Disarmed -> if (disarmedSince == null) disarmedSince = event.at.coerceAtMost(nowMs)
                is Armed -> {
                    disarmedSince?.let { dead += it to event.at.coerceAtMost(nowMs) }
                    disarmedSince = null
                }
            }
        }
        // A trailing disarm is dead through "now" for mid-list gaps; the tail stay handles it
        // explicitly via disarmedSince.
        disarmedSince?.let { dead += it to nowMs }
        return LivenessSummary(
            deadIntervals = dead,
            firstEvidenceAt = liveness.firstOrNull()?.at?.coerceAtMost(nowMs),
            disarmedSince = disarmedSince,
        )
    }

    // --- Display helpers -------------------------------------------------------

    /**
     * Splits intervals at local midnights so each piece falls inside one calendar day (a
     * 20:00–09:00 stay renders in both days with clamped bounds). An ongoing stay keeps its null
     * end on the final (today's) slice.
     */
    fun slicePerDay(intervals: List<Interval>, zone: ZoneId, nowMs: Long): List<Interval> =
        intervals.flatMap { interval ->
            val end = interval.end ?: nowMs
            val slices = mutableListOf<Interval>()
            var sliceStart = interval.start
            while (true) {
                val nextMidnight = Instant.ofEpochMilli(sliceStart).atZone(zone)
                    .toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                if (end <= nextMidnight) {
                    slices += copyWith(interval, sliceStart, interval.end?.let { end })
                    break
                }
                slices += copyWith(interval, sliceStart, nextMidnight)
                sliceStart = nextMidnight
            }
            slices
        }

    private fun copyWith(interval: Interval, start: Long, end: Long?): Interval = when (interval) {
        is Stay -> interval.copy(start = start, end = end)
        is Gap -> interval.copy(start = start, end = requireNotNull(end))
    }

    /**
     * Merges the DESC track list with derived intervals into one DESC timeline. On a start-time
     * tie the interval sorts newer (it extends past the shared instant; the track ended at it).
     */
    fun interleave(summaries: List<TrackSummary>, intervals: List<Interval>): List<TimelineItem> {
        val descIntervals = intervals.asReversed()
        val out = ArrayList<TimelineItem>(summaries.size + intervals.size)
        var t = 0
        var v = 0
        while (t < summaries.size || v < descIntervals.size) {
            val track = summaries.getOrNull(t)
            val interval = descIntervals.getOrNull(v)
            val takeInterval = when {
                interval == null -> false
                track == null -> true
                else -> interval.start >= track.startedAt
            }
            if (takeInterval) {
                out += when (val iv = descIntervals[v++]) {
                    is Stay -> TimelineItem.StayItem(iv)
                    is Gap -> TimelineItem.GapItem(iv)
                }
            } else {
                out += TimelineItem.TrackItem(summaries[t++])
            }
        }
        return out
    }
}

/** One row of the day-grouped timeline: a recorded track, a derived stay, or a data gap. */
sealed interface TimelineItem {
    val startedAt: Long

    data class TrackItem(val summary: TrackSummary) : TimelineItem {
        override val startedAt get() = summary.startedAt
    }

    data class StayItem(
        val stay: StayDeriver.Stay,
        /** Place resolution, attached after derivation; null only if resolution wasn't run. */
        val place: PlaceResolver.ResolvedStay? = null,
    ) : TimelineItem {
        override val startedAt get() = stay.start
    }

    data class GapItem(val gap: StayDeriver.Gap) : TimelineItem {
        override val startedAt get() = gap.start
    }
}
