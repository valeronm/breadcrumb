package io.github.valeronm.breadcrumb.domain

/**
 * The length of `[start, end)` ∩ `[windowStart, windowEnd)`, zero or negative when they are
 * disjoint. The one spelling of "an interval's share of a window": the journey's figures and the
 * per-city credits both measure with it, so an edge decided here — a zero-length touch counts as
 * no overlap — is decided once.
 */
internal fun overlapMs(start: Long, end: Long, windowStart: Long, windowEnd: Long): Long =
    minOf(end, windowEnd) - maxOf(start, windowStart)
