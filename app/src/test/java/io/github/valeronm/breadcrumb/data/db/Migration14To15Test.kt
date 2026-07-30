package io.github.valeronm.breadcrumb.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v15 adds `tracks.source` and fills existing rows in the migration itself, so the column is never
 * half-written. The fill is `TrackOrigin.inferFrom` in SQL: any point with an accuracy radius means
 * the recorder wrote the track, points with none mean a GPX file did, and no points at all means
 * no answer. See [MigrationDb] on the hand-written v14 schema.
 */
@RunWith(RobolectricTestRunner::class)
class Migration14To15Test {

    private val fixture = MigrationDb(14, ::createV14Schema)
    private val db: SupportSQLiteDatabase get() = fixture.db

    @After fun tearDown() = fixture.close()

    private fun insertTrack(id: Long) =
        db.execSQL("INSERT INTO tracks (id, activityType, startedAt) VALUES ($id, 'WALKING', 1000)")

    private fun insertPoint(trackId: Long, t: Long, accuracy: String) = db.execSQL(
        "INSERT INTO track_points (trackId, latitude, longitude, accuracy, timestamp) " +
            "VALUES ($trackId, 1.0, -2.0, $accuracy, $t)",
    )

    private fun sourceOf(trackId: Long): String? =
        db.query("SELECT source FROM tracks WHERE id = $trackId").use { c ->
            assertTrue(c.moveToFirst())
            if (c.isNull(0)) null else c.getString(0)
        }

    @Test fun `a track whose fixes carry accuracy is filled as recorded`() {
        insertTrack(1)
        insertPoint(1, 1000, "5.0")
        insertPoint(1, 2000, "7.5")

        AppDatabase.MIGRATION_14_15.migrate(db)

        assertEquals("recorded", sourceOf(1))
    }

    @Test fun `a track without a single accuracy is filled as imported`() {
        insertTrack(2)
        insertPoint(2, 1000, "NULL")
        insertPoint(2, 2000, "NULL")

        AppDatabase.MIGRATION_14_15.migrate(db)

        assertEquals("imported", sourceOf(2))
    }

    @Test fun `one accuracy among nulls is enough to name the recorder`() {
        // The platform may withhold a radius on a fix, so the fill asks "any", never "all" — a
        // stricter rule would file a recording as an import on one missing value.
        insertTrack(3)
        insertPoint(3, 1000, "NULL")
        insertPoint(3, 2000, "4.0")
        insertPoint(3, 3000, "NULL")

        AppDatabase.MIGRATION_14_15.migrate(db)

        assertEquals("recorded", sourceOf(3))
    }

    @Test fun `a track with no points names no writer`() {
        insertTrack(4)

        AppDatabase.MIGRATION_14_15.migrate(db)

        assertNull(sourceOf(4))
    }

    @Test fun `a discarded track is filled like any other`() {
        // Recently deleted is restorable, so its rows must come out of the migration answerable.
        insertTrack(5)
        db.execSQL("UPDATE tracks SET discardedAt = 9999, discardReason = 'deleted' WHERE id = 5")
        insertPoint(5, 1000, "6.0")

        AppDatabase.MIGRATION_14_15.migrate(db)

        assertEquals("recorded", sourceOf(5))
    }

    @Test fun `each track is judged on its own points`() {
        insertTrack(6)
        insertPoint(6, 1000, "3.0")
        insertTrack(7)
        insertPoint(7, 1000, "NULL")

        AppDatabase.MIGRATION_14_15.migrate(db)

        assertEquals("recorded", sourceOf(6))
        assertEquals("imported", sourceOf(7))
    }
}

private fun createV14Schema(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE tracks (
          id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, activityType TEXT NOT NULL,
          startedAt INTEGER NOT NULL, endedAt INTEGER, distanceMeters REAL NOT NULL DEFAULT 0,
          pointCount INTEGER NOT NULL DEFAULT 0, ignoredCount INTEGER NOT NULL DEFAULT 0,
          startLat REAL, startLon REAL, endLat REAL, endLon REAL,
          discardedAt INTEGER, discardReason TEXT, needsReview INTEGER NOT NULL DEFAULT 0)
        """,
    )
    db.execSQL(
        """
        CREATE TABLE track_points (
          id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, trackId INTEGER NOT NULL,
          latitude REAL NOT NULL, longitude REAL NOT NULL, altitude REAL, accuracy REAL,
          speed REAL, bearing REAL, timestamp INTEGER NOT NULL, verticalAccuracy REAL,
          speedAccuracy REAL, bearingAccuracy REAL, satellitesInFix INTEGER, cn0 REAL,
          ignored INTEGER NOT NULL DEFAULT 0, ignoreReason TEXT,
          segmentStart INTEGER NOT NULL DEFAULT 0)
        """,
    )
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS index_track_points_trackId_timestamp " +
            "ON track_points(trackId, timestamp)",
    )
}
