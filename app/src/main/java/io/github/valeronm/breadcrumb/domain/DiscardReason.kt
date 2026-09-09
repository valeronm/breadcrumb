package io.github.valeronm.breadcrumb.domain

/**
 * Why a track sits in Recently deleted. [code] is the stable DB string, and every path that
 * discards a track writes one.
 *
 * [byUser] separates a decision the user made from one the app made for them — stated per entry, so
 * a code added later cannot fall to either side unnoticed.
 */
enum class DiscardReason(val code: String, val byUser: Boolean) {
    DELETED("deleted", byUser = true),

    /** Rejected by the keep thresholds ([KeepRule]) — the one discard a returning stretch can undo
     *  by making the track long enough. */
    FILTERED("filtered", byUser = false),

    /** Superseded by the track a merge wrote over the pair. */
    MERGED("merged", byUser = false),
    ;

    companion object {
        fun fromCode(code: String?): DiscardReason? = entries.firstOrNull { it.code == code }
    }
}
