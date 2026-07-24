package io.github.valeronm.breadcrumb.ui

import android.os.Build
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Animation state for one stacked overlay layer: open/close presence plus the predictive-back
 * gesture. [rendered] holds the layer's content from open until the close animation finishes —
 * keep the layer composed (with that content) while it's non-null, so the page doesn't blank
 * or flip while receding.
 */
internal class OverlayLayerState<T : Any> {
    val presence = Animatable(0f) // 0 = underneath shown, 1 = layer fully shown
    val backProgress = Animatable(0f) // predictive back gesture progress, 0..1
    val backOffsetY = Animatable(0f) // finger's vertical travel (px) since the gesture started
    var backEdgeSign by mutableFloatStateOf(1f)
    var rendered by mutableStateOf<T?>(null)

    /** Blur radius (dp) for content underneath: full while covered, sharpening with the gesture. */
    val backdropBlurDp: Float
        get() = presence.value * (1f - 0.7f * easeOutBack(backProgress.value)) * 12f
}

// Ease-out on the gesture progress: like the system's cross-activity animation, most of the
// reveal happens right at gesture start, then the surface tracks the finger gently.
private fun easeOutBack(back: Float): Float = 1f - (1f - back) * (1f - back)

/**
 * One stacked overlay layer: animates in while [content] is non-null, out when it goes null, and
 * wires the predictive back gesture ([backEnabled] gates it — a layer yields to one stacked above).
 * [onDismiss] fires when the gesture commits; [onClosed] after the close animation finishes.
 */
@Composable
internal fun <T : Any> rememberOverlayLayer(
    content: T?,
    backEnabled: Boolean = content != null,
    onDismiss: () -> Unit,
    onClosed: () -> Unit = {},
): OverlayLayerState<T> {
    val state = remember { OverlayLayerState<T>() }
    // Snapshot the content while present; held stable through the close animation.
    if (content != null) state.rendered = content
    LaunchedEffect(content != null) {
        if (content != null) {
            state.backProgress.snapTo(0f)
            state.backOffsetY.snapTo(0f)
            state.presence.animateTo(1f, tween(300))
        } else if (state.rendered != null) {
            state.presence.animateTo(0f, tween(300))
            state.rendered = null
            state.backProgress.snapTo(0f)
            state.backOffsetY.snapTo(0f)
            onClosed()
        }
    }
    PredictiveBackHandler(enabled = backEnabled) { events ->
        var startTouchY = Float.NaN
        try {
            events.collect { event ->
                if (startTouchY.isNaN()) startTouchY = event.touchY
                state.backEdgeSign = if (event.swipeEdge == BackEventCompat.EDGE_LEFT) 1f else -1f
                state.backOffsetY.snapTo(event.touchY - startTouchY)
                state.backProgress.snapTo(event.progress)
            }
            onDismiss() // gesture committed -> dismiss
        } catch (_: CancellationException) {
            // Gesture canceled -> spring back to place.
            coroutineScope {
                launch { state.backProgress.animateTo(0f, tween(200)) }
                launch { state.backOffsetY.animateTo(0f, tween(200)) }
            }
        }
    }
    return state
}

/**
 * The overlay open/close + predictive-back transform: slide/scale in, recede toward the edge.
 * The animated values are read inside the graphicsLayer block (like [underlayBlur]) so animation
 * frames re-run only this draw-time block, not the composition that applied the modifier.
 */
internal fun Modifier.overlayTransform(layer: OverlayLayerState<*>): Modifier =
    graphicsLayer {
        val enter = layer.presence.value
        val back = layer.backProgress.value
        val edgeSign = layer.backEdgeSign
        val backOffsetY = layer.backOffsetY.value
        val eased = easeOutBack(back)
        val scale = (0.92f + 0.08f * enter) * (1f - 0.10f * eased)
        scaleX = scale
        scaleY = scale
        translationX = (1f - enter) * size.width * 0.25f + edgeSign * eased * 48.dp.toPx()
        // The receding card follows the finger vertically at a damped rate (another system
        // animation trait), fading in with the gesture so a near-full-screen card stays put.
        translationY = eased * (backOffsetY / 3f).coerceIn(-96.dp.toPx(), 96.dp.toPx())
        // Opaque through the back gesture (M3 predictive-back spec); only open/close fades.
        alpha = enter
        transformOrigin = TransformOrigin(if (edgeSign > 0f) 1f else 0f, 0.5f)
        shape = RoundedCornerShape(eased * 48f)
        clip = back > 0f
    }

/**
 * Blurs this content while any of [layers] sits above it — the system blurs the background
 * activity the same way during predictive back. Strongest when fully covered, sharpening as the
 * gesture reveals it. No-op below Android 12 (no RenderEffect there).
 */
internal fun Modifier.underlayBlur(vararg layers: OverlayLayerState<*>): Modifier {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return this
    return graphicsLayer {
        val radius = layers.maxOf { it.backdropBlurDp }.dp.toPx()
        renderEffect = if (radius > 0.5f) BlurEffect(radius, radius, TileMode.Clamp) else null
        clip = renderEffect != null
    }
}
