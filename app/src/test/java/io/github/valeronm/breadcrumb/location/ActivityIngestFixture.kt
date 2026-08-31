package io.github.valeronm.breadcrumb.location

import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.MeasuredPosition
import io.github.valeronm.breadcrumb.domain.Motion
import io.github.valeronm.breadcrumb.domain.NoFixGuard
import io.github.valeronm.breadcrumb.domain.ORIGIN_LAT
import io.github.valeronm.breadcrumb.domain.flatDistance
import io.github.valeronm.breadcrumb.domain.lonAt

/**
 * One recorder, wired the way a phone wires it, shared by the suites that drive the activity path.
 * They split by question rather than by machinery — [ActivityIngestTest] asks where tracks begin and
 * end, [DepartureTriggerTest] asks how a start is noticed when Play Services reports none — and both
 * need the same core, the same clock and the same way of putting a walk on the road.
 *
 * Times are milliseconds from an arbitrary [T0]; fixtures sit at the domain suites' neutral origin.
 */
abstract class ActivityIngestFixture {

    protected val ingest = FixIngest(flatDistance)
    protected val noFixGuard = NoFixGuard()
    protected val core = ActivityIngest(ingest, noFixGuard)

    protected val settings = ActivitySettings(
        stitchWindowMs = STITCH_WINDOW_MS,
        uncorroboratedHoldMs = HOLD_CAP_MS,
        triggers = TRIGGERS,
    )

    /**
     * The dispatcher's half of [Effect.OpenTrack]: it is a request with a reply, and a pass that
     * emits one is not finished until the core has been told which row it got. Without this the
     * whole suite drives a core that never ran [ActivityIngest.onTrackResolved] — no track label, no
     * fresh accumulators, no carrier case — and assertions about a track's finish would hold for
     * reasons that have nothing to do with the rules they name. Every pass goes through here.
     *
     * Always an insert, never a continuation: what the repository would have answered is
     * `StitchRule`'s question and has its own suite, so nothing here needs a database to guess it.
     */
    protected fun resolved(effects: List<Effect>): List<Effect> = effects.also {
        for (effect in it) {
            if (effect is Effect.OpenTrack) core.onTrackResolved(effect.activity, stitched = false)
        }
    }

    /**
     * A reading whose event time is its apply time — the ordinary live delivery. The registration is
     * long-established, so neither the replay window nor the armed bound swallows one.
     */
    protected fun reading(
        raw: ActivityType,
        atMs: Long,
        eventTimeMs: Long? = atMs,
    ) = resolved(
        core.onReading(
            raw = raw,
            eventTimeMs = eventTimeMs,
            nowMs = atMs,
            registration = Registration(armedAtMs = T0 - MINUTE, lastRegisteredAtMs = T0 - HOUR),
            settings = settings,
        ),
    )

    /**
     * A stop that lands. On foot the witness's window rarely fills, so the ordinary stop is one the
     * ground never vouched for: it is held, and the cap lands it. Returns both passes' effects in
     * order, which is what a caller would have seen had the stop applied at once.
     */
    protected fun stop(atMs: Long, eventTimeMs: Long? = atMs) =
        reading(ActivityType.STILL, atMs, eventTimeMs) +
            resolved(core.onMotion(Motion.Unknown, atMs + HOLD_CAP_MS, settings))

    /** The two other passes that can open a track, answered like [reading] is. */
    protected fun departure(nowMs: Long, settings: ActivitySettings = this.settings) =
        resolved(core.onDeparture(nowMs, settings))

    protected fun probeFix(
        position: MeasuredPosition,
        nowMs: Long,
        settings: ActivitySettings = this.settings,
    ) = resolved(core.onProbeFix(position, nowMs, settings))

    /** Feeds one accepted fix into the fix path, so the track has a last-good point to end at. */
    protected fun fix(atMs: Long, eastM: Double) {
        ingest.onFixes(
            trackId = 1L,
            fixes = listOf(
                Fix(
                    latitude = ORIGIN_LAT,
                    longitude = lonAt(eastM),
                    altitude = null,
                    accuracy = 5f,
                    speed = null,
                    bearing = null,
                    timeMs = atMs,
                    verticalAccuracy = null,
                    speedAccuracy = null,
                    bearingAccuracy = null,
                    elapsedRealtimeMs = atMs,
                ),
            ),
            gate = GateState(core.confirmed, stillParked = core.parked == ActivityType.STILL),
            settings = IngestSettings(maxAccuracyM = 50f, requireGnss = false),
            gnss = GnssState(satellitesInFix = null, cn0Top4 = null, lastFixElapsedMs = atMs),
        )
    }

    /** Puts a walk on the road: the track is open, GPS is on, and one fix has landed. */
    protected fun startWalking() {
        reading(ActivityType.WALKING, T0)
        fix(T0, 0.0)
    }

    companion object {
        const val T0 = 1_700_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val STITCH_WINDOW_MS = 90_000L

        /** How long a stop the ground could not vouch for is held before it lands anyway. */
        const val HOLD_CAP_MS = 35_000L

        /** The guard's clock is monotonic and unrelated to [T0]; only differences are ever read. */
        const val E0 = 500_000L

        /**
         * **What ships**: the two free triggers on, the battery-costing one off. Every case that does
         * not say otherwise runs these, so the effect lists assert the sequence a phone actually
         * performs — a fixture turning everything on would pin a combination nobody runs.
         */
        val TRIGGERS = DepartureTriggers(fence = true, continuous = false, motion = true)
    }
}
