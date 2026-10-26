package io.github.valeronm.breadcrumb.location

/** [WIFI_LOST] comes from [WifiWatch]'s `onLost`, the others from [ResumeSignals]. */
enum class ResumeSignal { MOTION, PASSIVE_FIX, WIFI_LOST }
