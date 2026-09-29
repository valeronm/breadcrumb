package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place

/**
 * Who last wrote a place row. [code] is the stable string stored on the row, sharing its spelling
 * with the [TrackOrigin] of the same writer. Null means unknown: a code this build doesn't know,
 * which survives a backup round trip rather than being rewritten.
 */
enum class PlaceOrigin(val code: String) {
    /** Made or edited in the app — any save of the place editor turns an imported row into this. */
    MANUAL(TrackOrigin.MANUAL.code),

    /** Created by the Google Timeline import and not edited since. */
    GOOGLE_TIMELINE(TrackOrigin.GOOGLE_TIMELINE.code),
    ;

    companion object {
        fun fromCode(code: String?): PlaceOrigin? = entries.firstOrNull { it.code == code }
    }
}

val Place.placeOrigin: PlaceOrigin? get() = PlaceOrigin.fromCode(source)
