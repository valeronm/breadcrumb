package com.valeronm.activitytracker.ui

import android.content.Context
import android.os.Build
import android.view.MotionEvent
import android.view.WindowInsets
import org.osmdroid.views.MapView

/**
 * An osmdroid [MapView] that ignores touch gestures starting within the system back-gesture edge
 * strips. The map still draws edge to edge, but a swipe that begins at the very left/right edge is
 * left for the system back gesture instead of panning the map (the same trade-off map apps make).
 */
class EdgeAwareMapView(context: Context) : MapView(context) {

    private var ignoreGesture = false

    private fun edgeInsetPx(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            rootWindowInsets?.getInsets(WindowInsets.Type.systemGestures())?.let {
                val inset = maxOf(it.left, it.right)
                if (inset > 0) return inset
            }
        }
        return (24 * resources.displayMetrics.density).toInt()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val edge = edgeInsetPx()
            ignoreGesture = event.x <= edge || event.x >= width - edge
        }
        // Decline edge-originating gestures so the system back gesture can claim them.
        if (ignoreGesture) return false
        return super.dispatchTouchEvent(event)
    }
}
