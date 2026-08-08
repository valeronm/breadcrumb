package io.github.valeronm.breadcrumb.domain

/**
 * A position and how well it was known. [accuracyM] is carried rather than assumed because sources
 * differ by an order of magnitude: a GNSS fix lands meters out, a fused Wi-Fi/cell position tens
 * to hundreds.
 */
data class MeasuredPosition(val coordinate: Coordinate, val accuracyM: Double)
