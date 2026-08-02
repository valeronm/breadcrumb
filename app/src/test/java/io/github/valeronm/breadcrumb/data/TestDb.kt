package io.github.valeronm.breadcrumb.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.data.db.AppDatabase
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import org.junit.Assert.assertEquals

/** Fixed epoch millis for test tracks — a real timestamp, so durations read sensibly. */
const val TEST_START = 1_700_000_000_000L

/**
 * An in-memory database and a repository on top of it, for the data-layer tests that need real
 * Room (Robolectric). Closed by the test's `@After`.
 */
class TestDb {
    private val context: Context = ApplicationProvider.getApplicationContext()
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    val repository = TrackRepository(context, db)
    val dao = db.trackDao()

    fun close() = db.close()

    /**
     * What the stored aggregates should be, recomputed from the track's points on the spot — the
     * one invariant every path that touches points is measured against (see [TrackRepositoryTest]).
     */
    suspend fun assertStatsMatchPoints(trackId: Long) {
        val track = dao.track(trackId)!!
        val expected = TrackStats.of(dao.allPointsFor(trackId))
        assertEquals(expected.pointCount, track.pointCount)
        assertEquals(expected.ignoredCount, track.ignoredCount)
        assertEquals(expected.distanceMeters, track.distanceMeters, 0.5)
        assertEquals(expected.startLat, track.startLat)
        assertEquals(expected.startLon, track.startLon)
        assertEquals(expected.endLat, track.endLat)
        assertEquals(expected.endLon, track.endLon)
    }

    /**
     * A fix on a northbound line: [index] steps ~110 m apart at 10 s intervals, so a handful of
     * points clears the keep thresholds (30 s / 50 m / 50 m extent) the way a real walk does.
     */
    fun point(
        trackId: Long,
        index: Int,
        ignored: Boolean = false,
        segmentStart: Boolean = false,
        lat: Double = 1.0 + index * 0.001,
    ) = TrackPoint(
        trackId = trackId,
        latitude = lat,
        longitude = -2.0,
        altitude = null,
        accuracy = 5f,
        speed = null,
        bearing = null,
        timestamp = TEST_START + index * 10_000L,
        ignored = ignored,
        segmentStart = segmentStart,
    )
}
