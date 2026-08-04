package io.github.valeronm.breadcrumb.domain

/** What the Record tab's main area shows. Decided by [recordCardState]. */
enum class RecordCardState {
    /** Auto-record is off — just the recorded period stats. */
    STATS_ONLY,

    /** Armed but the recording service hasn't come up yet. */
    STARTING,

    /** Armed and idle — waiting for a moving activity. */
    WAITING_FOR_MOVEMENT,

    /** A track is auto-paused: it resumes if the same activity returns within the window. */
    PAUSED,

    /** Recording but the track has no drawable geometry yet (fewer than [MIN_MAP_POINTS] fixes). */
    WAITING_FOR_GPS,

    /** Recording but the no-fix guard has switched GPS off; waiting for a resume signal. */
    NO_GPS_SIGNAL,

    /** Recording with enough points to draw — the live map card. */
    LIVE_MAP,
}

/** A line needs two points; below this the map card has nothing to draw. */
const val MIN_MAP_POINTS = 2

/**
 * Pure decision for the Record tab's main area. The live map wins as soon as the open track is
 * drawable — even while the no-fix guard is suspended mid-track (real geometry to show; the guard
 * state is the notification's job). [hasOpenTrack] can briefly disagree with [recording] around
 * track finalization; the waiting states cover that gap rather than flashing an empty map.
 */
fun recordCardState(
    armed: Boolean,
    tracking: Boolean,
    recording: Boolean,
    paused: Boolean,
    gpsSuspended: Boolean,
    points: Int,
    hasOpenTrack: Boolean,
): RecordCardState = when {
    !armed -> RecordCardState.STATS_ONLY
    recording && hasOpenTrack && points >= MIN_MAP_POINTS -> RecordCardState.LIVE_MAP
    recording && gpsSuspended -> RecordCardState.NO_GPS_SIGNAL
    recording -> RecordCardState.WAITING_FOR_GPS
    tracking && paused -> RecordCardState.PAUSED
    tracking -> RecordCardState.WAITING_FOR_MOVEMENT
    else -> RecordCardState.STARTING
}

/**
 * Every word the recorder says, supplied by the host — the seam that keeps this file free of both
 * Android formatters and English. This file decides *which* thing is true of the recorder; the
 * implementation decides how that reads, so word order, agreement and the name an activity goes by
 * all belong to the language rather than to the state machine.
 *
 * Total on purpose, with no defaults: an implementation that compiles has answered for every state,
 * which is the same guarantee the exhaustive `when` over [RecordCardState] gives on this side.
 * Adding a state therefore fails to compile in both places at once.
 */
interface RecorderVocabulary {
    /** Recording, named after what is being done where the detector has an opinion. */
    fun recording(activity: ActivityType?): String

    fun paused(): String

    fun idle(): String

    fun detectionStalled(): String

    fun starting(): String

    fun trackInProgress(): String

    /** No GPS, since [sinceMs] where the surface carries a moving figure to say it with. */
    fun noGps(sinceMs: Long?): String

    /** The same state phrased for a surface that shows no figures. */
    fun noGpsSettled(): String

    /** Positioning, quoting the rejected accuracy radius where there is one to quote. */
    fun positioning(accuracyM: Float?): String

    /** The same state phrased for a surface that shows no figures. */
    fun waitingForFix(): String

    fun resumesWithin(activity: ActivityType?, leftMs: Long): String

    /** The paused activity alone — a live surface with no countdown to hang it on. */
    fun pausedActivity(activity: ActivityType?): String

    /** The same, for a surface with no figures at all. */
    fun continuesIfYouMove(activity: ActivityType?): String

    /** Nothing to record, for [quietMs] where that is worth saying. */
    fun nothingToRecord(quietMs: Long?): String

    fun restartAdvice(): String
}

/**
 * The volatile inputs a *live* surface renders the recorder with — the 1 Hz-ticking clock and the
 * figures that move with it. Deliberately a bundle, not six parameters plus a flag: a surface that
 * must not carry moving figures passes `null` and cannot accidentally read one. The ongoing
 * notification is that surface — it re-posts whenever its text changes, so a countdown or an
 * accuracy radius in it would cost a wakelock and an IPC every second for a whole drive; bundled,
 * the constraint is structural instead of a comment someone has to remember.
 */
class LiveFigures(
    val nowMs: Long,
    val pausedUntilMs: Long? = null,
    val lastReadingAtMs: Long? = null,
    /** The last fix's accuracy radius *only where the gate rejected it for that radius* — the one
     *  case it is worth showing, so the caller resolves the condition instead of passing a flag
     *  that makes the value meaningless half the time. */
    val rejectedAccuracyM: Float? = null,
    val gpsSuspendedSinceMs: Long? = null,
)

/**
 * The recorder in words: [title] names the state, [detail] gives the blocker or progress fact —
 * phrased lowercase, since the Record card joins the two onto one line after " · "; a surface with
 * the detail on its own line (the notification) capitalizes it on render, which is typography, not
 * different wording. Only [RecordCardState.STARTING] has no detail.
 */
data class RecorderText(val title: String, val detail: String?) {
    /** The card's single line: "Recording walking · positioning ±78 m". */
    fun oneLine(): String = if (detail == null) title else "$title · $detail"

    /** The detail on a line of its own (the notification's second field), so it leads with a
     *  capital instead of continuing the title's sentence. Empty when there is no detail. */
    fun detailLine(): String = detail?.replaceFirstChar(Char::uppercase) ?: ""
}

/**
 * The one place the recorder is put into words, for **both** surfaces — the Record tab's state card
 * and the foreground notification. Classifying it twice, in two vocabularies, drifts: one surface
 * ends up reporting a track still waiting for its first fix as one already underway, or a stalled
 * detector as ordinary idleness. [live] decides how much the text moves, not what it says: with it
 * the detail counts down and quotes figures ([RecorderVocabulary.resumesWithin],
 * [RecorderVocabulary.positioning]), without it the same state gets a settled phrase
 * ([RecorderVocabulary.continuesIfYouMove], [RecorderVocabulary.waitingForFix]). Adding a
 * state to [RecordCardState] fails to compile here, which is what keeps the two surfaces in step.
 * [deaf] colors only the idle states: an open track is real recording to report, and a stall is
 * about what happens *next*, not about the track in hand.
 */
fun RecorderVocabulary.recorderText(
    state: RecordCardState,
    activity: ActivityType?,
    pausedActivity: ActivityType?,
    deaf: Boolean,
    live: LiveFigures?,
): RecorderText {
    val words = this
    return when (state) {
        RecordCardState.LIVE_MAP -> RecorderText(words.recording(activity), words.trackInProgress())

        RecordCardState.NO_GPS_SIGNAL -> RecorderText(
            words.recording(activity),
            if (live != null) words.noGps(live.gpsSuspendedSinceMs) else words.noGpsSettled(),
        )

        RecordCardState.WAITING_FOR_GPS -> RecorderText(
            words.recording(activity),
            // While the accuracy gate rejects fixes, the shrinking radius is the progress indicator.
            if (live != null) words.positioning(live.rejectedAccuracyM) else words.waitingForFix(),
        )

        RecordCardState.PAUSED -> {
            val left = live?.pausedUntilMs?.let { it - live.nowMs }
            // Past the deadline nothing resumes into the track — the next activity starts a new one —
            // so it's idle in every way that matters to the user; only the close is pending.
            if (left != null && left <= 0) {
                idleText(live, deaf, words)
            } else {
                val paused = pausedActivity ?: activity
                RecorderText(
                    words.paused(),
                    when {
                        left != null -> words.resumesWithin(paused, left)
                        live != null -> words.pausedActivity(paused)
                        // No countdown to hang the activity on, so it leads the detail instead — the
                        // notification's title is just "Paused", and which activity would resume is
                        // the useful half of that.
                        else -> words.continuesIfYouMove(paused)
                    },
                )
            }
        }

        RecordCardState.WAITING_FOR_MOVEMENT -> idleText(live, deaf, words)

        RecordCardState.STARTING -> RecorderText(words.starting(), null)

        // Auto-record off: the card shows recorded totals instead and the service is stopping, so
        // neither surface renders this. Present so the mapping is total rather than defaulting a live
        // state into whichever branch happened to be last.
        RecordCardState.STATS_ONLY -> RecorderText(words.idle(), words.nothingToRecord(null))
    }
}

/**
 * The reading's age is how long there's been nothing to record; under a minute goes unsaid. A
 * stalled detector is not a benign wait, so it says so — plain quiet would read as ordinary
 * idleness while the service posts a warning about it — and drops the "Idle" lead: idleness is a
 * normal state the user chose, and prefixing the fault with it reads as though nothing were wrong.
 * It carries no duration and no clock time — the only age available is the last reading's, which
 * measures whichever event a re-registration replay happened to surface, and the moment the stall
 * was *noticed* would read as when it began; both invite the user to reason about which trips
 * survived. It does carry the remedy, the same one the alerts notification gives for this condition.
 */
private fun idleText(live: LiveFigures?, deaf: Boolean, words: RecorderVocabulary): RecorderText {
    if (deaf) return RecorderText(words.detectionStalled(), words.restartAdvice())
    // Non-null only with live figures, so it also stands in for "this surface shows figures".
    val quiet = live?.lastReadingAtMs?.let { live.nowMs - it }?.takeIf { it >= 60_000 }
    return RecorderText(words.idle(), words.nothingToRecord(quiet))
}

/**
 * "1m 40s" / "25s" — the pause card's live countdown, rounded up to whole seconds. The minute and
 * second are spelled by the caller, which is where language lives; the shape of the countdown is
 * this file's.
 */
fun formatCountdown(ms: Long, minute: String, second: String): String {
    val totalSec = (ms + 999) / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return if (m > 0) "$m$minute $s$second" else "$s$second"
}
