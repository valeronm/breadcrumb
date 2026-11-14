package io.github.valeronm.breadcrumb.data

import android.content.Context
import io.github.valeronm.breadcrumb.data.export.JsonPullReader
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.PlaceSearch
import java.io.Reader
import java.net.URLEncoder
import kotlin.math.cos

/**
 * Full-text place search over the network — the one lookup no bundled data can answer: a specific
 * hotel, an address, a business by name. Photon (photon.komoot.io): OpenStreetMap data, no API
 * key, built for search-as-you-type. The typed query leaves the device, which is why the whole
 * feature sits behind [Settings.isOnlinePlaceSearch] and its switch on the Privacy page.
 *
 * Every failure — offline, timeout, rate-limited, a malformed body — is one [Outcome.Failed], told
 * apart only in the log.
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

    sealed interface Outcome {
        /** The Privacy switch is off, so nothing was sent. */
        data object Off : Outcome

        data object Failed : Outcome

        data class Found(val hits: List<Hit>) : Outcome
    }

    /** Photon hands back distinct OSM objects (a suburb, its station) whose rows would read identically. */
    fun distinctRows(hits: List<Hit>): List<Hit> =
        hits.distinctBy { PlaceSearch.fold(it.name) to PlaceSearch.fold(it.locality.orEmpty()) }

    private const val LIMIT = 8
    private const val TIMEOUT_MS = 4_000
    private const val METERS_PER_DEGREE = 111_320.0

    /** A degree of longitude shrinks toward nothing near a pole; this floor keeps the box finite. */
    private const val MIN_COS = 0.01

    /**
     * Results for [query], biased toward [near] when given and bounded to [withinM] of it when that
     * is given too; [Outcome.Off] without touching the network when the Privacy switch is off. The
     * gate lives here, with the socket, so no later caller can put a query on the wire around it.
     */
    suspend fun search(context: Context, query: String, near: Coordinate?, withinM: Double? = null): Outcome {
        if (!Settings.isOnlinePlaceSearch(context)) return Outcome.Off
        val hits = fetchJson(url(query, near, withinM), TIMEOUT_MS, "online place search", ::parse)
        return hits?.let(Outcome::Found) ?: Outcome.Failed
    }

    internal fun url(query: String, near: Coordinate?, withinM: Double?): String = buildString {
        append("https://photon.komoot.io/api/?limit=").append(LIMIT)
        append("&q=").append(URLEncoder.encode(query, "UTF-8"))
        near?.let { append("&lat=").append(it.lat).append("&lon=").append(it.lon) }
        // The position only ranks results: a name common elsewhere can fill every slot of [LIMIT]
        // with places far from it.
        if (near != null && withinM != null) {
            val dLat = withinM / METERS_PER_DEGREE
            val dLon = withinM / (METERS_PER_DEGREE * cos(Math.toRadians(near.lat)).coerceAtLeast(MIN_COS))
            append("&bbox=").append(near.lon - dLon).append(',').append(near.lat - dLat)
            append(',').append(near.lon + dLon).append(',').append(near.lat + dLat)
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
        var point: Coordinate? = null
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
        val at = point ?: return null
        val locality = listOfNotNull(props.city, props.country).joinToString(", ").ifEmpty { null }
        return Hit(name = named, locality = locality, lat = at.lat, lon = at.lon)
    }

    private fun parsePoint(json: JsonPullReader): Coordinate? {
        var point: Coordinate? = null
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "coordinates" -> point = json.nextPosition()
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
