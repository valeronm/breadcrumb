package io.github.valeronm.breadcrumb.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.utils.ColorUtils

/** Adds the source and faded layers for [idPrefix], empty until [ContextLineBuild] fills them. */
internal fun addContextLines(style: Style, idPrefix: String, dark: Boolean) {
    style.addSource(GeoJsonSource(contextSource(idPrefix), NO_FEATURES))
    addLineLayersByMeasure(
        style,
        "$idPrefix-layer",
        contextSource(idPrefix),
        PropertyFactory.lineColor(Expression.get(COLOR_KEY)),
        PropertyFactory.lineOpacity(contextOpacity(dark, CONTEXT_OPACITY)),
        PropertyFactory.lineWidth(TRACK_LINE_WIDTH),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
    )
}

/**
 * Fills the source [addContextLines] made for [idPrefix] with [lines], and again whenever [keys] change. [lines] runs
 * off the main thread, since a context line keeps every fix.
 */
@Composable
internal fun ContextLineBuild(style: Style, idPrefix: String, vararg keys: Any?, lines: () -> List<CasedLine>) {
    LaunchedEffect(style, *keys) {
        val json = withContext(Dispatchers.Default) { contextLinesJson(lines()) }
        style.getSourceAs<GeoJsonSource>(contextSource(idPrefix))?.setGeoJson(json)
    }
}

/** A feature per run of [lines] ([CasedLine.runs]), in its color and marked [UNMEASURED_KEY] where its line is. */
internal fun contextLinesJson(lines: List<CasedLine>): String {
    val properties = lines.map { line -> line.runs.map { run -> propertiesJson(line.color(run.last), line.unmeasured) } }
    var length = COLLECTION_HEAD.length + COLLECTION_TAIL.length
    lines.forEachIndexed { k, line ->
        line.runs.forEachIndexed { r, run ->
            length += LINE_FEATURE_HEAD.length + positionsLength(run) + LINE_FEATURE_MID.length + properties[k][r].length + 2
        }
    }
    // A builder grows by doubling, holding its old and its doubled array at once.
    val json = StringBuilder(length).append(COLLECTION_HEAD)
    var first = true
    lines.forEachIndexed { k, line ->
        line.runs.forEachIndexed { r, run ->
            if (!first) json.append(',')
            first = false
            json.append(LINE_FEATURE_HEAD).appendPositions(line.lonLat, run, line.greatCircleLegs)
            json.append(LINE_FEATURE_MID).append(properties[k][r]).append('}')
        }
    }
    return json.append(COLLECTION_TAIL).toString()
}

private fun propertiesJson(argb: Int, unmeasured: Boolean): String =
    "{\"$COLOR_KEY\":\"${ColorUtils.colorToRgbaString(argb)}\"" + (if (unmeasured) ",\"$UNMEASURED_KEY\":true" else "") + "}"

private fun contextSource(idPrefix: String) = "$idPrefix-src"

private const val COLOR_KEY = "color"

private const val CONTEXT_OPACITY = 0.4f
