package io.github.valeronm.breadcrumb.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.valeronm.breadcrumb.data.JourneyLine
import io.github.valeronm.breadcrumb.data.JourneyPolylines
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.data.lineKeys
import io.github.valeronm.breadcrumb.domain.ActivityType
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

/**
 * A whole journey on one map: every track's path colored by its activity and cased as
 * the track map's line is ([casedJourneyLines]), with the journey's places drawn over them in the
 * overview vocabulary ([addOverviewLayers] — labeled pins for named places, dots for unnamed
 * clusters).
 *
 * No per-metric ramp here — one line per track, one color per activity, which is the web viewer's
 * overview convention.
 *
 * The camera frames what is shown once per [frameKey] — the day selection — plus once more when
 * [linesComplete] turns true: a first open styles the map while the per-track loads are still
 * streaming in, and the frame taken then covers only what had arrived. Between those moments lines
 * redraw the sources under a camera that stays put.
 */
@Composable
internal fun MapLibreJourneyMap(
    lines: List<JourneyLine>,
    places: List<OverviewPlace>,
    frameKey: Any,
    linesComplete: Boolean,
    /** Reports a tapped place's detail key, as the all-places map does. */
    onOpenPlace: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Only the unknown-type fallback depends on the map's shade.
    val dark = isMapDark()
    val applied = remember { AppliedJourneyInputs() }
    applied.onOpenPlace = onOpenPlace
    MapLibreStyledMap(
        modifier = modifier,
        frame = { frame(it, lines, places) },
        frameKey = frameKey to linesComplete,
        onMapReady = { map ->
            map.addOnMapClickListener { latLng ->
                val key = overviewPlaceKeyNear(map, latLng)
                if (key != null) applied.onOpenPlace(key)
                key != null
            }
        },
        onStyleLoaded = { ctx, _, style, dark ->
            applied.places = places
            addCasedLines(style, JOURNEY_LINES)
            addOverviewLayers(ctx, style, places, dark, fullSize = true)
        },
        styled = { view, style, zoom ->
            // The lines stream in a few tracks at a time, and each build would otherwise prepare every track again.
            val prepared = remember { HashMap<JourneyLine, CasedLine>() }
            CasedLineBuild(view, style, zoom, JOURNEY_LINES, lines) {
                casedJourneyLines(lines, { mapActivityArgb(ActivityType.ofName(it), dark) }, prepared)
            }
        },
        onUpdate = { _, style ->
            if (applied.places !== places) {
                applied.places = places
                updateOverviewSource(style, places)
            }
        },
    )
}

private class AppliedJourneyInputs {
    var places: List<OverviewPlace>? = null

    /** The click listener is registered once, so it reads the handler from here to never go stale. */
    var onOpenPlace: (String) -> Unit = {}
}

private fun frame(map: MapLibreMap, lines: List<JourneyLine>, places: List<OverviewPlace>) {
    frameTo(map, framePositions(lines, places), singlePointZoom = 13.0)
}

/**
 * Each line's bounding corners plus the place pins — enough for a bounds fit without feeding
 * the camera every vertex.
 */
private fun framePositions(lines: List<JourneyLine>, places: List<OverviewPlace>): List<LatLng> {
    val positions = ArrayList<LatLng>(lines.size * 2 + places.size)
    for (line in lines) {
        val (southWest, northEast) = line.corners ?: continue
        positions += southWest.toLatLng()
        positions += northEast.toLatLng()
    }
    for (place in places) positions += place.marker.location.toLatLng()
    return positions
}

/**
 * The lines of every track a [rememberJourneyLines] load was asked for that have arrived, and whether all have.
 * [complete] counts lines, so a load restarted over the last load's lines reads complete before any of its own arrive.
 */
internal class JourneyLineLoad(val lines: List<JourneyLine>, val complete: Boolean)

/**
 * The lines of [tracks] as [JourneyPolylines.linesFor] emits them. The load is keyed on the tracks' [lineKeys] rather
 * than the list, so an emission that changed no track doesn't restart it and reset the map mid-fill. With
 * [clearOnReload], a restarted load starts from no lines rather than the last load's.
 */
@Composable
internal fun rememberJourneyLines(polylines: JourneyPolylines, tracks: List<TrackSummary>, clearOnReload: Boolean): JourneyLineLoad {
    val keys = remember(tracks) { lineKeys(tracks) }
    val lines by produceState(emptyList<JourneyLine>(), keys) {
        if (clearOnReload) value = emptyList()
        polylines.linesFor(tracks).collect { value = it }
    }
    return JourneyLineLoad(lines, complete = lines.size == tracks.size)
}

/**
 * [lines] as the cased lines a many-track map draws, oldest first so a later track covers an earlier one where they
 * cross: each in its activity's color ([colorOf]), a run per watched stretch. A line already in [prepared] is reused
 * whatever [colorOf] now says, so a [prepared] map belongs to one [colorOf]; it holds only [lines] afterward.
 */
internal fun casedJourneyLines(
    lines: List<JourneyLine>,
    colorOf: (activityType: String) -> Int,
    prepared: MutableMap<JourneyLine, CasedLine>,
): List<CasedLine> {
    val drawn = lines.sortedBy { it.startedAt }.map { line ->
        prepared.getOrPut(line) { CasedLine(line, colorOf(line.activityType)) }
    }
    prepared.keys.retainAll(lines.toSet())
    return drawn
}

private const val JOURNEY_LINES = "journey-lines"
