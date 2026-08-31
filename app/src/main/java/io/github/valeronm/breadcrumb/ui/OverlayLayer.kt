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
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Animation state for one stacked overlay layer: open/close presence plus the predictive-back
 * gesture. [rendered] holds the content from open until the close animation ends — keep the layer
 * composed with it while non-null, so the content doesn't blank or flip while receding. Where a layer
 * sits is named once, by the child, as [over], and nothing else may restate it: everything stacking
 * implies derives from that mention — which gesture back reaches ([onTop]), which content blurs beneath
 * which ([blurDp]), which draws over which ([depth]), what a landing on a layer closes
 * ([dismissAbove]). Restated, they could disagree — right for one and wrong for another looks right
 * until a finger is on it, and neither compiler nor test can tell. The child names the relation
 * because the parent exists first.
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

    // How this layer is dismissed — the state write its host clears its content with, registered by
    // [rememberOverlayLayer] on every composition so it closes over the host's current state. The
    // default is what a floor layer built with a plain `remember` runs, having no content to clear.
    var dismiss: () -> Unit = {}

    // Layers stacked directly on this one, registered by themselves. A plain list, not snapshot
    // state: every layer is declared unconditionally for the screen's lifetime, so this is built
    // once and never shrinks — what changes, and what is observed, is each child's `requested`.
    private val stackedOn = mutableListOf<OverlayLayerState<*>>()

    /** Layers deep, the root being 0 — the order the layers draw in. */
    val depth: Int = (over?.depth ?: -1) + 1

    init {
        over?.stackedOn?.add(this)
    }

    /** Nothing above is open, so this layer's gesture is the one back should reach. */
    val onTop: Boolean get() = stackedOn.none { it.requested }

    /**
     * Dismiss every layer stacked on this one, however deep — a landing on this layer's own content.
     * Derived from the stack rather than spelled by the caller, so a layer added later cannot be
     * left open under the landing. Top-down, the way back would take them.
     */
    fun dismissAbove() {
        stackedOn.forEach {
            it.dismissAbove()
            it.dismiss()
        }
    }

    /** Anything stacks on this at all. A layer nothing covers can never be blurred. */
    val stacked: Boolean get() = stackedOn.isNotEmpty()

    /** Blur for this layer's own content, cast by whatever is stacked on it. */
    val blurDp: Float get() = stackedOn.maxOfOrNull { it.castBlurDp } ?: 0f

    // Blur radius (dp) this layer casts downward: its own while it has content up — full while
    // covering, sharpening with the gesture — and whatever is cast onto it while it does not.
    // A layer with nothing rendered is not on screen, so a blur cast onto it must fall through
    // to the first layer that is: a place opened from a tab stacks on a journey that isn't there,
    // and it is the tabs that show beneath it. Read only through a parent's [blurDp] — a layer
    // never applies its own.
    private val castBlurDp: Float
        get() = if (rendered != null) {
            presence.value * (1f - 0.7f * easeOutBack(backProgress.value)) * 12f
        } else {
            blurDp
        }
}

// Ease-out on the gesture progress: like the system's cross-activity animation, most of the
// reveal happens right at gesture start, then the surface tracks the finger gently.
private fun easeOutBack(back: Float): Float = 1f - (1f - back) * (1f - back)

/**
 * One stacked overlay layer: animates in while [content] is non-null, out when it goes null; holds
 * the predictive back gesture only while nothing stacked on it is open. [over] is the layer this
 * one opens on top of — see [OverlayLayerState]. [dismiss] is how the layer closes — on gesture
 * commit, and from anything that asks the state ([OverlayLayerState.dismiss]); [onClosed]
 * after the close animation finishes.
 */
@Composable
internal fun <T : Any> rememberOverlayLayer(
    content: T?,
    over: OverlayLayerState<*>? = null,
    dismiss: () -> Unit,
    onClosed: () -> Unit = {},
): OverlayLayerState<T> {
    val state = remember { OverlayLayerState<T>(over) }
    // Registered once the composition has applied, so a skipped recomposition cannot leave a stale
    // closure on the state.
    SideEffect { state.dismiss = dismiss }
    // Snapshot the content while present; held stable through the close animation.
    if (content != null) state.rendered = content
    val focusManager = LocalFocusManager.current
    LaunchedEffect(content != null) {
        // Set from the effect, not from composition: a parent reads it to decide whether to yield
        // its gesture, and it is composed first, so writing it inline would be a write to state
        // already read this pass.
        state.requested = content != null
        if (content != null) {
            // The layer beneath stays composed under this one, so a focused search field there
            // would keep its keyboard up over the new content — the focus goes with the covering.
            // A field of this layer's own can still take focus: its request composes later, so
            // it runs after this.
            focusManager.clearFocus()
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
            state.dismiss() // gesture committed -> dismiss
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
 * whatever stacks on it, as the system blurs the background activity during predictive back:
 * strongest when covered, sharpening as the gesture reveals it. [radiusDp] is read at draw time,
 * so a frame re-runs only this block, not the applying composition; no-op below Android 12 (no
 * RenderEffect there).
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
 * open/close + predictive-back transform, its draw order, and the blur cast by whatever stacks on
 * it — all off the layer, so a frame's emission order among siblings cannot contradict the stack.
 * [content] receives [OverlayLayerState.rendered], deliberately: the content must keep drawing through
 * the close animation, and the state that opened it goes null the moment back commits — content
 * reading it blanks mid-slide instead of fading out; handing the held value down puts the right
 * one in scope and the wrong one out of reach. A layer's data subscriptions go *inside* this
 * block, so they live exactly as long as the layer is up. Every overlay goes through here rather
 * than assembling the pieces itself: each fails quietly alone — e.g. the transform without the
 * blur sits sharp under the layer above.
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
            // and a leaf would carry one around a full-screen layer to apply an effect of zero.
            .then(if (layer.stacked) Modifier.blurredBy { layer.blurDp } else Modifier),
    ) {
        content(rendered)
    }
}
