package io.github.valeronm.breadcrumb.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import io.github.valeronm.breadcrumb.data.JourneyLine
import io.github.valeronm.breadcrumb.data.JourneyPolylines
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.data.lineKeys
import io.github.valeronm.breadcrumb.data.recordingLineOf
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.TimelineItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

/**
 * A whole journey on one map: every track's path colored by its activity and cased as
 * the track map's line is ([casedJourneyLines]), with the journey's places drawn over them as the
 * all-places map draws its field ([MapLibrePlacesMap]).
 *
 * No per-metric ramp here — one line per track, one color per activity, which is the web viewer's
 * overview convention.
 *
 * The camera frames what is shown once per [frameKey] — the day selection — plus once more when
 * [linesComplete] turns true: a first open styles the map while the per-track loads are still
 * streaming in, and the frame taken then covers only what had arrived. Between those moments lines
 * redraw the sources under a camera that stays put. [liveLines] hold that second frame back only until [liveComplete]
 * first turns true per [frameKey]: a recording that starts later grows a map already framed.
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
    /** Drawn over [lines] from a source of their own, since they change on every fix and [lines] would rebuild with them. */
    liveLines: List<JourneyLine> = emptyList(),
    liveComplete: Boolean = true,
) {
    var liveFramed by remember(frameKey) { mutableStateOf(false) }
    LaunchedEffect(frameKey, liveComplete) { if (liveComplete) liveFramed = true }
    MapLibreStyledMap(
        modifier = modifier,
        frame = { frame(it, lines + liveLines, places) },
        frameKey = frameKey to (linesComplete && liveFramed),
        frameCovers = liveLines.map { it.corners },
        onClick = { map, at -> overviewPlaceKeyNear(map, at)?.also(onOpenPlace) != null },
        onStyleLoaded = { ctx, style, dark ->
            // Under the lines: a reach is the ground a place claims, and the route crosses it.
            addOverviewCircles(style, dark, OVERVIEW_CIRCLE_ZOOM)
            addCasedLines(style, JOURNEY_LINES)
            addCasedLines(style, JOURNEY_LIVE_LINES)
            addOverviewLayers(ctx, style, dark)
        },
        styled = {
            // The lines stream in a few tracks at a time, and each build would otherwise prepare every track again.
            val prepared = remember { HashMap<JourneyLine, CasedLine>() }
            val colorOf = { activityType: String -> mapActivityArgb(ActivityType.ofName(activityType), dark) }
            CasedLineBuild(JOURNEY_LINES, lines) { casedJourneyLines(lines, colorOf, prepared) }
            CasedLineBuild(JOURNEY_LIVE_LINES, liveLines) { liveLines.map { CasedLine(it, colorOf(it.activityType)) } }
            OverviewCirclesFeed(places)
            OverviewPlacesFeed(places)
        },
    )
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
internal fun rememberJourneyLines(
    polylines: JourneyPolylines,
    tracks: List<TrackSummary>,
    clearOnReload: Boolean,
): JourneyLineLoad {
    val smoothed = LocalSmoothTracks.current
    val keys = remember(tracks, smoothed) { lineKeys(tracks, smoothed) }
    val lines by produceState(emptyList<JourneyLine>(), keys) {
        if (clearOnReload) value = emptyList()
        polylines.linesFor(tracks, smoothed).collect { value = it }
    }
    return JourneyLineLoad(lines, complete = lines.size == tracks.size)
}

/**
 * The line of the track [recording] is writing through [fixes] ([TrackListViewModel.openTrackFixes]): [JourneyLineLoad.complete]
 * once the first line is built, or found to have no fix to draw. Built off the main thread, since smoothing re-runs
 * over the whole track.
 */
@Composable
internal fun rememberRecordingLine(recording: TimelineItem.RecordingItem, fixes: List<TrackPoint>?): JourneyLineLoad {
    val smoothed = LocalSmoothTracks.current
    val latest = rememberUpdatedState(fixes)
    var load by remember(recording.trackId) { mutableStateOf(JourneyLineLoad(emptyList(), complete = false)) }
    LaunchedEffect(recording, smoothed) {
        // Conflated rather than restarted per fix: on a long track a build the next fix cancels would never land.
        snapshotFlow { latest.value }.filterNotNull().conflate().collect { good ->
            val line = withContext(Dispatchers.Default) { recordingLineOf(recording, good, smoothed) }
            load = JourneyLineLoad(listOfNotNull(line.takeIf { it.fixCount > 0 }), complete = true)
        }
    }
    return load
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
private const val JOURNEY_LIVE_LINES = "journey-live-lines"
