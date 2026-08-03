package io.github.valeronm.breadcrumb.ui

import android.content.Context
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import com.google.gson.JsonObject
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.PlaceCategory
import io.github.valeronm.breadcrumb.domain.PlaceClusterer
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.placeCategory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * The two maps whose subject is a place rather than a journey: one place at the zoom its own capture
 * radius frames, and the whole field of them at once. Three things cross between them — what a place
 * is ([PlaceMarker]), what it is drawn as ([markerIcon]) and the endpoint dot's id — and the zoom
 * range forces the rest apart.
 */

// --- One place ------------------------------------------------------------------------------

/**
 * One dot on the place map whose fate the *map* decides: [distanceM] (distance from the pin) is
 * compared against the live radius in a layer expression; null means settled — a neighbor keeps it
 * whatever the radius does, drawn gray uncompared. Carrying the distance rather than a resolved
 * icon means a drag changes one layer property, not thousands of features — that per-step rebuild
 * of the collection is what lags.
 */
internal class CaptureDot(val location: StayDeriver.Endpoint, val distanceM: Double?)

/**
 * A place as these two maps draw it: what it is called and what it is for, at a spot. Being named is
 * what makes one a *pin* — an unnamed cluster stays a dot — and the name and the category always come
 * from the same place row, which is what the [Place] constructor is for. A track's end pins are
 * always a named row and take the shorter road, straight to `placePinImage`.
 */
internal data class PlaceMarker(
    val location: StayDeriver.Endpoint,
    val label: String? = null,
    val category: PlaceCategory? = null,
) {
    constructor(location: StayDeriver.Endpoint, place: Place?) :
        this(location, place?.label, place?.placeCategory)
}

/**
 * What a place is drawn as: named makes it a pin in its own category's colors — [withGlyph] where
 * the marker is big enough to hold one — and unnamed leaves it the [unnamed] dot the surface gives
 * a cluster. Both maps here answer it the same way; only the fallback differs — and it need not be a
 * dot: the place map's own centre passes an untagged pin, being about to become one.
 */
private fun markerIcon(
    marker: PlaceMarker,
    withGlyph: Boolean,
    unnamed: String,
    muted: Boolean = false,
): String = if (marker.label != null) placePinImage(marker.category, withGlyph, muted) else unnamed

/**
 * Renders one place on the basemap: the cluster's capture circle (a meter-true polygon around
 * [center]) with every captured track endpoint as small dots, plus [neighbors] — gray
 * neighbor-endpoint dots and labeled named pins — so the radius can be judged against what a wider
 * circle would swallow. The camera fits the circle once on open, and the data is a snapshot: an
 * input change is a full refresh.
 */
@Composable
internal fun MapLibrePlaceMap(
    center: PlaceMarker,
    radiusM: Double,
    endpoints: List<StayDeriver.Endpoint>,
    modifier: Modifier = Modifier,
    neighbors: List<PlaceMarker> = emptyList(),
    // When set, these dots replace [endpoints] and the plain neighbor dots, and the radius decides
    // each one's icon in a layer expression — so dragging costs one property, not a re-upload.
    capture: List<CaptureDot>? = null,
    // Named neighbors' own capture areas, drawn muted under this one. They are what stops a dot
    // joining this place, so seeing them is what makes a gray dot inside your circle make sense.
    rivalAreas: List<PlaceClusterer.Seed> = emptyList(),
    /** A long press on the map, in map coordinates — how the center is placed by hand. */
    onLongPress: (StayDeriver.Endpoint) -> Unit,
) {
    val applied = remember { AppliedPlaceInputs() }
    // The listener is attached once, to a map that outlives every recomposition, so it must read the
    // *current* callback rather than the one the first composition passed — that one would move the
    // pin while offering an Undo back to wherever the pin was when the map was built.
    val longPress by rememberUpdatedState(onLongPress)
    val placeContent = {
        PlaceMapContent(
            center = center,
            radiusM = radiusM,
            markers = PlaceMarkers(endpoints, neighbors, capture),
            rivalAreas = rivalAreas,
        )
    }
    MapLibreStyledMap(
        modifier = modifier,
        onMapReady = { map ->
            map.addOnMapLongClickListener { at ->
                longPress(StayDeriver.Endpoint(at.latitude, at.longitude))
                true
            }
        },
        onStyleLoaded = { ctx, map, style ->
            applied.circleCenter = center.location
            applied.circleRadiusM = radiusM
            applied.markers = endpoints to neighbors
            applied.center = center
            applied.capture = capture
            applied.rivalAreas = rivalAreas
            addPlaceLayers(ctx, style, placeContent())
            framePlace(map, center.location, radiusM)
        },
        onUpdate = { map, style ->
            if (applied.circleCenter != center.location || applied.circleRadiusM != radiusM) {
                applied.circleCenter = center.location
                style.getSourceAs<GeoJsonSource>(PLACE_CIRCLE_SOURCE)
                    ?.setGeoJson(circleFeature(center.location, radiusM))
            }
            if (applied.circleRadiusM != radiusM) {
                applied.circleRadiusM = radiusM
                // A radius that changed re-fits the camera — the circle is the subject and it just
                // grew or shrank. A *moved* pin deliberately does not: the move was aimed at a point
                // the user was looking at, so re-fitting would slide it out from under them and
                // re-zoom to boot, which is exactly the precision the placement was after.
                framePlace(map, center.location, radiusM)
                // The dots do not move; which side of the radius they fall on does.
                style.getLayer(PLACE_MARKER_LAYER)?.setProperties(markerIconProperty(radiusM))
            }
            if (applied.markers != endpoints to neighbors ||
                applied.capture !== capture ||
                applied.center != center
            ) {
                applied.markers = endpoints to neighbors
                applied.capture = capture
                applied.center = center
                style.getSourceAs<GeoJsonSource>(PLACE_MARKER_SOURCE)
                    ?.setGeoJson(placeMarkerCollection(placeContent()))
            }
            if (applied.rivalAreas !== rivalAreas) {
                applied.rivalAreas = rivalAreas
                style.getSourceAs<GeoJsonSource>(PLACE_RIVAL_SOURCE)
                    ?.setGeoJson(captureAreaCollection(rivalAreas))
            }
        },
    )
}

/** What the marker layer draws — three inputs that always travel together. */
private class PlaceMarkers(
    val endpoints: List<StayDeriver.Endpoint>,
    val neighbors: List<PlaceMarker>,
    val capture: List<CaptureDot>?,
)

/** Everything the place map draws: its own area, the markers, and the areas it competes with. */
private class PlaceMapContent(
    val center: PlaceMarker,
    val radiusM: Double,
    val markers: PlaceMarkers,
    val rivalAreas: List<PlaceClusterer.Seed>,
)

/** Last-applied inputs of the place map — value comparisons, the inputs are rebuilt lists. */
private class AppliedPlaceInputs {
    /** Where the circle is drawn and how wide, tracked apart because the two have different
     *  consequences: either redraws the ring, but only a *resize* re-fits the camera and re-decides
     *  which side of the radius each dot falls on. */
    var circleCenter: StayDeriver.Endpoint? = null
    var circleRadiusM: Double? = null
    var markers: Pair<List<StayDeriver.Endpoint>, List<PlaceMarker>>? = null

    /** Naming or tagging a place while its map is open redraws its marker — the source names an
     *  image per category, so both are a feature rebuild rather than a restyle. Its position is
     *  watched twice over, here and in [circleCenter]: the two answer different questions about a
     *  moved pin (which features to rebuild, where to point the camera) and both must be asked. */
    var center: PlaceMarker? = null

    /** Identity, not equality: the scan hands back a new list only when the candidates change. */
    var capture: List<CaptureDot>? = null
    var rivalAreas: List<PlaceClusterer.Seed>? = null
}

private const val PLACE_RIVAL_SOURCE = "place-rival-src"
private const val PLACE_RIVAL_FILL = "place-rival-fill"
private const val PLACE_RIVAL_LINE = "place-rival-line"

private const val PLACE_CIRCLE_SOURCE = "place-circle-src"
private const val PLACE_CIRCLE_FILL = "place-circle-fill"
private const val PLACE_CIRCLE_LINE = "place-circle-line"

private const val PLACE_MARKER_SOURCE = "place-marker-src"
private const val PLACE_MARKER_LAYER = "place-marker-layer"
private const val IMG_NEIGHBOR = "marker-neighbor"

/** The dot a captured track endpoint is drawn as, on both maps — the one image id they share. */
private const val IMG_ENDPOINT = "marker-endpoint"

/**
 * Registers the endpoint dot, [withBrief] adding the orange variant. One function because the
 * drawable and its shadow weight are one decision: a dot registered at a different weight on one map
 * reads as a different kind of thing on the other, which is the opposite of what sharing the id says.
 */
private fun addEndpointDotImages(ctx: Context, style: Style, withBrief: Boolean = false) {
    style.addImage(IMG_ENDPOINT, shadowedBitmap(ctx, R.drawable.ic_marker_endpoint, MarkerShadow.EVIDENCE))
    if (withBrief) {
        style.addImage(
            IMG_ENDPOINT_BRIEF,
            shadowedBitmap(ctx, R.drawable.ic_marker_endpoint_brief, MarkerShadow.EVIDENCE),
        )
    }
}

private fun addPlaceLayers(ctx: Context, style: Style, content: PlaceMapContent) {
    // Rivals first, so this place's own circle reads on top of them where they overlap.
    style.addSource(GeoJsonSource(PLACE_RIVAL_SOURCE, captureAreaCollection(content.rivalAreas)))
    addContextCircleLayers(style, PLACE_RIVAL_SOURCE, PLACE_RIVAL_FILL, PLACE_RIVAL_LINE)
    style.addSource(
        GeoJsonSource(PLACE_CIRCLE_SOURCE, circleFeature(content.center.location, content.radiusM)),
    )
    addCaptureCircleLayers(style, PLACE_CIRCLE_SOURCE, PLACE_CIRCLE_FILL, PLACE_CIRCLE_LINE)
    addEndpointDotImages(ctx, style)
    style.addImage(IMG_NEIGHBOR, shadowedBitmap(ctx, R.drawable.ic_marker_neighbor, MarkerShadow.EVIDENCE))
    // This map holds one place at the zoom its own radius frames, so its pins are never the
    // small form: full size, glyphed, and no disc variant to carry.
    addPlacePinImages(ctx, style, PinSet.PlacesAndNeighbors)
    style.addSource(
        GeoJsonSource(
            PLACE_MARKER_SOURCE,
            placeMarkerCollection(content),
        ),
    )
    style.addLayer(
        labeledSymbolLayer(ctx, PLACE_MARKER_LAYER, PLACE_MARKER_SOURCE)
            .withProperties(markerIconProperty(content.radiusM)),
    )
}

/**
 * Neighbor context first (visually underneath), then the place's own endpoint dots, then the
 * pin marker last so it draws on top.
 */
private fun placeMarkerCollection(content: PlaceMapContent): FeatureCollection {
    val markers = content.markers
    val capture = markers.capture
    val features = ArrayList<Feature>(markers.neighbors.size + markers.endpoints.size + 1)
    // Named neighbors are pins in both modes; their plain dots are only drawn when the
    // capture form isn't, because there they are already among the candidates.
    markers.neighbors.forEach { n ->
        if (capture != null && n.label == null) return@forEach
        features.add(neighborFeature(n))
    }
    if (capture != null) {
        capture.forEach { dot ->
            features.add(
                // A settled dot is written as the gray icon; one still in play carries the
                // distance instead and lets the layer decide.
                if (dot.distanceM == null) {
                    endpointFeature(dot.location, IMG_NEIGHBOR)
                } else {
                    endpointFeature(dot.location, IMG_NEIGHBOR, distanceM = dot.distanceM)
                },
            )
        }
    } else {
        markers.endpoints.forEach { features.add(endpointFeature(it, IMG_ENDPOINT)) }
    }
    // A pin whether or not the row exists yet — this map shows what saving would produce, and saving
    // produces a pin. Said through the rule's own `unnamed` seam rather than around it: an untagged
    // pin is simply the fallback *this* surface gives, where a dot would be the same marker as the
    // endpoints it sits among in another hue, and these are told apart by size and shape first.
    // Carries no label: its name is the screen's title, not something to repeat on it.
    val center = content.center
    val centerIcon = markerIcon(center, withGlyph = true, unnamed = placePinImage(null, withGlyph = true))
    features.add(endpointFeature(center.location, centerIcon))
    return FeatureCollection.fromFeatures(features)
}

/**
 * A neighbouring place, receding on both counts a raster symbol can: a desaturated bitmap and less
 * ink. One function because the two are one decision — set on the feature but not the image (or the
 * reverse) and the pin half-recedes, with nothing to catch it.
 */
private fun neighborFeature(n: PlaceMarker): Feature = endpointFeature(
    n.location,
    markerIcon(n, withGlyph = true, unnamed = IMG_NEIGHBOR, muted = true),
    n.label,
    muted = true,
)

/**
 * The marker layer's icon property: only features carrying a distance (capture dots) resolve against
 * the radius; the rest keep their written icon. Keyed on the property being *present*, not a sentinel
 * icon name — that shares the image ids' value space (an image id "auto" would break it). A radius
 * change costs only this rebuild; correct with or without capture dots, so installed unconditionally.
 */
private fun markerIconProperty(radiusM: Double) = PropertyFactory.iconImage(
    Expression.switchCase(
        Expression.has(DISTANCE_KEY),
        Expression.switchCase(
            Expression.lte(Expression.get(DISTANCE_KEY), Expression.literal(radiusM)),
            Expression.literal(IMG_ENDPOINT),
            Expression.literal(IMG_NEIGHBOR),
        ),
        Expression.get(ICON_KEY),
    ),
)

// --- All-places overview map ---------------------------------------------------------------

/** One place on the overview map: what to draw, plus what only this map needs of it. */
internal class OverviewPlace(
    val marker: PlaceMarker,
    /** The place-detail key reported on tap. */
    val key: String,
    /** The place's only stay is a merge-eligible short stop (a likely split-track artifact,
     *  not a real visit) — unnamed dots render orange instead of blue. */
    val brief: Boolean = false,
)

/**
 * Every place on one map: labeled pins for named places, small dots for unnamed clusters, sized
 * down the zoom range (see [overviewIconSize]) and framed to fit them all once on open. A pin is
 * colored by its category's group and shows the category's glyph at [GLYPH_ZOOM]. Tapping a marker
 * reports its key via [onOpen].
 */
@Composable
internal fun MapLibrePlacesMap(
    places: List<OverviewPlace>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val applied = remember { AppliedOverviewInputs() }
    applied.onOpen = onOpen
    MapLibreStyledMap(
        modifier = modifier,
        onMapReady = { map ->
            map.addOnMapClickListener { latLng ->
                val key = featureNear(map, latLng, OVERVIEW_LAYER)?.getStringProperty(PLACE_KEY)
                if (key != null) applied.onOpen(key)
                key != null
            }
        },
        onStyleLoaded = { ctx, map, style ->
            applied.places = places
            addOverviewLayers(ctx, style, places)
            frameTo(map, places.map { LatLng(it.marker.location.lat, it.marker.location.lon) }, singlePointZoom = 13.0)
        },
        onUpdate = { _, style ->
            if (applied.places !== places) {
                applied.places = places
                style.getSourceAs<GeoJsonSource>(OVERVIEW_SOURCE)
                    ?.setGeoJson(overviewCollection(places))
            }
        },
    )
}

/** The topmost feature of [layer] within a finger's reach of [latLng], or null — the one tap
 *  hit-test every marker layer here shares, 36 px of slop included. */
private fun featureNear(map: MapLibreMap, latLng: LatLng, layer: String): Feature? {
    val screen = map.projection.toScreenLocation(latLng)
    val touch = RectF(screen.x - 36, screen.y - 36, screen.x + 36, screen.y + 36)
    return map.queryRenderedFeatures(touch, layer).firstOrNull()
}

/** Last-applied input of the all-places overview map. */
private class AppliedOverviewInputs {
    var places: List<OverviewPlace>? = null

    /** The click listener is registered once, so it reads the handler from here to never go stale. */
    var onOpen: (String) -> Unit = {}
}

private const val OVERVIEW_SOURCE = "places-overview-src"
private const val OVERVIEW_LAYER = "places-overview-layer"
private const val IMG_ENDPOINT_BRIEF = "marker-endpoint-brief"

/** The place-detail key a tapped feature reports back through [MapLibrePlacesMap]'s `onOpen`. */
private const val PLACE_KEY = "key"

/** The image a feature takes once it is drawn big enough to carry a glyph — see [GLYPH_ZOOM]. */
private const val GLYPH_KEY = "glyph"

/** What a feature *is*, kept apart from which image draws it: a pin's image id names its category. */
private const val KIND_KEY = "kind"
private const val KIND_PIN = "pin"
private const val KIND_DOT = "dot"

/**
 * The zoom from which an overview pin carries its category's glyph. Below it the pin is a colored
 * disc — the color survives being scaled down where a 24-unit glyph does not, and drawing the glyph
 * all the way out reads as a field of smudges rather than as symbols. It is also the ramp's middle
 * stop, so the glyph arrives on a pin already near full size: a glyph appearing on a marker still too
 * small to hold it is what the threshold exists to prevent.
 *
 * A threshold at all is a property of a *field* of places, framed to fit however far apart they are.
 * The track map's two end pins carry their glyph and name at every zoom: two markers cannot smudge
 * into each other, and a pin small enough to need this rule would be a pin whose name is the only
 * thing saying where the journey went.
 *
 * **Whole numbers only.** A camera expression on a *layout* property — which `icon-image`,
 * `text-field` and `icon-size` all are — is evaluated at integer zooms alone, so a fractional
 * threshold silently takes effect at the integer above it.
 */
private const val GLYPH_ZOOM = 9f

/**
 * The zoom from which an overview pin carries its place's name. Wider than this the map answers
 * *where* the places are and how they group, which the colored discs do on their own; a field of
 * names over a region is a wall of text with most of it dropped to collision anyway. Above
 * [GLYPH_ZOOM], so a pin gains its glyph first and its name second — shape, then word.
 */
private const val LABEL_ZOOM = 11f

private fun addOverviewLayers(ctx: Context, style: Style, places: List<OverviewPlace>) {
    addEndpointDotImages(ctx, style, withBrief = true)
    addPlacePinImages(ctx, style)
    style.addSource(GeoJsonSource(OVERVIEW_SOURCE, overviewCollection(places)))
    style.addLayer(
        labeledSymbolLayer(ctx, OVERVIEW_LAYER, OVERVIEW_SOURCE).withProperties(
            // Every feature names both of its variants; zoom picks which one is drawn. A dot names
            // its own icon twice — it has no glyph to gain, and a missing image draws nothing.
            PropertyFactory.iconImage(
                Expression.step(
                    Expression.zoom(),
                    Expression.get(ICON_KEY),
                    Expression.stop(GLYPH_ZOOM, Expression.get(GLYPH_KEY)),
                ),
            ),
            PropertyFactory.iconSize(overviewIconSize()),
            // Emptied rather than faded out: an invisible label still takes part in collision and
            // would push its neighbours' names around from behind.
            PropertyFactory.textField(
                Expression.step(
                    Expression.zoom(),
                    Expression.literal(""),
                    Expression.stop(LABEL_ZOOM, Expression.get(LABEL_KEY)),
                ),
            ),
        ),
    )
}

/**
 * Marker size down the overview map's zoom range: this map is framed to fit every place at once,
 * so it opens as wide as the history is spread and pans down to a single street — one fixed size
 * cannot serve both. The pin shrinks the harder of the two, because a wide view is asking where the
 * places *are*, not which one is which; the dots shrink gently so they stay visible as a density
 * without ever catching the pin up.
 *
 * Zoom must be the interpolation's own input — it may not sit nested inside a per-feature
 * expression — so the ramp is over zoom with each stop choosing per feature, never the reverse.
 *
 * The ramp steps rather than glides: `icon-size` is a layout property, so this is sampled at whole
 * zooms and the sizes between the stops are the ones those samples land on.
 */
private fun overviewIconSize(): Expression =
    Expression.interpolate(
        Expression.linear(),
        Expression.zoom(),
        // Interpolation clamps outside its stops, so the smallest size holds all the way out to a
        // world view without a stop of its own down there.
        Expression.stop(8f, sizeByMarker(pin = 0.4f, dot = 0.65f)),
        Expression.stop(GLYPH_ZOOM, sizeByMarker(pin = 0.76f, dot = 0.8f)),
        Expression.stop(12f, sizeByMarker(pin = 1f, dot = 1f)),
    )

/**
 * Keyed on the feature's kind rather than its icon id: a pin's id says which *category* it is, and
 * matching sixteen of those to learn it is a pin would put the palette in the size ramp.
 */
private fun sizeByMarker(pin: Float, dot: Float): Expression =
    Expression.switchCase(
        Expression.eq(Expression.get(KIND_KEY), Expression.literal(KIND_PIN)),
        Expression.literal(pin),
        Expression.literal(dot),
    )

private fun overviewCollection(places: List<OverviewPlace>): FeatureCollection =
    FeatureCollection.fromFeatures(
        // Unnamed dots first so named pins draw (and hit-test) on top.
        places.sortedBy { it.marker.label != null }.map { p ->
            val dot = if (p.brief) IMG_ENDPOINT_BRIEF else IMG_ENDPOINT
            Feature.fromGeometry(
                Point.fromLngLat(p.marker.location.lon, p.marker.location.lat),
                JsonObject().apply {
                    addProperty(KIND_KEY, if (p.marker.label != null) KIND_PIN else KIND_DOT)
                    addProperty(ICON_KEY, markerIcon(p.marker, withGlyph = false, unnamed = dot))
                    addProperty(GLYPH_KEY, markerIcon(p.marker, withGlyph = true, unnamed = dot))
                    addProperty(LABEL_KEY, p.marker.label ?: "")
                    addProperty(PLACE_KEY, p.key)
                },
            )
        },
    )

// --- Trip-endpoints map ---------------------------------------------------------------------

private const val TRIP_MARKER_SOURCE = "trip-marker-src"
private const val TRIP_MARKER_LAYER = "trip-marker-layer"

/** Last-applied inputs of the trip map; the two pins are one pair, always redrawn together. */
private class AppliedTripInputs {
    var pins: Pair<StayDeriver.Endpoint?, StayDeriver.Endpoint?>? = null
    var places: List<OverviewPlace>? = null
}

/**
 * The add-trip form's map: the user's places as the field the overview map draws — **the
 * first-class way to pick an end**, a tap selecting one — with the trip's own two labeled pins
 * above them and a long press placing the active pin anywhere else. No capture circles and no
 * endpoint dots, which is why [MapLibrePlaceMap] isn't bent to draw it. The trip pins are the
 * untagged place pin: the marker vocabulary already means "a spot the user named", and these are
 * two of those in the making.
 */
@Composable
internal fun MapLibreTripMap(
    origin: StayDeriver.Endpoint?,
    destination: StayDeriver.Endpoint?,
    places: List<OverviewPlace>,
    onLongPress: (StayDeriver.Endpoint) -> Unit,
    /** A tap on a place's pin, reported at the pin's own spot rather than the finger's, with the
     *  place's label where the feature carries one — what the pick was picked *by*. */
    onPlaceTap: (StayDeriver.Endpoint, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val applied = remember { AppliedTripInputs() }
    // Both listeners are attached once to a map outliving recompositions — they must read the
    // current callbacks, or a press would land on whichever end was active when the map was built.
    val longPress by rememberUpdatedState(onLongPress)
    val placeTap by rememberUpdatedState(onPlaceTap)
    MapLibreStyledMap(
        modifier = modifier,
        onMapReady = { map ->
            map.addOnMapLongClickListener { at ->
                longPress(StayDeriver.Endpoint(at.latitude, at.longitude))
                true
            }
            map.addOnMapClickListener { latLng ->
                val feature = featureNear(map, latLng, OVERVIEW_LAYER)
                val point = feature?.geometry() as? Point
                if (point != null) {
                    placeTap(
                        StayDeriver.Endpoint(point.latitude(), point.longitude()),
                        // Unnamed features carry an empty label, not an absent one.
                        feature.getStringProperty(LABEL_KEY)?.takeIf { it.isNotEmpty() },
                    )
                }
                point != null
            }
        },
        onStyleLoaded = { ctx, map, style ->
            applied.pins = origin to destination
            applied.places = places
            // The overview map's own layers, ids and all — a style belongs to one MapView, so the
            // names can't collide, and the places here are exactly that map's field of pins.
            addOverviewLayers(ctx, style, places)
            style.addSource(GeoJsonSource(TRIP_MARKER_SOURCE, tripMarkerCollection(origin, destination)))
            style.addLayer(labeledSymbolLayer(ctx, TRIP_MARKER_LAYER, TRIP_MARKER_SOURCE))
            frameTripMap(map, origin, destination, places, opening = true)
        },
        onUpdate = { map, style ->
            if (applied.places !== places) {
                applied.places = places
                style.getSourceAs<GeoJsonSource>(OVERVIEW_SOURCE)
                    ?.setGeoJson(overviewCollection(places))
            }
            if (applied.pins != origin to destination) {
                applied.pins = origin to destination
                style.getSourceAs<GeoJsonSource>(TRIP_MARKER_SOURCE)
                    ?.setGeoJson(tripMarkerCollection(origin, destination))
                frameTripMap(map, origin, destination, places, opening = false)
            }
        },
    )
}

private fun tripMarkerCollection(
    origin: StayDeriver.Endpoint?,
    destination: StayDeriver.Endpoint?,
): FeatureCollection {
    val pin = placePinImage(null, withGlyph = true)
    return FeatureCollection.fromFeatures(
        listOfNotNull(
            origin?.let { endpointFeature(it, pin, "Origin") },
            destination?.let { endpointFeature(it, pin, "Destination") },
        ),
    )
}

/**
 * Frames the trip pins only when one sits outside the view's central region: a long-pressed pin is
 * already under the user's eye and yanking the camera would fight the gesture, while a pin picked
 * by name can land anywhere on the globe. On opening with no pins yet the camera fits the place
 * field instead — the ground the user's history is on is where a trip's ends are picked from.
 */
private fun frameTripMap(
    map: MapLibreMap,
    origin: StayDeriver.Endpoint?,
    destination: StayDeriver.Endpoint?,
    places: List<OverviewPlace>,
    opening: Boolean,
) {
    val pins = listOfNotNull(origin, destination).map { LatLng(it.lat, it.lon) }
    if (pins.isEmpty()) {
        if (opening && places.isNotEmpty()) {
            frameTo(map, places.map { LatLng(it.marker.location.lat, it.marker.location.lon) }, singlePointZoom = 13.0)
        }
        return
    }
    if (!opening) {
        val visible = map.projection.visibleRegion.latLngBounds
        if (pins.all { visible.containsWithMargin(it.latitude, it.longitude) }) return
    }
    frameTo(map, pins, singlePointZoom = 9.0)
}
