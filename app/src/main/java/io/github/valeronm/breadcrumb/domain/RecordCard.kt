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
 * Pure decision for the Record tab's main area.
 *
 * The live map wins as soon as the open track is drawable — including while the no-fix guard is
 * suspended mid-track (there's real geometry to show; the guard state is the notification's job).
 * [hasOpenTrack] can briefly disagree with [recording] around track finalization; the waiting
 * states cover that gap rather than flashing an empty map.
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
 * How the host renders a wall-clock time and an elapsed span — the seam that keeps this file free of
 * Android formatters. Bundled because they are one concern (the host's time formatting) and travel
 * together everywhere.
 */
class TimeRenderer(
    /** An absolute time as a wall clock, e.g. "14:36". */
    val clock: (Long) -> String,
    /** An elapsed span, e.g. "17m" / "2h 05m". */
    val duration: (Long) -> String,
)

/**
 * The volatile inputs a *live* surface renders the recorder with — the 1 Hz-ticking clock, the
 * figures that move with it, and the host's own clock/duration renderings so this stays free of
 * Android formatters.
 *
 * It is a bundle rather than seven parameters plus a flag on purpose: a surface that must not carry
 * moving figures passes `null` and cannot accidentally read one. The ongoing notification is that
 * surface — it re-posts whenever its text changes, so a countdown or an accuracy radius in it would
 * cost a wakelock and an IPC every second for a whole drive. With the figures bundled, that
 * constraint is structural instead of a comment someone has to remember.
 */
class LiveFigures(
    val nowMs: Long,
    val render: TimeRenderer,
    val pausedUntilMs: Long? = null,
    val lastReadingAtMs: Long? = null,
    /** The last fix's accuracy radius *only where the gate rejected it for that radius* — the one
     *  case it is worth showing, so the caller resolves the condition instead of passing a flag
     *  that makes the value meaningless half the time. */
    val rejectedAccuracyM: Float? = null,
    val gpsSuspendedSinceMs: Long? = null,
)

/**
 * The recorder in words: [title] names the state, [detail] gives the blocker or progress fact.
 *
 * [detail] is phrased lowercase — the Record card's convention, since it joins the two onto one line
 * after " · ". A surface that puts the detail on its own line (the notification) capitalizes it on
 * render; that is typography, not different wording. Only [RecordCardState.STARTING] has no detail.
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
 * and the foreground notification. They used to classify it twice, in two vocabularies, and had
 * already drifted: the notification called a track still waiting for its first fix "in progress",
 * and a stalled detector "idle".
 *
 * [live] decides how much the text moves, not what it says: with it the detail counts down and
 * quotes figures ("walking resumes within 1m 40s", "positioning ±78 m"), without it the same state
 * gets a settled phrase ("continues if you move soon", "waiting for a GPS fix"). Adding a state to
 * [RecordCardState] fails to compile here, which is what keeps the two surfaces in step.
 *
 * [deaf] colors only the idle states: while a track is open there is real recording to report, and
 * a stall is about what happens *next*, not about the track in hand.
 */
fun recorderText(
    state: RecordCardState,
    activity: ActivityType?,
    pausedActivity: ActivityType?,
    deaf: Boolean,
    live: LiveFigures?,
): RecorderText = when (state) {
    RecordCardState.LIVE_MAP -> RecorderText(recordingTitle(activity), "track in progress")

    RecordCardState.NO_GPS_SIGNAL -> RecorderText(
        recordingTitle(activity),
        if (live != null) {
            "no GPS" + (live.gpsSuspendedSinceMs?.let { " since ${live.render.clock(it)}" } ?: "")
        } else {
            "no GPS signal — waiting for one"
        },
    )

    RecordCardState.WAITING_FOR_GPS -> RecorderText(
        recordingTitle(activity),
        if (live != null) {
            // While the accuracy gate rejects fixes, the shrinking radius is the progress indicator.
            val radius = live.rejectedAccuracyM?.let { " ±${it.toInt()} m" } ?: ""
            "positioning$radius"
        } else {
            "waiting for a GPS fix"
        },
    )

    RecordCardState.PAUSED -> {
        val left = live?.pausedUntilMs?.let { it - live.nowMs }
        // Past the deadline nothing resumes into the track — the next activity starts a new one —
        // so it's idle in every way that matters to the user; only the close is pending.
        if (left != null && left <= 0) {
            idleText(live, deaf)
        } else {
            val label = (pausedActivity ?: activity)?.label?.lowercase() ?: "activity"
            RecorderText(
                "Paused",
                when {
                    left != null -> "$label resumes within ${formatCountdown(left)}"
                    live != null -> label
                    // No countdown to hang the activity on, so it leads the detail instead — the
                    // notification's title is just "Paused", and which activity would resume is
                    // the useful half of that.
                    else -> "$label continues if you move soon"
                },
            )
        }
    }

    RecordCardState.WAITING_FOR_MOVEMENT -> idleText(live, deaf)

    RecordCardState.STARTING -> RecorderText("Starting…", null)

    // Auto-record off: the card shows recorded totals instead and the service is stopping, so
    // neither surface renders this. Present so the mapping is total rather than defaulting a live
    // state into whichever branch happened to be last.
    RecordCardState.STATS_ONLY -> RecorderText("Idle", "nothing to record")
}

/**
 * The reading's age is how long there's been nothing to record; under a minute goes unsaid. A
 * stalled detector is not a benign wait, so it says so — reporting it as plain quiet would read as
 * ordinary idleness while the service is posting a warning about it.
 *
 * A stall also drops the "Idle" lead entirely: idleness is a normal state the user chose, and
 * prefixing the fault with it reads as though nothing were wrong. It carries no duration and no
 * clock time — the only age available is the last reading's, which measures whichever event a
 * re-registration replay happened to surface, and the moment the stall was *noticed* would be read
 * as the moment it began. Both would invite the user to reason about which trips survived. What it
 * does carry is the remedy, the same one the alerts notification gives for the same condition.
 */
private fun idleText(live: LiveFigures?, deaf: Boolean): RecorderText {
    if (deaf) return RecorderText("Detection stalled", "restarting the phone usually fixes it")
    // Non-null only with live figures, so it also stands in for "this surface shows figures".
    val quiet = live?.lastReadingAtMs?.let { live.nowMs - it }?.takeIf { it >= 60_000 }
    return RecorderText(
        "Idle",
        if (quiet == null) "nothing to record" else "nothing to record for ${live.render.duration(quiet)}",
    )
}

private fun recordingTitle(activity: ActivityType?): String = "Recording" + labelSuffix(activity)

private fun labelSuffix(activity: ActivityType?): String =
    activity?.let { " ${it.label.lowercase()}" } ?: ""

/** "1m 40s" / "25s" — the pause card's live countdown, rounded up to whole seconds. */
fun formatCountdown(ms: Long): String {
    val totalSec = (ms + 999) / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}
