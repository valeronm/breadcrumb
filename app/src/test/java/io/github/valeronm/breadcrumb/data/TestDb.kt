package io.github.valeronm.breadcrumb.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.data.db.AppDatabase
import io.github.valeronm.breadcrumb.data.db.TrackPoint

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
     * A fix on a northbound line: [index] steps ~110 m apart at 10 s intervals, so a handful of
     * points clears the keep thresholds (30 s / 50 m / 50 m extent) the way a real walk does.
     */
    fun point(
        trackId: Long,
        index: Int,
        ignored: Boolean = false,
        segmentStart: Boolean = false,
        lat: Double = 38.7 + index * 0.001,
    ) = TrackPoint(
        trackId = trackId,
        latitude = lat,
        longitude = -9.3,
        altitude = null,
        accuracy = 5f,
        speed = null,
        bearing = null,
        timestamp = TEST_START + index * 10_000L,
        ignored = ignored,
        segmentStart = segmentStart,
    )
}
