package io.github.valeronm.breadcrumb.domain

object GapDiagnosis {

    enum class Fix {
        /** Trips entered by hand into gaps mostly start at the previous track's end fix and end where the next track starts. */
        MISSED_START,
        ADD_TRIP,
        STAYED_HERE,
        MERGE,
    }

    enum class Reason { NO_POSITION, ENDS_CLOSE, PARKED_THEN_LEFT, NEXT_AT_NAMED_PLACE, DROPOUT, TRAVELLED }

    /** Why a fix is ruled out for this gap. */
    enum class Block {
        MERGE_ACTIVITIES_DIFFER,
        MERGE_WRITERS_DIFFER,
        STAY_NO_POINT_BEFORE,
        STAY_NO_POINT_AFTER,
        STAY_TOO_FAR,
        STAY_NO_NAMED_PLACE,
    }

    // Set against the recorded history, where most gaps' ends lie 200 m–1 km apart at an implied
    // ~1 km/h, the time parked included.
    private const val STAYED_HERE_WITHIN_M = 200.0
    private val PARKED_BELOW_MPS = Speed.kmh(5.0).mps

    data class Verdict(
        val suggested: Fix,
        val reason: Reason,
        /** The fixes this gap rules out; [suggested] is never among them. */
        val blocked: Map<Fix, Block>,
        val merge: TrackMerge.Plan?,
        /** [StayDeriver.Gap.apartMeters]. */
        val apartMeters: Double?,
        /** [apartMeters] over the gap's length, a floor on the real speed; null where a side has no fix or the gap has no length. */
        val atLeastMps: Double?,
    ) {
        /** Every fix but [suggested], in [Fix] order. */
        val others: List<Fix> get() = Fix.entries.filter { it != suggested }
    }

    fun diagnose(
        gap: StayDeriver.Gap,
        neighbors: TrackMerge.Neighbors,
        fromNamed: Boolean,
        toNamed: Boolean,
        distance: DistanceFn,
    ): Verdict {
        val apart = gap.apartMeters(distance)
        val seconds = (gap.end - gap.start) / 1000.0
        val speed = apart?.takeIf { seconds > 0 }?.let { it / seconds }
        val merge = TrackMerge.compatible(neighbors.before, neighbors.after)
        val close = apart != null && apart <= STAYED_HERE_WITHIN_M
        val parked = speed != null && speed <= PARKED_BELOW_MPS
        val atNamedPlace = fromNamed || toNamed

        val blocked = buildMap {
            when (TrackMerge.mismatch(neighbors.before, neighbors.after)) {
                TrackMerge.Mismatch.WRITER -> put(Fix.MERGE, Block.MERGE_WRITERS_DIFFER)
                TrackMerge.Mismatch.ACTIVITY -> put(Fix.MERGE, Block.MERGE_ACTIVITIES_DIFFER)
                null -> Unit
            }
            // A named place is the user's word that this is somewhere people stop, and the only kind a
            // stay is linked to. Naming one cannot lift the other blocks, so they are stated first.
            when {
                gap.from == null -> Block.STAY_NO_POINT_BEFORE
                gap.to == null -> Block.STAY_NO_POINT_AFTER
                !close && !parked -> Block.STAY_TOO_FAR
                !atNamedPlace -> Block.STAY_NO_NAMED_PLACE
                else -> null
            }?.let { put(Fix.STAYED_HERE, it) }
        }
        val (suggested, reason) = when {
            apart == null -> (if (merge != null) Fix.MERGE else Fix.ADD_TRIP) to Reason.NO_POSITION
            close && atNamedPlace -> Fix.STAYED_HERE to Reason.ENDS_CLOSE
            // Trips entered by hand into such gaps mostly ran the whole way to the named place and
            // stayed there, where the next track starting somewhere unnamed is nearly always a missed start.
            parked && toNamed -> Fix.ADD_TRIP to Reason.NEXT_AT_NAMED_PLACE
            parked -> Fix.MISSED_START to Reason.PARKED_THEN_LEFT
            merge != null -> Fix.MERGE to Reason.DROPOUT
            else -> Fix.ADD_TRIP to Reason.TRAVELLED
        }
        return Verdict(
            suggested = suggested,
            reason = reason,
            blocked = blocked,
            merge = merge,
            apartMeters = apart,
            atLeastMps = speed,
        )
    }
}
