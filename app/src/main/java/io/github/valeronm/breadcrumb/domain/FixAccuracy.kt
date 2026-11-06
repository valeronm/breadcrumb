package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint

/** A fix's horizontal accuracy as the rules that weigh fixes read it. */
object FixAccuracy {

    /** A typical phone fix's, standing in where the writer reports none. */
    const val ASSUMED_M = 5.0

    fun metersOf(fix: TrackPoint): Double = fix.accuracy?.toDouble() ?: ASSUMED_M
}
