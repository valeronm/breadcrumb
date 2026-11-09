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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** One entry's open/close and predictive-back motion. */
internal class EntryMotion {
    val presence = Animatable(0f) // 0 = underneath shown, 1 = fully shown
    val backProgress = Animatable(0f) // predictive back gesture progress, 0..1
    val backOffsetY = Animatable(0f) // finger's vertical travel (px) since the gesture started
    var backEdgeSign by mutableFloatStateOf(1f)
}

/**
 * The app's [BackStack] as Compose state, with each entry's [EntryMotion]. Motion is kept here
 * rather than in the entry's composition because an entry's blur reads the motion of the entries
 * drawn over it.
 */
@Stable
internal class BackStackHost<D : Any> {
    var stack by mutableStateOf(BackStack<D>())
        private set

    // Plain map, not snapshot state: an entry's motion is created by its first reader and every
    // value inside it is already observable.
    private val motions = HashMap<Long, EntryMotion>()

    fun motionOf(id: Long): EntryMotion = motions.getOrPut(id, ::EntryMotion)

    /** Nothing is open or opening. */
    val uncovered: Boolean by derivedStateOf { stack.top == null }

    /** Opens [destination] on top; returns its entry id. */
    fun push(destination: D): Long = stack.nextId.also { stack = stack.push(destination) }

    /** Returns whether anything closed; a page already closing reports false. */
    fun close(id: Long): Boolean = change(stack.close(id))

    /** Returns whether [id] closed or left; a page already closing reports false. */
    fun dismiss(id: Long): Boolean = change(stack.dismiss(id)).also { forgetIfGone(id) }

    fun clear() {
        stack = stack.clear()
    }

    fun update(id: Long, transform: (D) -> D) {
        stack = stack.update(id, transform)
    }

    /** Closes [id] onto [destination] opened beneath it; returns the new entry's id, or null when nothing opened. */
    fun closeOnto(id: Long, destination: D): Long? {
        val opened = stack.nextId
        stack = stack.closeOnto(id, destination)
        return if (stack.nextId == opened) null else opened
    }

    fun closed(id: Long) {
        stack = stack.closed(id)
        forgetIfGone(id)
    }

    private fun change(next: BackStack<D>): Boolean = (next != stack).also { stack = next }

    private fun forgetIfGone(id: Long) {
        if (stack.entries.none { it.id == id }) motions.remove(id)
    }

    /** Blur for the entry at position [index] in the stack: the strongest cast by any entry drawn over it. */
    fun blurDp(index: Int): Float =
        castOver(index) { it.presence.value * (1f - 0.7f * easeOutBack(it.backProgress.value)) * 12f }

    /** [blurDp] for the tabs, which every entry is drawn over. */
    val tabsBlurDp: Float get() = blurDp(-1)

    /** How far the entries cover the tabs, 0 to 1, following their open and close animations. */
    val tabsCovered: Float get() = castOver(-1) { it.presence.value }

    private fun castOver(index: Int, cast: (EntryMotion) -> Float): Float {
        val entries = stack.entries
        var strongest = 0f
        for (i in index + 1..entries.lastIndex) strongest = maxOf(strongest, cast(motionOf(entries[i].id)))
        return strongest
    }
}

/**
 * Every entry of [host]'s stack as [page] in a full-screen frame: its open/close and predictive-back
 * transform, its draw order and the blur cast by what is drawn over it. Every page goes through here
 * rather than assembling the pieces itself: each fails quietly alone, such as the transform without
 * the blur sitting sharp under the page above. A page's data subscriptions go inside [page], so they
 * live exactly as long as its entry.
 */
@Composable
internal fun <D : Any> StackPages(host: BackStackHost<D>, page: @Composable BoxScope.(BackStack.Entry<D>) -> Unit) {
    host.stack.entries.forEachIndexed { index, entry ->
        key(entry.id) {
            StackFrame(host, entry, index) { page(entry) }
        }
    }
}

@Composable
private fun <D : Any> StackFrame(
    host: BackStackHost<D>,
    entry: BackStack.Entry<D>,
    index: Int,
    content: @Composable BoxScope.() -> Unit,
) {
    val motion = host.motionOf(entry.id)
    val focusManager = LocalFocusManager.current
    val leaving = entry.leaving
    val isTop by remember { derivedStateOf { host.stack.top?.id == entry.id } }
    val isLast by remember(host, index) { derivedStateOf { index == host.stack.entries.lastIndex } }
    LaunchedEffect(leaving) {
        if (!leaving) {
            // The page beneath stays composed under this one, so a focused field there would keep
            // its keyboard up over the new page. A field of this page's own can still take focus:
            // its request composes later, so it runs after this.
            focusManager.clearFocus()
            motion.presence.animateTo(1f, tween(300))
        } else {
            motion.presence.animateTo(0f, tween(300))
            host.closed(entry.id)
        }
    }
    PredictiveBackHandler(enabled = isTop) { events ->
        var startTouchY = Float.NaN
        try {
            events.collect { event ->
                if (startTouchY.isNaN()) startTouchY = event.touchY
                motion.backEdgeSign = if (event.swipeEdge == BackEventCompat.EDGE_LEFT) 1f else -1f
                motion.backOffsetY.snapTo(event.touchY - startTouchY)
                motion.backProgress.snapTo(event.progress)
            }
            host.close(entry.id)
        } catch (_: CancellationException) {
            coroutineScope {
                launch { motion.backProgress.animateTo(0f, tween(200)) }
                launch { motion.backOffsetY.animateTo(0f, tween(200)) }
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(index + 1f)
            .pageTransform(motion)
            // Skipped on the last entry: the blur is a graphics layer of its own, and nothing is drawn over it.
            .then(if (isLast) Modifier else Modifier.blurredBy { host.blurDp(index) })
            // An open page that draws nothing yet must still cover what is beneath it, without consuming
            // the touches its own content handles, a map view's among them.
            .then(
                if (leaving) {
                    Modifier
                } else {
                    Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                },
            ),
        content = content,
    )
}

// Ease-out on the gesture progress: like the system's cross-activity animation, most of the
// reveal happens right at gesture start, then the surface tracks the finger gently.
private fun easeOutBack(back: Float): Float = 1f - (1f - back) * (1f - back)

/**
 * The animated values are read inside the graphicsLayer block (like [blurredBy]) so animation
 * frames re-run only this draw-time block, not the composition that applied the modifier.
 */
private fun Modifier.pageTransform(motion: EntryMotion): Modifier =
    graphicsLayer {
        val enter = motion.presence.value
        val back = motion.backProgress.value
        val edgeSign = motion.backEdgeSign
        val backOffsetY = motion.backOffsetY.value
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
 * Blurs this content by [radiusDp] — pass [BackStackHost.blurDp] to blur it by whatever is drawn
 * over it, as the system blurs the background activity during predictive back: strongest when
 * covered, sharpening as the gesture reveals it. [radiusDp] is read at draw time, so a frame re-runs
 * only this block, not the applying composition; no-op below Android 12 (no RenderEffect there).
 */
internal fun Modifier.blurredBy(radiusDp: () -> Float): Modifier {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return this
    return graphicsLayer {
        val radius = radiusDp().dp.toPx()
        renderEffect = if (radius > 0.5f) BlurEffect(radius, radius, TileMode.Clamp) else null
        clip = renderEffect != null
    }
}
