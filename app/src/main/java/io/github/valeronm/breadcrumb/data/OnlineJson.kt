package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.BuildConfig
import io.github.valeronm.breadcrumb.data.export.JsonPullReader
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.util.DebugLog
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import java.io.IOException
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "Breadcrumb"

/**
 * One GET of [url] parsed by [parse], as every online lookup makes it: the app named in the
 * User-Agent, both timeouts at [timeoutMs], and null for any failure — offline, a timeout, a body
 * that is not what [parse] expects — logged under [logLabel].
 *
 * A cancelled caller aborts its transfer: disconnect from another thread is the one cancellation
 * hook HttpURLConnection offers. Only cancellation disconnects — a completed request keeps its
 * socket pooled for the next one's, which search-as-you-type lives on.
 */
internal suspend fun <T> fetchJson(url: String, timeoutMs: Int, logLabel: String, parse: (Reader) -> T): T? =
    try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.setRequestProperty("User-Agent", "Breadcrumb/${BuildConfig.VERSION_NAME}")
        val aborter = currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause is CancellationException) connection.disconnect()
        }
        try {
            connection.inputStream.bufferedReader().use(parse)
        } finally {
            aborter.dispose()
        }
    } catch (e: IOException) {
        DebugLog.i(TAG, "$logLabel failed: ${e.message}")
        null
    } catch (e: IllegalStateException) {
        DebugLog.i(TAG, "$logLabel unparseable: ${e.message}")
        null
    }

/**
 * A GeoJSON position — longitude first, as GeoJSON orders it, a third element (altitude) dropped
 * — or null with fewer than two numbers in it. [JsonPullReader.hasNext] is what consumes the
 * separators, so even a fixed-size pair is walked as a sequence.
 */
internal fun JsonPullReader.nextPosition(): Coordinate? {
    beginArray()
    val values = mutableListOf<Double>()
    while (hasNext()) values += nextNumber().toDouble()
    endArray()
    return if (values.size >= 2) Coordinate(values[1], values[0]) else null
}
