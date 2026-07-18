package io.github.valeronm.breadcrumb.domain

/**
 * Decides when to tell the user that automatic recording has stopped responding, and when to take
 * that back. Fed by `LocationRecordingService.applyActivity`; the service owns the notification.
 *
 * The registration can stop delivering live transitions with no error anywhere, and re-registering
 * is not a reliable cure (see [io.github.valeronm.breadcrumb.location.ActivityRecognitionManager]),
 * so the app cannot fix it — but it can stop failing silently. The user's remedy is a reboot, which
 * is only worth suggesting once it is clear the recorder is not recovering on its own.
 *
 * Hence [detectionsBeforeWarning]: one detection is not enough. [StaleReadingOracle] can fire on a
 * registration that then recovers by itself, and the service restarts on the first detection
 * anyway, so warning immediately would cry wolf on an episode the user never noticed. A second
 * detection means the restart did not take.
 */
class DeafnessWarning(
    /** A delivery runs seconds behind its event; anything older arrived by replay. */
    private val liveMaxAgeMs: Long,
    /** A reading this close behind a re-registration is its replay, not a live delivery. */
    private val replayWindowMs: Long,
    private val detectionsBeforeWarning: Int = 2,
) {

    private var detections = 0

    /** True once the user has been warned and not yet had it withdrawn. */
    var warned = false
        private set

    /** A [StaleReadingOracle] detection. Returns true when the warning should be raised now. */
    fun onDeafDetected(): Boolean {
        detections++
        if (warned || detections < detectionsBeforeWarning) return false
        warned = true
        return true
    }

    /**
     * A reading applied [readingAgeMs] after the event that produced it, [sinceRegistrationMs]
     * after the last re-registration. Returns true when a standing warning should be withdrawn.
     *
     * Only a prompt reading that did *not* follow a re-registration proves delivery is live: a
     * re-registration replays the current activity, and a dead registration answers that replay
     * just as a healthy one does — which is exactly why deafness is invisible in the first place.
     */
    fun onReading(readingAgeMs: Long, sinceRegistrationMs: Long): Boolean {
        if (readingAgeMs > liveMaxAgeMs || sinceRegistrationMs < replayWindowMs) return false
        detections = 0
        if (!warned) return false
        warned = false
        return true
    }

    /** Arming or disarming starts the assessment over. */
    fun reset() {
        detections = 0
        warned = false
    }

}
