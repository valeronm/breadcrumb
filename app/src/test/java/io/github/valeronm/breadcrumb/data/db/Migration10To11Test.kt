package io.github.valeronm.breadcrumb.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v11 moves each track's point aggregates onto its row. The upgrade has to *fill* them in the same
 * step: the timeline reads the columns instead of counting points, so a migrated-but-unfilled row
 * is a track with no points and no endpoints — gone from the list and from the stay derivation.
 *
 * See [MigrationDb] for why the v10 schema is written by hand.
 */
@RunWith(RobolectricTestRunner::class)
class Migration10To11Test {

    private val fixture = MigrationDb(10, ::createV10Schema)
    private val db: SupportSQLiteDatabase get() = fixture.db

    @After fun tearDown() = fixture.close()

    private fun insertPoint(trackId: Long, timestamp: Long, lat: Double, lon: Double, ignored: Int) {
        db.execSQL(
            "INSERT INTO track_points (trackId, latitude, longitude, timestamp, ignored) " +
                "VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any>(trackId, lat, lon, timestamp, ignored),
        )
    }

    @Test fun `the upgrade fills the aggregates from the existing points`() {
        db.execSQL(
            "INSERT INTO tracks (id, activityType, startedAt, endedAt, distanceMeters) " +
                "VALUES (1, 'WALKING', 1000, 9000, 123.5)",
        )
        // Out of insertion order on purpose: the endpoints follow timestamp, not rowid.
        insertPoint(1, timestamp = 3000, lat = 1.02, lon = -2.02, ignored = 0)
        insertPoint(1, timestamp = 1000, lat = 1.00, lon = -2.00, ignored = 0)
        insertPoint(1, timestamp = 2000, lat = 0.0, lon = 0.0, ignored = 1) // a bad fix, mid-track
        insertPoint(1, timestamp = 500, lat = 5.0, lon = 5.0, ignored = 1) // an ignored *first* fix

        AppDatabase.MIGRATION_10_11.migrate(db)

        db.query("SELECT * FROM tracks WHERE id = 1").use { c ->
            c.moveToFirst()
            assertEquals(2, c.getInt(c.getColumnIndexOrThrow("pointCount")))
            assertEquals(2, c.getInt(c.getColumnIndexOrThrow("ignoredCount")))
            // The endpoints are the first and last *good* fixes, ignoring the strays entirely.
            assertEquals(1.00, c.getDouble(c.getColumnIndexOrThrow("startLat")), 1e-9)
            assertEquals(-2.00, c.getDouble(c.getColumnIndexOrThrow("startLon")), 1e-9)
            assertEquals(1.02, c.getDouble(c.getColumnIndexOrThrow("endLat")), 1e-9)
            assertEquals(-2.02, c.getDouble(c.getColumnIndexOrThrow("endLon")), 1e-9)
            // Distance was already stored per track; the migration must not touch it.
            assertEquals(123.5, c.getDouble(c.getColumnIndexOrThrow("distanceMeters")), 1e-9)
        }
    }

    @Test fun `a track with no points migrates to zeroed counts and null endpoints`() {
        db.execSQL(
            "INSERT INTO tracks (id, activityType, startedAt, endedAt, distanceMeters) " +
                "VALUES (7, 'WALKING', 1000, 2000, 0.0)",
        )

        AppDatabase.MIGRATION_10_11.migrate(db)

        db.query("SELECT * FROM tracks WHERE id = 7").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(c.getColumnIndexOrThrow("pointCount")))
            assertEquals(0, c.getInt(c.getColumnIndexOrThrow("ignoredCount")))
            for (column in listOf("startLat", "startLon", "endLat", "endLon")) {
                assertTrue("$column has no point to come from", c.isNull(c.getColumnIndexOrThrow(column)))
            }
        }
    }
}

private fun createV10Schema(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE tracks (
          id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, activityType TEXT NOT NULL,
          startedAt INTEGER NOT NULL, endedAt INTEGER, distanceMeters REAL NOT NULL,
          discardedAt INTEGER, discardReason TEXT)
        """,
    )
    db.execSQL(
        """
        CREATE TABLE track_points (
          id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, trackId INTEGER NOT NULL,
          latitude REAL NOT NULL, longitude REAL NOT NULL, altitude REAL, accuracy REAL,
          speed REAL, bearing REAL, timestamp INTEGER NOT NULL, verticalAccuracy REAL,
          speedAccuracy REAL, bearingAccuracy REAL, satellitesInFix INTEGER, cn0 REAL,
          provider TEXT, ignored INTEGER NOT NULL DEFAULT 0, ignoreReason TEXT,
          segmentStart INTEGER NOT NULL DEFAULT 0)
        """,
    )
}
