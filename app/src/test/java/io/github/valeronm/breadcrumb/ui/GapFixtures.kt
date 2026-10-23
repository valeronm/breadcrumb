package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.GapDiagnosis
import io.github.valeronm.breadcrumb.domain.PlaceClusterer
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TrackMerge
import io.github.valeronm.breadcrumb.domain.trackSummary
import java.time.LocalDate
import java.time.ZoneId

internal object GapFixtures {
    val ORIGIN = Coordinate(lat = 1.0, lon = -2.0)
    private val DAY: LocalDate = LocalDate.of(2024, 3, 14)
    const val HOUR = 3_600_000L

    fun noon(zone: ZoneId): Long = DAY.atStartOfDay(zone).toInstant().toEpochMilli() + 12 * HOUR

    fun verdict(
        suggested: GapDiagnosis.Fix = GapDiagnosis.Fix.ADD_TRIP,
        reason: GapDiagnosis.Reason = GapDiagnosis.Reason.TRAVELLED,
        merge: TrackMerge.Plan? = null,
        apartMeters: Double? = 1_000.0,
        atLeastMps: Double? = 1.0,
    ) = GapDiagnosis.Verdict(suggested, reason, emptyList(), merge, apartMeters, atLeastMps)

    fun detail(
        zone: ZoneId,
        verdict: GapDiagnosis.Verdict = verdict(),
        fromPlace: PlaceResolver.ResolvedStay? = side(place(id = 1, label = "Work"), visits = 3),
        toPlace: PlaceResolver.ResolvedStay? = side(place(id = 2, label = "Home"), visits = 4),
    ): GapDetail {
        val noon = noon(zone)
        return GapDetail(
            gap = StayDeriver.Gap(
                start = noon,
                end = noon + HOUR,
                afterTrackId = 1,
                from = StayDeriver.Gap.Side(ORIGIN, clusterId = 0),
                to = StayDeriver.Gap.Side(Coordinate(ORIGIN.lat + 0.01, ORIGIN.lon), clusterId = 1),
            ),
            before = trackSummary(1, ActivityType.DRIVING.name, noon - HOUR, noon, meters = 5_000.0),
            after = trackSummary(2, ActivityType.DRIVING.name, noon + HOUR, noon + 2 * HOUR, meters = 5_000.0),
            fromPlace = fromPlace,
            toPlace = toPlace,
            departureZone = zone,
            arrivalZone = zone,
            verdict = verdict,
        )
    }

    fun side(place: Place?, visits: Int, centroid: Coordinate = ORIGIN) =
        PlaceResolver.ResolvedStay(place = place, visitCount = visits, centroid = centroid, radiusM = place?.radiusM ?: PlaceClusterer.DEFAULT_RADIUS_M)

    fun place(id: Long, label: String?) = Place(
        id = id,
        label = label,
        lat = ORIGIN.lat,
        lon = ORIGIN.lon,
        createdAt = 0,
        radiusM = 60.0,
    )
}
