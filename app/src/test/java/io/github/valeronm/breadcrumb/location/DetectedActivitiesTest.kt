package io.github.valeronm.breadcrumb.location

import com.google.android.gms.location.DetectedActivity
import io.github.valeronm.breadcrumb.domain.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectedActivitiesTest {

    @Test
    fun `maps each tracked detected activity to its type`() {
        assertEquals(ActivityType.DRIVING, activityTypeOfDetected(DetectedActivity.IN_VEHICLE))
        assertEquals(ActivityType.CYCLING, activityTypeOfDetected(DetectedActivity.ON_BICYCLE))
        assertEquals(ActivityType.RUNNING, activityTypeOfDetected(DetectedActivity.RUNNING))
        assertEquals(ActivityType.WALKING, activityTypeOfDetected(DetectedActivity.WALKING))
        assertEquals(ActivityType.STILL, activityTypeOfDetected(DetectedActivity.STILL))
    }

    @Test
    fun `the coarse on-foot reading collapses to walking`() {
        assertEquals(ActivityType.WALKING, activityTypeOfDetected(DetectedActivity.ON_FOOT))
    }

    @Test
    fun `an unrecognized constant maps to unknown, not a crash`() {
        assertEquals(ActivityType.UNKNOWN, activityTypeOfDetected(DetectedActivity.TILTING))
        assertEquals(ActivityType.UNKNOWN, activityTypeOfDetected(999))
    }

    @Test
    fun `every type the registration asks for maps to a recording decision`() {
        // The tracked list and the mapping live side by side; a constant added to one but not
        // the other would silently fall to UNKNOWN.
        for (type in TRACKED_DETECTED_ACTIVITIES) {
            assertTrue(activityTypeOfDetected(type) != ActivityType.UNKNOWN)
        }
    }
}
