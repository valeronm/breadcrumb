package io.github.valeronm.breadcrumb.location

import io.github.valeronm.breadcrumb.data.AndroidDistance
import io.github.valeronm.breadcrumb.data.TrackQuality
import io.github.valeronm.breadcrumb.data.TrackStats
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.CarrierEvidence
import io.github.valeronm.breadcrumb.domain.DistanceFn
import io.github.valeronm.breadcrumb.domain.GnssSnapshot
import io.github.valeronm.breadcrumb.domain.IgnoreReason
import io.github.valeronm.breadcrumb.domain.Motion
import io.github.valeronm.breadcrumb.domain.MovementConfirmer
import io.github.valeronm.breadcrumb.domain.TrackGroup

/**
 * One platform fix, reduced to what the recorder stores and judges. The Android boundary is the
 * mapping that builds this, not anything below it: `android.location.Location` reports absence
 * through `hasX()` companions, and every one of those questions is answered here so nothing
 * downstream has to ask.
 */
data class Fix(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val accuracy: Float?,
    val speed: Float?,
    val bearing: Float?,
    /** When the fix was taken. The platform occasionally reports no time at all; the boundary that
     *  builds this substitutes arrival time, so nothing below has to know that happens. */
    val timeMs: Long,
    val verticalAccuracy: Float?,
    val speedAccuracy: Float?,
    val bearingAccuracy: Float?,
    /** Monotonic time of the fix — the domain the GNSS cross-check compares in, never wall time. */
    val elapsedRealtimeMs: Long,
)

/** The settings a batch is judged under, read once per batch by the caller that owns them. */
data class IngestSettings(
    val maxAccuracyM: Float,
    val requireGnss: Boolean,
)

/**
 * What the activity gate says as this batch arrives: the activity it trusts, and whether it is
 * holding a STILL the ground contradicted. Both are the gate's facts — the ingest reads them and
 * decides nothing about either.
 */
data class GateState(
    val confirmed: ActivityType,
    val stillParked: Boolean,
)

/**
 * What the receiver last said about the satellites, as of this batch. An *input*, not state this
 * accumulates: it is written from the `GnssStatus` callback's own thread while fixes arrive on
 * another, so it stays where that write can be published (a `@Volatile` field on the service) and
 * arrives here already read. [lastFixElapsedMs] is monotonic — the domain a fix age may be measured
 * in, since wall time can step.
 */
data class GnssState(
    val satellitesInFix: Int?,
    val cn0Top4: Float?,
    val lastFixElapsedMs: Long,
)

/**
 * What a batch produced: the rows to store, the verdict the card should show, and how many fixes
 * were good — the no-fix guard's input, which is a fact about the batch rather than a decision.
 */
data class Ingested(
    val points: List<TrackPoint>,
    val motion: Motion,
    val accepted: Int,
)

/**
 * The recorder's fix path, with no Android in it: platform fixes in, point rows and a motion verdict
 * out. Everything here used to sit in [LocationRecordingService.ingestLocations] and its two helpers,
 * where it could only be exercised by walking around with the phone; the service now maps
 * `Location` to [Fix], calls [onFixes], writes the rows it hands back and publishes what it says.
 *
 * It owns the three things that accumulate across a track — the movement witness, the carrier case
 * against the track's label, and the running aggregates — because each is fed inside the loop below
 * and none of them means anything apart from it. The activity is *not* among them: the gate and the
 * controller stay outside, and the confirmed activity arrives per batch, so this decides nothing
 * about the track's lifecycle. It only judges fixes.
 *
 * Callers hold whatever lock they serialize the recorder with; nothing here is thread-safe on its own.
 */
class FixIngest(internal val distance: DistanceFn = AndroidDistance) {

    private val confirmer = MovementConfirmer(distance)
    private val carrierEvidence = CarrierEvidence()
    private var accumulator = TrackStats.Accumulator(distance)

    /** Mark the first good fix after a resume as a new segment. */
    private var pendingSegmentStart = false

    /** Last fix's accuracy and whether the gate rejected it — the "waiting for GPS" card's feedback. */
    var lastFixAccuracyM: Float? = null
        private set
    var lastFixRejectedByAccuracy = false
        private set

    val pointCount: Int get() = accumulator.pointCount
    val distanceMeters: Double get() = accumulator.distanceMeters
    val lastGood: TrackPoint? get() = accumulator.lastGood

    /**
     * The witness's verdict — [Motion.Unknown] whenever the ground data can't support one, which
     * is also what every consultation treats as "no witness": the recorder must record movement,
     * and a mislabelled STILL aboard a moving carrier costs the trip unless the ground can answer.
     */
    fun verdict(nowMs: Long): Motion = confirmer.verdict(nowMs)

    /**
     * While the verdict overrules a foot label (measured ground speed its ceiling can't explain),
     * the Record card and notification say "Moving" ([ActivityType.UNKNOWN]) instead. Display only,
     * structurally so: computed downstream of every decision, feeding neither gate, controller nor
     * ceiling, and recomputed at every publish rather than a mode to exit, so it reverts by itself
     * when the verdict drops out; while the ground can't answer the verdict is [Motion.Unknown] and
     * the substitution never triggers. A parked STILL keeps the *confirmed* activity on the foot label
     * — exactly when the card should say "Moving" — so the same test covers that stretch.
     */
    fun displayActivity(confirmed: ActivityType, motion: Motion): ActivityType {
        if (confirmed.trackGroup != TrackGroup.FOOT) return confirmed
        return if (TrackQuality.motionOverrules(confirmed, motion)) ActivityType.UNKNOWN else confirmed
    }

    /** Judge one delivery of fixes against [gate]'s activity ceilings, under [settings]. */
    fun onFixes(
        trackId: Long,
        fixes: List<Fix>,
        gate: GateState,
        settings: IngestSettings,
        gnss: GnssState,
    ): Ingested {
        var motion: Motion = Motion.Unknown
        var accepted = 0
        // One insert per batch — the platform listener's List overload can deliver several
        // buffered fixes at once.
        val batch = ArrayList<TrackPoint>(fixes.size)
        for (fix in fixes) {
            val candidate = TrackPoint(
                trackId = trackId,
                latitude = fix.latitude,
                longitude = fix.longitude,
                altitude = fix.altitude,
                accuracy = fix.accuracy,
                speed = fix.speed,
                bearing = fix.bearing,
                timestamp = fix.timeMs,
                verticalAccuracy = fix.verticalAccuracy,
                speedAccuracy = fix.speedAccuracy,
                bearingAccuracy = fix.bearingAccuracy,
                satellitesInFix = gnss.satellitesInFix,
                cn0 = gnss.cn0Top4,
            )
            // The first good fix after a resume begins a new segment: disconnect it from the previous
            // segment so the paused gap isn't jump-checked or counted in distance.
            val segStart = pendingSegmentStart
            val baseline = if (segStart) null else accumulator.lastGood
            // Bad fixes are still stored (with the reason), just excluded from distance and the
            // good-point baseline. The rule weighs all three reasons and their order; this reports
            // the platform evidence for one of them (null = the cross-check is off).
            // The motion verdict is taken against the ground as it stood *before* this fix joined
            // the window, so a fix can never be part of the evidence that clears it.
            val quality = TrackQuality.Gates(
                settings.maxAccuracyM,
                if (settings.requireGnss) gnssBacked(gnss, fix) else null,
                verdict(candidate.timestamp),
            )
            val reason = TrackQuality.badFixReason(baseline, candidate, gate.confirmed, quality, distance)
            // The feed contract ([MovementConfirmer]): every fix that cleared the *label-independent*
            // gates, and only those. A jump-flagged fix is included deliberately — its rejection came
            // from the activity ceiling, which is the very thing the witness exists to second-guess,
            // and withholding it would make the witness inherit that error.
            if (reason != IgnoreReason.ACCURACY && reason != IgnoreReason.NO_GNSS) {
                confirmer.onFix(candidate.timestamp, candidate.latitude, candidate.longitude)
            }
            // The same verdict, folded into the carrier evidence the track is judged by at finish.
            carrierEvidence.onSample(candidate.timestamp, quality.motion, gate.stillParked)
            motion = quality.motion
            val bad = reason != null
            lastFixAccuracyM = candidate.accuracy
            lastFixRejectedByAccuracy = reason == IgnoreReason.ACCURACY
            val point = candidate.copy(
                ignored = bad,
                ignoreReason = reason?.code,
                segmentStart = segStart && !bad,
            )
            // Every fix goes through the accumulator, ignored ones included — it applies the same
            // rule (skip ignored, detach at a segment start) the finished track is recomputed with.
            accumulator.add(point)
            if (!bad) {
                if (segStart) pendingSegmentStart = false
                accepted++
            }
            batch.add(point)
        }
        return Ingested(batch, motion, accepted)
    }

    /** The next good fix opens a new segment: recording resumed, and nobody watched the gap. */
    fun markSegmentStart() {
        pendingSegmentStart = true
    }

    /** A track opened under [activity]: fresh aggregates, and a fresh case against its label. */
    fun onTrackOpened(activity: ActivityType) {
        accumulator = TrackStats.Accumulator(distance)
        carrierEvidence.restart(TrackQuality.groupCeiling(activity))
        pendingSegmentStart = false
        lastFixAccuracyM = null
        lastFixRejectedByAccuracy = false
    }

    /**
     * The track closed. Only the pending segment break is dropped: the aggregates and the carrier
     * case are read *after* this by the finish, and [onTrackOpened] is what clears them.
     */
    fun onTrackClosed() {
        pendingSegmentStart = false
    }

    /** What the carrier case renames a track labelled [label] to, or null — see [CarrierEvidence]. */
    fun renameFor(label: ActivityType): ActivityType? = carrierEvidence.renameFor(label)

    /** Re-window the witness for a new sampling cadence; see [MovementConfirmer.reshape]. */
    fun reshapeConfirmer(params: MovementConfirmer.Params) = confirmer.reshape(params)

    /** Whether [fix] is backed by a recent real satellite fix — see [GnssSnapshot.backed]. */
    private fun gnssBacked(gnss: GnssState, fix: Fix): Boolean =
        GnssSnapshot.backed(gnss.lastFixElapsedMs, fix.elapsedRealtimeMs, GNSS_FIX_MAX_AGE_MS)

    companion object {
        // A fix counts as GNSS-backed when a satellite fix occurred within this of it. Tunable for
        // field-testing the cross-check against the tunnel/underpass fabrication case; the satellite
        // count that makes a fix count at all is the service's, which owns the callback that counts.
        const val GNSS_FIX_MAX_AGE_MS = 5_000L
    }
}
