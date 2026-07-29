package io.github.valeronm.breadcrumb.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.core.graphics.createBitmap
import io.github.valeronm.breadcrumb.domain.PlaceCategory
import org.maplibre.android.maps.Style
import kotlin.math.ceil
import kotlin.math.roundToInt

// The bitmaps a map's symbol layers draw: markers loaded from drawables, place pins drawn here
// because their fill varies per category, and the shadow that lifts either off the basemap.

private fun drawableBitmap(ctx: Context, resId: Int): Bitmap {
    val d = AppCompatResources.getDrawable(ctx, resId)!!
    val w = d.intrinsicWidth.coerceAtLeast(1)
    val h = d.intrinsicHeight.coerceAtLeast(1)
    val bmp = createBitmap(w, h)
    d.setBounds(0, 0, w, h)
    d.draw(Canvas(bmp))
    return bmp
}

/**
 * How hard a marker is lifted off the basemap. Two weights, because a shadow every marker shares
 * lifts none of them: the marker a map is *about* carries [SUBJECT], the fixes and dots that
 * evidence it carry [EVIDENCE], and the gap between the two is the hierarchy.
 *
 * [dropDp] is 0 on a rotation-aligned layer. There the icon turns with the map, its shadow with it,
 * so an offset one swings around the marker as the map is rotated — light from a fixed direction is
 * only readable where the icon holds still.
 */
internal data class MarkerShadow(val blurDp: Float, val dropDp: Float, val alpha: Int) {
    companion object {
        val SUBJECT = MarkerShadow(blurDp = 2.5f, dropDp = 1.5f, alpha = 90)
        val EVIDENCE = MarkerShadow(blurDp = 1.5f, dropDp = 1f, alpha = 60)
        val SUBJECT_TURNING = SUBJECT.copy(dropDp = 0f)
        val EVIDENCE_TURNING = EVIDENCE.copy(dropDp = 0f)
    }
}

/** [withShadow] over a drawable — how every marker that isn't a place pin is built. */
internal fun shadowedBitmap(ctx: Context, resId: Int, shadow: MarkerShadow): Bitmap =
    withShadow(ctx, drawableBitmap(ctx, resId), shadow, scale = 1f)

/**
 * A marker with a soft shadow cast under it, so it reads as sitting above the basemap. The padding
 * is symmetric: the layer anchors the image's *center* to the coordinate, so the drop has to fall
 * inside the bottom padding instead of moving the marker off the spot it marks. The icon's own
 * pixels are untouched, so the marker's drawn size doesn't change. [scale] carries to the shadow as
 * well — it is the icon's own shadow, not a fixed-size smudge behind a marker of any size.
 */
private fun withShadow(ctx: Context, icon: Bitmap, shadow: MarkerShadow, scale: Float): Bitmap {
    val density = ctx.resources.displayMetrics.density
    val blur = shadow.blurDp * density * scale
    val drop = shadow.dropDp * density * scale
    val pad = ceil(blur * 2f + drop).toInt()
    val blurPaint = Paint().apply {
        maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
    }
    val inkPaint = Paint().apply {
        color = android.graphics.Color.BLACK
        alpha = shadow.alpha
    }
    // extractAlpha grows the mask to fit the blur and reports where that put its origin.
    val blurOrigin = IntArray(2)
    val shadowMask = icon.extractAlpha(blurPaint, blurOrigin)
    val out = createBitmap(icon.width + pad * 2, icon.height + pad * 2)
    Canvas(out).apply {
        drawBitmap(shadowMask, (pad + blurOrigin[0]).toFloat(), pad + drop + blurOrigin[1], inkPaint)
        drawBitmap(icon, pad.toFloat(), pad.toFloat(), null)
    }
    return out
}

/**
 * The place pin as a style image: a white halo, a fill saying what the place is *for*, and — only
 * in the [withGlyph] variant — the category's glyph inside it. Drawn rather than loaded from a
 * drawable because the fill varies per category, which a vector asset can't do without one asset
 * per color.
 *
 * Every variant is registered up front ([addPlacePinImages]) so a re-tag or a zoom threshold is a
 * change of *which id a feature names*, never a bitmap built mid-gesture.
 */
internal fun placePinImage(category: PlaceCategory?, withGlyph: Boolean): String =
    "place-pin-${category?.code ?: "untagged"}-${if (withGlyph) "glyph" else "disc"}"

/**
 * Geometry as fractions of the image, in the 24-unit viewport of the drawable this replaces. The
 * halo is what separates a pin from the basemap under it, not part of what the pin *says* — so it
 * is the thinnest ring that still does that, and the fill it leaves is the color the map is read by.
 */
private const val PIN_BASE_DP = 18f
private const val PIN_HALO_RADIUS = 11f / 24f
private const val PIN_FILL_RADIUS = 9f / 24f

/**
 * The glyph's box, wider than the fill it sits in looks to allow: a Material icon carries its own
 * padding inside the 24-unit viewport, so a box matched to the fill would draw ink at half the fill.
 */
private const val PIN_GLYPH_BOX = 12f / 24f

private class PinImage(val id: String, val glyphed: Boolean, val bitmap: Bitmap)

private class PinImageSet(val scale: Float, val untaggedFill: Int, val images: List<PinImage>)

/**
 * The pin set, built once for the process rather than per style load: opening a place and editing
 * it is three maps by the never-resize-a-MapView rule, and rasterizing plus blurring the whole
 * catalogue for each lands on the main thread in front of every map's first frame. Cached like the
 * basemap style, for the same reason, and keyed by the two things that can change the bitmaps — the
 * scale they were drawn at, and the theme-derived neutral untagged wears. Main thread only, as
 * every style load that reads it is.
 */
private var cachedPins: PinImageSet? = null

private fun pinImages(ctx: Context, untaggedFill: Int, scale: Float): List<PinImage> {
    cachedPins?.let { if (it.scale == scale && it.untaggedFill == untaggedFill) return it.images }
    val images = (listOf(null) + PlaceCategory.entries).flatMap { category ->
        val fill = category?.let { categoryPinColor(it) } ?: forWhiteGlyph(Color(untaggedFill))
        listOf(
            PinImage(
                placePinImage(category, withGlyph = false),
                glyphed = false,
                bitmap = placePinBitmap(ctx, fill, glyph = null, scale = scale),
            ),
            PinImage(
                placePinImage(category, withGlyph = true),
                glyphed = true,
                bitmap = placePinBitmap(ctx, fill, glyph = category.discIcon, scale = scale),
            ),
        )
    }
    cachedPins = PinImageSet(scale, untaggedFill, images)
    return images
}

/**
 * Registers a pin image per category, with and without its glyph, plus the pair for untagged.
 * [untaggedFill] is the caller's resolved neutral — the map has no composition to read a theme color
 * from, and untagged must stay the *same* neutral the place lists use. [scale] draws the pins larger
 * than [PIN_BASE_DP], for a layer that scales them down by zoom.
 *
 * [glyphedOnly] leaves out the glyphless variant, for a map whose pins are never the small form: the
 * set is built either way, being cached whole, but the sprite atlas isn't asked to carry images no
 * feature on that map can name.
 */
internal fun addPlacePinImages(
    ctx: Context,
    style: Style,
    untaggedFill: Int,
    scale: Float,
    glyphedOnly: Boolean = false,
) {
    for (image in pinImages(ctx, untaggedFill, scale)) {
        if (glyphedOnly && !image.glyphed) continue
        style.addImage(image.id, image.bitmap)
    }
}

private fun placePinBitmap(ctx: Context, fill: Color, glyph: ImageVector?, scale: Float): Bitmap {
    val size = (PIN_BASE_DP * ctx.resources.displayMetrics.density * scale).roundToInt().coerceAtLeast(1)
    val bmp = createBitmap(size, size)
    val canvas = Canvas(bmp)
    val center = size / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(center, center, size * PIN_HALO_RADIUS, paint)
    paint.color = fill.toArgb()
    canvas.drawCircle(center, center, size * PIN_FILL_RADIUS, paint)
    if (glyph != null) {
        val box = size * PIN_GLYPH_BOX
        // Always white: every fill reaching here is dark enough to carry it (see forWhiteGlyph), so
        // the ink is never what tells two categories apart.
        canvas.drawGlyph(glyph, center - box / 2f, center - box / 2f, box, android.graphics.Color.WHITE)
    }
    return withShadow(ctx, bmp, MarkerShadow.SUBJECT, scale)
}

/**
 * Rasterizes an `ImageVector` into [boxPx], in one flat [ink]. The glyph set is shared with the
 * lists ([discIcon]) and must stay so — a pin drawing a different shape than the row for the same
 * category is a worse bug than either shape being wrong.
 */
private fun Canvas.drawGlyph(image: ImageVector, left: Float, top: Float, boxPx: Float, ink: Int) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
    save()
    translate(left, top)
    val unit = boxPx / image.viewportWidth
    scale(unit, unit)
    drawVectorGroup(image.root, paint)
    restore()
}

private fun Canvas.drawVectorGroup(group: VectorGroup, paint: Paint) {
    save()
    translate(group.translationX + group.pivotX, group.translationY + group.pivotY)
    rotate(group.rotation)
    scale(group.scaleX, group.scaleY)
    translate(-group.pivotX, -group.pivotY)
    if (group.clipPathData.isNotEmpty()) clipPath(group.clipPathData.toAndroidPath())
    for (node in group) {
        when (node) {
            is VectorGroup -> drawVectorGroup(node, paint)
            is VectorPath -> drawPath(
                node.pathData.toAndroidPath().apply {
                    fillType = if (node.pathFillType == PathFillType.EvenOdd) {
                        Path.FillType.EVEN_ODD
                    } else {
                        Path.FillType.WINDING
                    }
                },
                paint,
            )
        }
    }
    restore()
}

private fun List<PathNode>.toAndroidPath(): Path = PathParser().addPathNodes(this).toPath().asAndroidPath()
