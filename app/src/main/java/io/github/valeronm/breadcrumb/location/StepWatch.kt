package io.github.valeronm.breadcrumb.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import io.github.valeronm.breadcrumb.util.DebugLog
import io.github.valeronm.breadcrumb.util.activityRecognitionGranted

/**
 * Listens to the hardware step counter while GPS is on.
 *
 * **[history] is written on the main thread and read on the recorder's.** It holds the counter's
 * running total since boot at each event's own time, and is empty until the counter has reported
 * since [register].
 */
class StepWatch(private val context: Context) {

    @Volatile
    var history: StepHistory = StepHistory.EMPTY
        private set

    private val sensors by lazy { context.getSystemService(SensorManager::class.java) }
    private val counter by lazy {
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        // A phone with no counter and a counter that never reports store the same nulls.
        if (sensor == null) DebugLog.i(TAG, "step counter: this phone has none")
        sensor
    }
    private var listener: SensorEventListener? = null

    fun register() {
        if (listener != null) return
        if (!context.activityRecognitionGranted()) return
        val sm = sensors ?: return
        val sensor = counter ?: return
        val registered = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // A sensor event's timestamp runs on the elapsed-realtime clock a fix is stamped on.
                history = history.with(event.timestamp / 1_000_000L, event.values[0].toLong())
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (sm.registerListener(registered, sensor, REPORT_PERIOD_US)) {
            listener = registered
        } else {
            DebugLog.w(TAG, "step counter: the sensor refused the request")
        }
    }

    fun unregister() {
        val registered = listener ?: return
        sensors?.unregisterListener(registered)
        listener = null
        // A total from before a gap would be stored on the next stretch's first fixes as current.
        history = StepHistory.EMPTY
    }

    private companion object {
        const val TAG = "Breadcrumb"

        // Fixes arrive a second apart at the fastest, so a finer step history places none better.
        const val REPORT_PERIOD_US = 1_000_000
    }
}
