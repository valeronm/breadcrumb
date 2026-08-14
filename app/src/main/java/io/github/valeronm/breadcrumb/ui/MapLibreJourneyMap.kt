package io.github.valeronm.breadcrumb.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import com.google.gson.JsonObject
import io.github.valeronm.breadcrumb.data.JourneyLine
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Coordinate
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.utils.ColorUtils
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * A whole journey on one map: every track's simplified path colored by its activity, with the
 * journey's places drawn over them in the overview vocabulary ([addOverviewLayers] — labeled pins
 * for named places, dots for unnamed clusters).
 *
 * No per-metric ramp here — one line per track, one color per activity, which is the web viewer's
 * overview convention. The paths arrive already reduced ([JourneyLine]), so the source keeps its
 * default simplification.
 *
 * The camera frames what is shown once per [frameKey] — the day selection — plus once more when
 * [linesComplete] turns true: a first open styles the map while the per-track loads are still
 * streaming in, and the frame taken then covers only what had arrived. Between those moments lines
 * redraw the source under a camera that stays put.
 */
@Composable
internal fun MapLibreJourneyMap(
    lines: List<JourneyLine>,
    places: List<OverviewPlace>,
    frameKey: Any,
    linesComplete: Boolean,
    modifier: Modifier = Modifier,
) {
    // The colors follow the theme only through the unknown-type fallback, so that one color is
    // read from composition and the map is remembered off it — a theme flip rebuilds, a plain
    // recomposition costs nothing.
    val fallback = MaterialTheme.colorScheme.onSurfaceVariant
    val colorByType = remember(lines, fallback) {
        lines.map { it.activityType }.distinct()
            .associateWith { activityColorOr(ActivityType.ofName(it), fallback).toArgb() }
    }
    val collection = remember(lines, colorByType) { journeyCollection(lines, colorByType) }
    val applied = remember { AppliedJourneyInputs() }
    MapLibreStyledMap(
        modifier = modifier,
        onStyleLoaded = { ctx, map, style ->
            applied.collection = collection
            applied.places = places
            applied.frameKey = frameKey
            applied.framedComplete = linesComplete
            style.addSource(GeoJsonSource(JOURNEY_SOURCE, collection))
            style.addLayer(
                LineLayer(JOURNEY_LAYER, JOURNEY_SOURCE).withProperties(
                    PropertyFactory.lineWidth(3f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    PropertyFactory.lineColor(Expression.get(JOURNEY_COLOR_KEY)),
                ),
            )
            addOverviewLayers(ctx, style, places, fullSize = true)
            frame(map, lines, places)
        },
        onUpdate = { map, style ->
            if (applied.collection !== collection) {
                applied.collection = collection
                style.getSourceAs<GeoJsonSource>(JOURNEY_SOURCE)?.setGeoJson(collection)
            }
            if (applied.places !== places) {
                applied.places = places
                updateOverviewSource(style, places)
            }
            if (applied.frameKey != frameKey || (linesComplete && !applied.framedComplete)) {
                applied.frameKey = frameKey
                applied.framedComplete = linesComplete
                frame(map, lines, places)
            }
        },
    )
}

private class AppliedJourneyInputs {
    var collection: FeatureCollection? = null
    var places: List<OverviewPlace>? = null
    var frameKey: Any? = null

    /** Whether the current frame was taken with every line in hand — see the composable's KDoc. */
    var framedComplete = false
}

private fun frame(map: MapLibreMap, lines: List<JourneyLine>, places: List<OverviewPlace>) {
    frameTo(map, framePositions(lines, places), singlePointZoom = 13.0)
}

/**
 * Each segment's bounding corners plus the place pins — enough for a bounds fit without feeding
 * the camera every vertex.
 */
private fun framePositions(lines: List<JourneyLine>, places: List<OverviewPlace>): List<LatLng> {
    val positions = ArrayList<LatLng>(lines.size * 2 + places.size)
    for (line in lines) {
        for (segment in line.segments) addSegmentCorners(segment, positions)
    }
    for (place in places) positions += place.marker.location.toLatLng()
    return positions
}

private fun addSegmentCorners(segment: DoubleArray, into: MutableList<LatLng>) {
    if (segment.isEmpty()) return
    var minLon = segment[0]
    var maxLon = segment[0]
    var minLat = segment[1]
    var maxLat = segment[1]
    for (i in segment.indices step 2) {
        if (segment[i] < minLon) minLon = segment[i]
        if (segment[i] > maxLon) maxLon = segment[i]
        if (segment[i + 1] < minLat) minLat = segment[i + 1]
        if (segment[i + 1] > maxLat) maxLat = segment[i + 1]
    }
    into += LatLng(minLat, minLon)
    into += LatLng(maxLat, maxLon)
}

/**
 * One feature per drawn stretch, its color property shared per activity. A manual track's typed
 * endpoints are densified along their great circle ([greatCirclePositions]), as the track detail
 * draws them.
 */
private fun journeyCollection(lines: List<JourneyLine>, colorByType: Map<String, Int>): FeatureCollection {
    val features = ArrayList<Feature>()
    val propsByColor = HashMap<Int, JsonObject>()
    for (line in lines) {
        val color = colorByType[line.activityType] ?: continue
        val props = propsByColor.getOrPut(color) {
            JsonObject().apply { addProperty(JOURNEY_COLOR_KEY, ColorUtils.colorToRgbaString(color)) }
        }
        for (segment in line.segments) {
            val positions = segmentPositions(segment, line.manual)
            if (positions.size >= 2) {
                features += Feature.fromGeometry(LineString.fromLngLats(positions), props)
            }
        }
    }
    return FeatureCollection.fromFeatures(features)
}

private fun segmentPositions(segment: DoubleArray, manual: Boolean): List<Point> {
    if (!manual || segment.size < 4) {
        return List(segment.size / 2) { i -> Point.fromLngLat(segment[i * 2], segment[i * 2 + 1]) }
    }
    val coords = List(segment.size / 2) { i -> Coordinate(segment[i * 2 + 1], segment[i * 2]) }
    return greatCirclePositions(coords).map { it.toPoint() }
}

private const val JOURNEY_SOURCE = "journey-lines-src"
private const val JOURNEY_LAYER = "journey-lines-layer"
private const val JOURNEY_COLOR_KEY = "color"
