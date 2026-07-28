package io.github.valeronm.breadcrumb.ui

import android.os.Build
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Animation state for one stacked overlay layer: open/close presence plus the predictive-back
 * gesture. [rendered] holds the layer's content from open until the close animation finishes —
 * keep the layer composed (with that content) while it's non-null, so the page doesn't blank
 * or flip while receding.
 *
 * **Where a layer sits in the stack is named once, by the child, as [over], and nothing else may
 * restate it.** All three consequences of stacking derive from that one mention: which gesture
 * back reaches ([onTop]), which page blurs beneath which ([blurDp]), and which draws over which
 * ([depth]). Restated anywhere, the statements can disagree — and a layer that stacks correctly
 * for one of the three and wrongly for another looks right until a finger is on it, with neither
 * the compiler nor a test able to tell. The child is the end that names the relation, because the
 * parent exists first.
 */
internal class OverlayLayerState<T : Any>(over: OverlayLayerState<*>? = null) {
    val presence = Animatable(0f) // 0 = underneath shown, 1 = layer fully shown
    val backProgress = Animatable(0f) // predictive back gesture progress, 0..1
    val backOffsetY = Animatable(0f) // finger's vertical travel (px) since the gesture started
    var backEdgeSign by mutableFloatStateOf(1f)
    var rendered by mutableStateOf<T?>(null)

    /**
     * Asked to be open: true from the request until dismissal, where [rendered] lingers on through
     * the close animation. A parent yields its gesture on this rather than on [rendered], so back
     * is live again the moment a child is dismissed instead of when it has finished leaving.
     */
    var requested by mutableStateOf(false)

    // Layers stacked directly on this one, registered by themselves. A plain list, not snapshot
    // state: every layer is declared unconditionally for the screen's lifetime, so this is built
    // once and never shrinks — what changes, and what is observed, is each child's `requested`.
    private val stackedOn = mutableListOf<OverlayLayerState<*>>()

    /** Layers deep, the root being 0 — the order the pages draw in. */
    val depth: Int = (over?.depth ?: -1) + 1

    init {
        over?.stackedOn?.add(this)
    }

    /** Nothing above is open, so this layer's gesture is the one back should reach. */
    val onTop: Boolean get() = stackedOn.none { it.requested }

    /** Anything stacks on this at all. A layer nothing covers can never be blurred. */
    val stacked: Boolean get() = stackedOn.isNotEmpty()

    /** Blur for this layer's own page, cast by whatever is stacked on it. */
    val blurDp: Float get() = stackedOn.maxOfOrNull { it.castBlurDp } ?: 0f

    // Blur radius (dp) this layer casts downward: full while covering, sharpening with the
    // gesture. Read only through a parent's [blurDp] — a layer never applies its own.
    private val castBlurDp: Float
        get() = presence.value * (1f - 0.7f * easeOutBack(backProgress.value)) * 12f
}

// Ease-out on the gesture progress: like the system's cross-activity animation, most of the
// reveal happens right at gesture start, then the surface tracks the finger gently.
private fun easeOutBack(back: Float): Float = 1f - (1f - back) * (1f - back)

/**
 * One stacked overlay layer: animates in while [content] is non-null, out when it goes null, and
 * wires the predictive back gesture, which it holds only while nothing stacked on it is open.
 * [over] is the layer this one opens on top of — see [OverlayLayerState]. [onDismiss] fires when
 * the gesture commits; [onClosed] after the close animation finishes.
 */
@Composable
internal fun <T : Any> rememberOverlayLayer(
    content: T?,
    over: OverlayLayerState<*>? = null,
    onDismiss: () -> Unit,
    onClosed: () -> Unit = {},
): OverlayLayerState<T> {
    val state = remember { OverlayLayerState<T>(over) }
    // Snapshot the content while present; held stable through the close animation.
    if (content != null) state.rendered = content
    LaunchedEffect(content != null) {
        // Set from the effect, not from composition: a parent reads it to decide whether to yield
        // its gesture, and it is composed first, so writing it inline would be a write to state
        // already read this pass.
        state.requested = content != null
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
    PredictiveBackHandler(enabled = content != null && state.onTop) { events ->
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
 * The animated values are read inside the graphicsLayer block (like [blurredBy]) so animation
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
 * Blurs this content by [radiusDp] — pass a layer's [OverlayLayerState.blurDp] to blur it by
 * whatever is stacked on it, the way the system blurs the background activity during predictive
 * back: strongest when fully covered, sharpening as the gesture reveals it.
 *
 * [radiusDp] is read at draw time, so an animation frame re-runs only this block and not the
 * composition that applied the modifier. No-op below Android 12 (no RenderEffect there).
 */
internal fun Modifier.blurredBy(radiusDp: () -> Float): Modifier {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return this
    return graphicsLayer {
        val radius = radiusDp().dp.toPx()
        renderEffect = if (radius > 0.5f) BlurEffect(radius, radius, TileMode.Clamp) else null
        clip = renderEffect != null
    }
}

/**
 * One stacked layer's full-screen frame: composed only while the layer has content, carrying its
 * own open/close and predictive-back transform, its draw order, and the blur cast on it by
 * whatever is stacked on it. All three come off the layer, so where a frame is emitted among its
 * siblings carries no meaning and cannot contradict the stack.
 *
 * **[content] receives [OverlayLayerState.rendered], and that is the point.** A page must keep
 * drawing itself all the way through the close animation, so it has to render from the layer's
 * held content rather than from the state that opened it — that state goes null the moment back
 * commits, and a page reading it blanks mid-slide instead of fading out. Handing the held value
 * down puts the right one in scope and leaves the wrong one out of reach.
 *
 * The gate belongs here for the same reason it holds the content: a layer's data subscriptions go
 * *inside* this block, so they live exactly as long as the layer is up.
 *
 * Every overlay goes through here instead of assembling this itself, because each piece fails
 * quietly on its own: a page given the transform but no blur sits sharp under the layer above it,
 * one rendered from the live key vanishes a frame into its own exit, and a subscription hoisted
 * out of the gate stays live for the life of the app.
 */
@Composable
internal fun <T : Any> OverlayFrame(
    layer: OverlayLayerState<T>,
    content: @Composable BoxScope.(T) -> Unit,
) {
    val rendered = layer.rendered ?: return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(layer.depth.toFloat())
            .overlayTransform(layer)
            // Skipped where nothing can cover this layer: the blur is a graphics layer of its own,
            // and a leaf would carry one around a full-screen page to apply an effect of zero.
            .then(if (layer.stacked) Modifier.blurredBy { layer.blurDp } else Modifier),
    ) {
        content(rendered)
    }
}
