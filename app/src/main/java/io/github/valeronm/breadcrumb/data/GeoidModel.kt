package io.github.valeronm.breadcrumb.data

import android.content.Context
import android.location.Location
import androidx.annotation.WorkerThread
import androidx.core.location.LocationCompat
import androidx.core.location.altitude.AltitudeConverterCompat
import io.github.valeronm.breadcrumb.domain.GeoidHeight
import io.github.valeronm.breadcrumb.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

private const val TAG = "Breadcrumb"

/**
 * Geoid heights from androidx's model ([AltitudeConverterCompat]), looked up at the center of a
 * [CELL_DEGREES] cell and kept for the process. The geoid moves by centimeters across a cell, and
 * well under a meter where it is steepest.
 */
class GeoidModel private constructor(private val context: Context) : GeoidHeight {

    /** Cell key to height, NaN where the model had none. */
    private val heights = ConcurrentHashMap<Long, Double>()

    /** The converter's shared instance is not documented as thread-safe. */
    private val converterLock = Any()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** May load the model from disk, which can take seconds the first time. */
    @WorkerThread
    override fun at(latitude: Double, longitude: Double): Double? {
        val key = cellOf(latitude, longitude)
        val height = heights[key] ?: synchronized(converterLock) { heights.getOrPut(key) { lookup(key) } }
        return height.takeUnless { it.isNaN() }
    }

    /**
     * The height if this cell has been looked up, without blocking; a miss is null and starts the
     * lookup in the background, so a later call answers.
     */
    fun cachedAt(latitude: Double, longitude: Double): Double? {
        val key = cellOf(latitude, longitude)
        val height = heights[key]
        if (height == null) scope.launch { at(latitude, longitude) }
        return height?.takeUnless { it.isNaN() }
    }

    private fun lookup(key: Long): Double {
        val probe = Location("geoid").apply {
            // A cell at the antimeridian or a pole has its center past the valid range.
            latitude = (((key shr 32).toInt() + 0.5) * CELL_DEGREES).coerceIn(-90.0, 90.0)
            longitude = (((key and 0xFFFFFFFFL).toInt() + 0.5) * CELL_DEGREES).coerceIn(-180.0, 180.0)
            altitude = 0.0
        }
        return try {
            AltitudeConverterCompat.addMslAltitudeToLocation(context, probe)
            if (LocationCompat.hasMslAltitude(probe)) -LocationCompat.getMslAltitudeMeters(probe) else Double.NaN
        } catch (e: IOException) {
            DebugLog.e(TAG, "Geoid model unreadable", e)
            Double.NaN
        } catch (e: IllegalArgumentException) {
            DebugLog.e(TAG, "Geoid lookup refused", e)
            Double.NaN
        }
    }

    companion object {
        private const val CELL_DEGREES = 0.01

        private fun cellOf(latitude: Double, longitude: Double): Long {
            val row = floor(latitude / CELL_DEGREES).toInt()
            val column = floor(longitude / CELL_DEGREES).toInt()
            return (row.toLong() shl 32) or (column.toLong() and 0xFFFFFFFFL)
        }

        @Volatile private var instance: GeoidModel? = null

        fun of(context: Context): GeoidModel =
            instance ?: synchronized(this) {
                instance ?: GeoidModel(context.applicationContext).also { instance = it }
            }
    }
}
