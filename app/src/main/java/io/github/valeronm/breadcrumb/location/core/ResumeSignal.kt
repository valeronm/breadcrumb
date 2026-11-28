package io.github.valeronm.breadcrumb.location.core

import io.github.valeronm.breadcrumb.location.ResumeSignals
import io.github.valeronm.breadcrumb.location.WifiWatch

/** [WIFI_LOST] comes from [WifiWatch]'s `onLost`, the others from [ResumeSignals]. */
enum class ResumeSignal { MOTION, PASSIVE_FIX, WIFI_LOST }
