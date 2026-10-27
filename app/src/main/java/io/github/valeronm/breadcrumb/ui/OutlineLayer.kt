package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.domain.CountryBorders
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * The country outlines a place's name is decided by ([CountryBorders]), drawn over the basemap's own
 * borders so the two can be told apart where they disagree.
 */

private const val OUTLINE_SOURCE = "dev-outlines-src"
private const val OUTLINE_LINE = "dev-outlines-line"
private const val OUTLINE_LABEL = "dev-outlines-label"
private const val COUNTRY_KEY = "country"
private const val NO_OUTLINES = "{\"type\":\"FeatureCollection\",\"features\":[]}"

/** Added before a map's own layers, so its places draw over the outlines. */
internal fun addOutlineLayers(style: Style, dark: Boolean, outlines: String?) {
    val ink = if (dark) "#FF7AC6" else "#C2185B"
    style.addSource(GeoJsonSource(OUTLINE_SOURCE))
    setOutlines(style, outlines)
    style.addLayer(
        LineLayer(OUTLINE_LINE, OUTLINE_SOURCE).withProperties(
            PropertyFactory.lineColor(ink),
            PropertyFactory.lineWidth(1.5f),
        ),
    )
    style.addLayer(
        SymbolLayer(OUTLINE_LABEL, OUTLINE_SOURCE).withProperties(
            *labelLettering(dark),
            PropertyFactory.textField(Expression.get(COUNTRY_KEY)),
            PropertyFactory.textSize(14f),
            PropertyFactory.textColor(ink),
        ),
    )
}

/** Draws [outlines], from [outlinesGeoJson], or none where null. */
internal fun setOutlines(style: Style, outlines: String?) {
    style.getSourceAs<GeoJsonSource>(OUTLINE_SOURCE)?.setGeoJson(outlines ?: NO_OUTLINES)
}

/** Text rather than MapLibre's geometry objects, which would hold a boxed point for every vertex of the world's outlines. */
internal fun outlinesGeoJson(borders: CountryBorders): String {
    val shapes = borders.shapes()
    val vertices = shapes.sumOf { shape -> shape.rings.sumOf { it.size / 2 + 1 } }
    return buildString(vertices * CHARS_PER_VERTEX) { writeOutlines(shapes) }
}

/** At least the length of a coordinate pair as written, `[-123.45678,12.34567],`, so the builder never grows. */
private const val CHARS_PER_VERTEX = 24

private fun StringBuilder.writeOutlines(shapes: List<CountryBorders.Shape>) {
    append("{\"type\":\"FeatureCollection\",\"features\":[")
    shapes.forEachIndexed { index, shape ->
        if (index > 0) append(',')
        append("{\"type\":\"Feature\",\"properties\":{\"").append(COUNTRY_KEY).append("\":\"").append(shape.country)
        append("\"},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[")
        shape.rings.forEachIndexed { r, ring ->
            if (r > 0) append(',')
            append('[')
            val vertices = ring.size / 2
            // GeoJSON is longitude first and closes a ring by repeating its first point, which a [CountryBorders.Shape] leaves out.
            for (v in 0..vertices) {
                val at = 2 * (v % vertices)
                if (v > 0) append(',')
                append('[').append(ring[at + 1]).append(',').append(ring[at]).append(']')
            }
            append(']')
        }
        append("]}}")
    }
    append("]}")
}
