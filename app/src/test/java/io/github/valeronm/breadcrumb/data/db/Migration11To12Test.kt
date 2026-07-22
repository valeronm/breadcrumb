package io.github.valeronm.breadcrumb.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v12 drops `track_points.provider` by rebuilding the table (minSdk 26's SQLite has no DROP
 * COLUMN), so the risk isn't the lost column — it's the copy: every other column of every point
 * must survive, ids included (they anchor `pointsAfter`'s incremental reload), and the composite
 * index must exist again on the rebuilt table.
 *
 * See [MigrationDb] for why the v11 schema is written by hand.
 */
@RunWith(RobolectricTestRunner::class)
class Migration11To12Test {

    private val fixture = MigrationDb(11, ::createV11Schema)
    private val db: SupportSQLiteDatabase get() = fixture.db

    @After fun tearDown() = fixture.close()

    @Test fun `the rebuild drops provider and keeps every other column of every point`() {
        db.execSQL(
            "INSERT INTO track_points (id, trackId, latitude, longitude, altitude, accuracy, " +
                "timestamp, satellitesInFix, cn0, provider, ignored, ignoreReason, segmentStart) " +
                "VALUES (42, 7, 38.70, -9.30, 55.5, 4.5, 1000, 11, 33.5, 'gps', 1, 'jump', 1)",
        )
        db.execSQL(
            "INSERT INTO track_points (id, trackId, latitude, longitude, timestamp, provider) " +
                "VALUES (43, 7, 38.71, -9.31, 2000, 'fused')",
        )

        AppDatabase.MIGRATION_11_12.migrate(db)

        db.query("SELECT * FROM track_points ORDER BY id").use { c ->
            assertEquals(-1, c.getColumnIndex("provider"))
            assertTrue(c.moveToFirst())
            assertEquals(42, c.getLong(c.getColumnIndexOrThrow("id")))
            assertEquals(7, c.getLong(c.getColumnIndexOrThrow("trackId")))
            assertEquals(38.70, c.getDouble(c.getColumnIndexOrThrow("latitude")), 1e-9)
            assertEquals(-9.30, c.getDouble(c.getColumnIndexOrThrow("longitude")), 1e-9)
            assertEquals(55.5, c.getDouble(c.getColumnIndexOrThrow("altitude")), 1e-9)
            assertEquals(4.5f, c.getFloat(c.getColumnIndexOrThrow("accuracy")), 1e-6f)
            assertEquals(1000, c.getLong(c.getColumnIndexOrThrow("timestamp")))
            assertEquals(11, c.getInt(c.getColumnIndexOrThrow("satellitesInFix")))
            assertEquals(33.5f, c.getFloat(c.getColumnIndexOrThrow("cn0")), 1e-6f)
            assertEquals(1, c.getInt(c.getColumnIndexOrThrow("ignored")))
            assertEquals("jump", c.getString(c.getColumnIndexOrThrow("ignoreReason")))
            assertEquals(1, c.getInt(c.getColumnIndexOrThrow("segmentStart")))
            assertTrue(c.moveToNext())
            assertEquals(43, c.getLong(c.getColumnIndexOrThrow("id")))
            assertTrue("nullable columns stay null", c.isNull(c.getColumnIndexOrThrow("altitude")))
        }
    }

    @Test fun `the composite index exists on the rebuilt table`() {
        AppDatabase.MIGRATION_11_12.migrate(db)

        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' " +
                "AND name = 'index_track_points_trackId_timestamp'",
        ).use { c ->
            assertTrue("the rebuilt table must carry the composite index", c.moveToFirst())
        }
    }
}

private fun createV11Schema(db: SupportSQLiteDatabase) {
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
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS index_track_points_trackId_timestamp " +
            "ON track_points(trackId, timestamp)",
    )
}
