package io.github.valeronm.breadcrumb.data

import android.content.Context
import io.github.valeronm.breadcrumb.domain.CityAtlas

/**
 * The packed city atlas (`assets/cities.bin`), read once per process and held for the life of it.
 *
 * 4 MB of asset becomes 4 MB of heap, which is why it is read **lazily** rather than at startup:
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

    fun atlas(context: Context): CityAtlas =
        atlas ?: synchronized(this) {
            atlas ?: context.applicationContext.assets.open(ASSET).use { it.readBytes() }
                .let(CityAtlas::parse)
                .also { atlas = it }
        }
}
