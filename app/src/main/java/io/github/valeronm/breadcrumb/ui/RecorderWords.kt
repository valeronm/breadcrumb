package io.github.valeronm.breadcrumb.ui

import android.content.Context
import androidx.annotation.StringRes
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.RecorderVocabulary

/**
 * What each track type reads as, here rather than on [ActivityType] for the same reason a place
 * category's name is (see `CategoryLabels`): a name is language, and the domain package holds no
 * resources. The enum's own `name` is the permanent code written to the DB, the GPX export and the
 * backup file — keeping the two in different layers is what stops one being mistaken for the other.
 */
internal val ActivityType.labelRes: Int
    @StringRes get() = when (this) {
        ActivityType.WALKING -> R.string.activity_walking
        ActivityType.RUNNING -> R.string.activity_running
        ActivityType.CYCLING -> R.string.activity_cycling
        ActivityType.DRIVING -> R.string.activity_driving
        ActivityType.TAXI -> R.string.activity_taxi
        ActivityType.FERRY -> R.string.activity_ferry
        ActivityType.TRANSIT -> R.string.activity_transit
        ActivityType.FLIGHT -> R.string.activity_flight
        ActivityType.STILL -> R.string.activity_still
        ActivityType.UNKNOWN -> R.string.activity_unknown
    }

/**
 * The same ten as the word form a sentence drops into a slot — see the resources, which say why this
 * is authored per language rather than derived from [labelRes].
 */
internal val ActivityType.inlineLabelRes: Int
    @StringRes get() = when (this) {
        ActivityType.WALKING -> R.string.activity_inline_walking
        ActivityType.RUNNING -> R.string.activity_inline_running
        ActivityType.CYCLING -> R.string.activity_inline_cycling
        ActivityType.DRIVING -> R.string.activity_inline_driving
        ActivityType.TAXI -> R.string.activity_inline_taxi
        ActivityType.FERRY -> R.string.activity_inline_ferry
        ActivityType.TRANSIT -> R.string.activity_inline_transit
        ActivityType.FLIGHT -> R.string.activity_inline_flight
        ActivityType.STILL -> R.string.activity_inline_still
        ActivityType.UNKNOWN -> R.string.activity_inline_unknown
    }

/**
 * What a stored `activityType` string reads as. A code no longer in the enum is an older install's
 * and has no translation to find, so it falls back to its own title-cased self.
 */
internal fun activityLabel(context: Context, stored: String): String =
    ActivityType.ofName(stored)?.let { context.getString(it.labelRes) }
        ?: ActivityType.legacyLabelFor(stored)

/**
 * The recorder's words for a given [context] — the Android half of [RecorderVocabulary], shared by
 * the Record card and the foreground notification so the two cannot drift apart.
 *
 * Every string is resolved per call rather than captured, so a language change reaches text already
 * on screen; the recorder's process outlives the UI by weeks and would otherwise keep speaking the
 * language it started in.
 */
internal fun recorderWords(context: Context): RecorderVocabulary =
    object : RecorderVocabulary {
        private val durations = durationSymbols(context)

        // The zone every reader of a recorder bound must agree on, asked for at render rather than
        // captured: the process outlives a timezone change as readily as a language one — and the
        // 12/24 setting behind the format is a system setting the same way, so it is read here too.
        private fun clock(atMs: Long) = readerClockOf(context).time(atMs, timelineZone())

        private fun duration(ms: Long) = formatDurationMs(ms, durations)

        // The mid-sentence form, read rather than derived: lower-casing the label would be this
        // caller deciding a language's orthography, and a noun-capitalizing language would be wrong
        // in every recorder sentence with no way to say so from the translation.
        private fun named(activity: ActivityType?): String? =
            activity?.let { context.getString(it.inlineLabelRes) }

        override fun recording(activity: ActivityType?): String {
            val what = named(activity) ?: return context.getString(R.string.recorder_recording)
            return context.getString(R.string.recorder_recording_activity, what)
        }

        override fun idle() = context.getString(R.string.recorder_idle)

        override fun detectionStalled() = context.getString(R.string.recorder_detection_stalled)

        override fun starting() = context.getString(R.string.recorder_starting)

        override fun trackInProgress() = context.getString(R.string.recorder_track_in_progress)

        override fun noGps(sinceMs: Long?): String = sinceMs
            ?.let { context.getString(R.string.recorder_no_gps_since, clock(it)) }
            ?: context.getString(R.string.recorder_no_gps)

        override fun noGpsSettled() = context.getString(R.string.recorder_no_gps_settled)

        override fun positioning(accuracyM: Float?): String = accuracyM
            ?.let {
                context.getString(
                    R.string.recorder_positioning_radius,
                    // Read per call like every string here, and for the same reason twice over: the
                    // reader can change the units choice while a recording runs, and the system half
                    // of it follows a configuration this process can outlive.
                    measuresOf(context).shortDistance(it.toDouble()),
                )
            }
            ?: context.getString(R.string.recorder_positioning)

        override fun waitingForFix() = context.getString(R.string.recorder_waiting_for_fix)

        override fun nothingToRecord(quietMs: Long?): String = quietMs
            ?.let { context.getString(R.string.recorder_nothing_to_record_for, duration(it)) }
            ?: context.getString(R.string.recorder_nothing_to_record)

        override fun restartAdvice() = context.getString(R.string.recorder_restart_advice)
    }
