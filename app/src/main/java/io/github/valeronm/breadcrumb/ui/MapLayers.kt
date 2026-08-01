package io.github.valeronm.breadcrumb.ui

import android.content.Context
import com.google.gson.JsonObject
import io.github.valeronm.breadcrumb.domain.PlaceClusterer
import io.github.valeronm.breadcrumb.domain.StayDeriver
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.PropertyValue
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.cos
import kotlin.math.sin

/**
 * What every map draws on a basemap: the capture-circle rings, the symbol layers a marker rides,
 * and the features feeding both. A map's own layers — the ones only it has — belong with that map;
 * what lands here is what two of them would otherwise each spell out.
 */

/** Feature property names shared by the marker features and the layer expressions that read them. */
internal const val ICON_KEY = "icon"
internal const val LABEL_KEY = "label"

/**
 * Distance from the pin a capture dot sits at, present only on features whose icon the *layer*
 * resolves against the live radius. Read by an expression keyed on the property being **present**,
 * so writing it is what opts a feature in — a feature without it keeps the icon it was written with.
 */
internal const val DISTANCE_KEY = "dm"

private const val MUTED_KEY = "muted"

private const val CIRCLE_FILL = 0x2E5B9BF0
private const val CIRCLE_LINE = 0x995B9BF0.toInt()

/**
 * A capture area on a map that is not *about* that area — a neighbouring place beside the one being
 * edited, and the reach of the place at a track's end. Faint enough to read as context rather than as
 * a second subject.
 *
 * At a track's end this is deliberately not matched by the pin above it, which keeps its full colour:
 * the pin answers where the journey began and ended, and the ring only says how far that place
 * reaches — the same information the place's own screen exists to show. Muting the ring alone is what
 * lets the answer stay loud while its footnote recedes.
 */
private const val CONTEXT_AREA_OPACITY = 0.35f

/**
 * The ink a neighbouring place's pin and label keep. Gentler than [CONTEXT_AREA_OPACITY], and gentler
 * than it would be if it worked alone: the pin has already given up most of its saturation (see
 * `categoryMutedPinColor`), so this is the second half of a recede rather than the whole of one, and
 * a neighbour still has to be identifiable — which is the entire reason it is drawn.
 */
private const val NEIGHBOR_MUTED_OPACITY = 0.7f

/**
 * The capture-circle look — translucent fill + dashed outline — shared by every ring on every map: a
 * place's own reach, its neighbours', the stops detected inside a track, and the places at that
 * track's ends. All of them are "ground that counts as one spot", so all of them read as one species,
 * and only opacity says which of them the screen is about — [addContextCircleLayers] for the ones it
 * isn't. On a track's map that puts the dwell circles at full strength while an end place's ring
 * recedes: a dwell is *this track's* own evidence, where the ring belongs to a place the track
 * merely arrived in.
 */
internal fun addCaptureCircleLayers(
    style: Style,
    sourceId: String,
    fillLayerId: String,
    lineLayerId: String,
    vararg extraProps: PropertyValue<*>,
) {
    style.addLayer(
        FillLayer(fillLayerId, sourceId).withProperties(
            PropertyFactory.fillColor(CIRCLE_FILL),
            *extraProps,
        ),
    )
    style.addLayer(
        LineLayer(lineLayerId, sourceId).withProperties(
            PropertyFactory.lineColor(CIRCLE_LINE),
            PropertyFactory.lineWidth(1.5f),
            PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
            *extraProps,
        ),
    )
}

/**
 * [addCaptureCircleLayers] for a ring the screen is *not* about — a neighbouring place beside the one
 * being edited, the reach of the place at a track's end. One function so the weight of "context" is
 * set once for every surface that has some, rather than each remembering to pass the pair.
 */
internal fun addContextCircleLayers(
    style: Style,
    sourceId: String,
    fillLayerId: String,
    lineLayerId: String,
) = addCaptureCircleLayers(
    style, sourceId, fillLayerId, lineLayerId,
    PropertyFactory.fillOpacity(CONTEXT_AREA_OPACITY),
    PropertyFactory.lineOpacity(CONTEXT_AREA_OPACITY),
)

/**
 * Shared base of the marker layers: an icon per feature, drawn in source order — the load-bearing
 * part: left to itself a symbol layer stacks point symbols by screen position, so the lower marker
 * covers the rest, and a collection with a hierarchy in it ends with the marker that matters most (a
 * place's pin among its dots, a track's start/end among rejected fixes). Overlap and placement are
 * off likewise: these are markers, not labels competing for room.
 */
internal fun markerSymbolLayer(id: String, source: String): SymbolLayer =
    SymbolLayer(id, source).withProperties(
        PropertyFactory.symbolZOrder(Property.SYMBOL_Z_ORDER_SOURCE),
        PropertyFactory.iconImage(Expression.get(ICON_KEY)),
        PropertyFactory.iconAllowOverlap(true),
        PropertyFactory.iconIgnorePlacement(true),
        PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
    )

/**
 * How far a place's name sits below its anchor, in ems of [PropertyFactory.textSize] — at the 12
 * used here, ~14 dp. Measured against the *pin* rather than the text, and squeezed from both sides:
 * a full-size pin is `PIN_BASE_DP * PIN_MAX_SCALE` ≈ 28 dp, so its lower half reaches ~14 dp below
 * centre and less than this draws the name through it, while a couple of dp more and the name stops
 * reading as *this pin's* and starts looking like a caption adrift under it. The usable range is
 * about a third of an em wide and this sits at the bottom of it.
 *
 * Flat rather than ramped by zoom: the place map draws pins at full size, and a field of places shows
 * names only once the overview map's label threshold is reached, by which point the size ramp has the
 * pin at ~0.9 — so the most this is ever out by is a dp of extra gap at one zoom stop, never an overlap.
 */
private const val PLACE_LABEL_OFFSET_EM = 1.2f

/** Labeled pin layer, shared by every map that draws a place: a marker plus a label under it. */
internal fun labeledSymbolLayer(ctx: Context, id: String, source: String): SymbolLayer {
    val dark = isDarkUi(ctx)
    return markerSymbolLayer(id, source).withProperties(
        // Named features carry a label under the pin; other features have an empty string.
        PropertyFactory.textField(Expression.get(LABEL_KEY)),
        PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
        PropertyFactory.textSize(12f),
        PropertyFactory.textColor(if (dark) "#C8CFC6" else "#38423B"),
        PropertyFactory.textHaloColor(if (dark) "#14211A" else "#F0F2EE"),
        PropertyFactory.textHaloWidth(1.2f),
        PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
        PropertyFactory.textOffset(arrayOf(0f, PLACE_LABEL_OFFSET_EM)),
        PropertyFactory.textOptional(true),
        // Context recedes twice over: a muted pin gives up most of its chroma in the bitmap it
        // names *and* some of its ink here, which is what separates it from its neighbours' circles
        // as well as from the subject. The label can only give up ink, being neutral in both themes
        // with no chroma to drain. Keyed on the property being present, so a collection that never
        // writes it — the all-places overview, a track's end places — is unaffected without knowing
        // the rule exists.
        PropertyFactory.iconOpacity(mutedOpacity()),
        PropertyFactory.textOpacity(mutedOpacity()),
    )
}

private fun mutedOpacity(): Expression = Expression.switchCase(
    Expression.has(MUTED_KEY),
    Expression.literal(NEIGHBOR_MUTED_OPACITY),
    Expression.literal(1f),
)

/**
 * A place-style marker at [e]. Two of the arguments hand a decision to the layer rather than making
 * it: [distanceM] surrenders [icon] to whatever the live radius says (see [DISTANCE_KEY]), and
 * [muted] costs the feature ink on top of whatever its bitmap already gave up. A null [label] is an
 * unnamed feature, which is the empty string the text layer needs rather than an absent property.
 */
internal fun endpointFeature(
    e: StayDeriver.Endpoint,
    icon: String,
    label: String? = null,
    distanceM: Double? = null,
    muted: Boolean = false,
): Feature =
    Feature.fromGeometry(
        Point.fromLngLat(e.lon, e.lat),
        JsonObject().apply {
            addProperty(ICON_KEY, icon)
            addProperty(LABEL_KEY, label ?: "")
            distanceM?.let { addProperty(DISTANCE_KEY, it) }
            if (muted) addProperty(MUTED_KEY, true)
        },
    )

/**
 * A capture area per seed, each as its own polygon — the named neighbours a radius is judged against,
 * or the places at a track's ends. Taken as [PlaceClusterer.Seed]s because a pin and its reach are all
 * a ring needs, which is the same projection the clustering reads. Empty is a valid, common answer.
 */
internal fun captureAreaCollection(seeds: List<PlaceClusterer.Seed>): FeatureCollection =
    FeatureCollection.fromFeatures(seeds.map { circleFeature(it.anchor, it.radiusM) })

/** A meter-true circle approximated by a 72-gon (fine at place zoom levels). */
internal fun circleFeature(center: StayDeriver.Endpoint, radiusM: Double): Feature {
    val ring = (0..72).map { i ->
        val theta = 2 * Math.PI * i / 72
        val (lat, lon) = offsetMeters(center, radiusM * sin(theta), radiusM * cos(theta))
        Point.fromLngLat(lon, lat)
    }
    return Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))
}

/** [e] displaced by meters north/east into a (lat, lon) pair — flat-earth, fine at circle scale. */
internal fun offsetMeters(e: StayDeriver.Endpoint, northM: Double, eastM: Double): Pair<Double, Double> {
    val lat = e.lat + northM / 111_320.0
    val lon = e.lon + eastM / (111_320.0 * cos(Math.toRadians(e.lat)))
    return lat to lon
}

/** Fits the camera to the capture circle [radiusM] around [center] — the ring is the subject. */
internal fun framePlace(map: MapLibreMap, center: StayDeriver.Endpoint, radiusM: Double) {
    val (north, _) = offsetMeters(center, radiusM, 0.0)
    val (south, _) = offsetMeters(center, -radiusM, 0.0)
    val (_, east) = offsetMeters(center, 0.0, radiusM)
    val (_, west) = offsetMeters(center, 0.0, -radiusM)
    val bounds = LatLngBounds.Builder()
        .include(LatLng(north, east))
        .include(LatLng(south, west))
        .build()
    map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 64))
}
