package io.github.valeronm.breadcrumb.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.domain.atlas.CityAtlas
import io.github.valeronm.breadcrumb.domain.atlas.CountryBorders
import java.util.Locale

/**
 * The packed city atlas (`assets/cities.bin`) with the country outlines it names by
 * (`assets/borders.bin`), read once per process and held for the life of it.
 *
 * The atlas's bytes, the outlines decoded and the names table of the language asked for are several
 * megabytes of heap, which is why they are read **lazily** rather than at startup:
 * a recorder that runs for weeks without the UI ever opening should not carry a table of place names
 * it has nothing to name. The first caller pays for the read.
 *
 * **Blocking** — call it off the main thread. Every caller so far is already inside a
 * `Dispatchers.Default` flow, and a mapping that names a travel has no business on the main thread
 * regardless.
 */
object Cities {

    private const val ASSET = "cities.bin"
    private const val BORDERS = "borders.bin"

    @Volatile private var atlas: CityAtlas? = null

    /** The atlas naming places in the last language asked for — one held, since a process shows one. */
    @Volatile private var named: Pair<String, CityAtlas>? = null

    /**
     * The atlas with its places named in [language] (an ISO 639 code) wherever that language's
     * `R.raw.city_names` has a name for one, and its regions by `R.raw.region_names`. A language
     * with no tables of its own, English among them, resolves to the defaults: every row's own name
     * and GeoNames' region names.
     */
    // The locale set below reads the table in the language the screens already show rather than
    // switching the app's; a language split Play has not delivered resolves to the empty default.
    @SuppressLint("AppBundleLocaleChanges")
    fun atlas(context: Context, language: String): CityAtlas {
        named?.let { (held, atlas) -> if (held == language) return atlas }
        return synchronized(this) {
            named?.let { (held, atlas) -> if (held == language) return atlas }
            val app = context.applicationContext
            val base = base(app)
            val inLanguage = Configuration(app.resources.configuration)
                .apply { setLocale(Locale.forLanguageTag(language)) }
            val resources = app.createConfigurationContext(inLanguage).resources
            fun raw(id: Int) = resources.openRawResource(id).use { it.readBytes() }
            base.withNames(
                CityAtlas.LocalNames.parse(raw(R.raw.city_names), base.size),
                CityAtlas.RegionNames.parse(raw(R.raw.region_names), base.size),
            )
                .also { named = language to it }
        }
    }

    /** The outlines the atlas names by, read without loading any language's names table. */
    fun borders(context: Context): CountryBorders = synchronized(this) { base(context.applicationContext) }.borders

    /** Called holding the lock. */
    private fun base(app: Context): CityAtlas {
        fun asset(name: String) = app.assets.open(name).use { it.readBytes() }
        return atlas ?: CityAtlas.parse(asset(ASSET), asset(BORDERS)).also { atlas = it }
    }
}
