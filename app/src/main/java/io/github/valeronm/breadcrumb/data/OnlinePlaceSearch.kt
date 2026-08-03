package io.github.valeronm.breadcrumb.data

import android.content.Context
import io.github.valeronm.breadcrumb.data.export.JsonPullReader
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.util.DebugLog
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import java.io.IOException
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException

/**
 * Full-text place search over the network — the one lookup no bundled data can answer: a specific
 * hotel, an address, a business by name. Photon (photon.komoot.io): OpenStreetMap data, no API
 * key, built for search-as-you-type. The typed query leaves the device, which is why the whole
 * feature sits behind [Settings.isOnlinePlaceSearch] and its switch on the Privacy page.
 *
 * Every failure — offline, timeout, rate-limited, a malformed body — reads as "no results": the
 * local sections of the search stand on their own, and a form field is no place for a network
 * error. The failure is logged, not surfaced.
 */
object OnlinePlaceSearch {

    /** One result — what the form needs to place a pin and say what it picked. */
    class Hit(
        val name: String,
        /** "City, Country" as far as the result carries them, null when it carries neither. */
        val locality: String?,
        val lat: Double,
        val lon: Double,
    )

    private const val TAG = "Breadcrumb"
    private const val LIMIT = 8
    private const val TIMEOUT_MS = 4_000

    /**
     * Results for [query], biased toward [near] when given; empty on any failure — and empty
     * without touching the network when the Privacy switch is off. The gate lives here, with the
     * socket, so no later caller can put a query on the wire around it.
     */
    suspend fun search(context: Context, query: String, near: StayDeriver.Endpoint?): List<Hit> {
        if (!Settings.isOnlinePlaceSearch(context)) return emptyList()
        val url = buildString {
            append("https://photon.komoot.io/api/?limit=").append(LIMIT)
            append("&q=").append(URLEncoder.encode(query, "UTF-8"))
            near?.let { append("&lat=").append(it.lat).append("&lon=").append(it.lon) }
        }
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            // A superseded query aborts its transfer: disconnect from another thread is the one
            // cancellation hook HttpURLConnection offers. Only cancellation disconnects — a
            // completed request keeps its socket pooled for the next keystroke's, which is what
            // search-as-you-type lives on (there is deliberately no disconnect on success).
            val aborter = currentCoroutineContext().job.invokeOnCompletion { cause ->
                if (cause is CancellationException) connection.disconnect()
            }
            try {
                connection.inputStream.bufferedReader().use(::parse)
            } finally {
                aborter.dispose()
            }
        } catch (e: IOException) {
            DebugLog.i(TAG, "online place search failed: ${e.message}")
            emptyList()
        } catch (e: IllegalStateException) {
            // The parser's own checks — a body that isn't the GeoJSON Photon promises.
            DebugLog.i(TAG, "online place search unparseable: ${e.message}")
            emptyList()
        }
    }

    /**
     * Parses a Photon response (GeoJSON FeatureCollection) — separate from the fetch so the shape
     * is pinned by a host test with no network. Features without a name are dropped: Photon also
     * returns bare addresses, and a nameless pin is what the map's long press already does better.
     */
    internal fun parse(reader: Reader): List<Hit> {
        val out = mutableListOf<Hit>()
        val json = JsonPullReader(reader)
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "features" -> {
                    json.beginArray()
                    while (json.hasNext()) parseFeature(json)?.let(out::add)
                    json.endArray()
                }
                else -> json.skipValue()
            }
        }
        json.endObject()
        json.expectEnd()
        return out
    }

    private class Properties(val name: String?, val city: String?, val country: String?)

    private fun parseFeature(json: JsonPullReader): Hit? {
        var props: Properties? = null
        var point: Pair<Double, Double>? = null
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "geometry" -> point = parsePoint(json)
                "properties" -> props = parseProperties(json)
                else -> json.skipValue()
            }
        }
        json.endObject()
        val named = props?.name ?: return null
        val (lat, lon) = point ?: return null
        val locality = listOfNotNull(props.city, props.country).joinToString(", ").ifEmpty { null }
        return Hit(name = named, locality = locality, lat = lat, lon = lon)
    }

    /** The geometry's (lat, lon) — GeoJSON stores longitude first. */
    private fun parsePoint(json: JsonPullReader): Pair<Double, Double>? {
        var point: Pair<Double, Double>? = null
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "coordinates" -> {
                    // hasNext() is what consumes the separators, so even a fixed-size pair is
                    // walked as a sequence; a third element (altitude) is read and dropped.
                    json.beginArray()
                    val values = mutableListOf<Double>()
                    while (json.hasNext()) values += json.nextNumber().toDouble()
                    json.endArray()
                    if (values.size >= 2) point = values[1] to values[0]
                }
                else -> json.skipValue()
            }
        }
        json.endObject()
        return point
    }

    private fun parseProperties(json: JsonPullReader): Properties {
        var name: String? = null
        var city: String? = null
        var country: String? = null
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "name" -> name = json.nextStringOrNull()
                "city" -> city = json.nextStringOrNull()
                "country" -> country = json.nextStringOrNull()
                else -> json.skipValue()
            }
        }
        json.endObject()
        return Properties(name, city, country)
    }
}
