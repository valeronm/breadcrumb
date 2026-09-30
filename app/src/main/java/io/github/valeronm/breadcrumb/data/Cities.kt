package io.github.valeronm.breadcrumb.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.domain.CityAtlas
import java.util.Locale

/**
 * The packed city atlas (`assets/cities.bin`), read once per process and held for the life of it.
 *
 * 4 MB of asset becomes 4 MB of heap, plus the names table of the language asked for, which is why
 * it is read **lazily** rather than at startup:
 * a recorder that runs for weeks without the UI ever opening should not carry a table of place names
 * it has nothing to name. The first caller pays for the read.
 *
 * **Blocking** — call it off the main thread. Every caller so far is already inside a
 * `Dispatchers.Default` flow, and a mapping that names a travel has no business on the main thread
 * regardless.
 */
object Cities {

    private const val ASSET = "cities.bin"

    @Volatile private var atlas: CityAtlas? = null

    /** The atlas naming places in the last language asked for — one held, since a process shows one. */
    @Volatile private var named: Pair<String, CityAtlas>? = null

    /**
     * The atlas with its places named in [language] (an ISO 639 code) wherever that language's
     * `R.raw.city_names` has a name for one. A language with no table of its own, English among
     * them, resolves to the empty default and reads every row's own name.
     */
    // The locale set below reads the table in the language the screens already show rather than
    // switching the app's; a language split Play has not delivered resolves to the empty default.
    @SuppressLint("AppBundleLocaleChanges")
    fun atlas(context: Context, language: String): CityAtlas {
        named?.let { (held, atlas) -> if (held == language) return atlas }
        return synchronized(this) {
            named?.let { (held, atlas) -> if (held == language) return atlas }
            val app = context.applicationContext
            val base = atlas ?: app.assets.open(ASSET).use { it.readBytes() }
                .let(CityAtlas::parse)
                .also { atlas = it }
            val inLanguage = Configuration(app.resources.configuration)
                .apply { setLocale(Locale.forLanguageTag(language)) }
            val names = app.createConfigurationContext(inLanguage).resources
                .openRawResource(R.raw.city_names).use { it.readBytes() }
            base.withNames(CityAtlas.LocalNames.parse(names, base.size))
                .also { named = language to it }
        }
    }
}
