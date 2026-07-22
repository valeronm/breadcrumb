package io.github.valeronm.breadcrumb.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v13 adds `tracks.needsReview`. A plain ADD COLUMN, so the point of the test is the default:
 * every existing row must come out unmarked and otherwise untouched — the backfill decides who
 * is marked, and a row that migrated to "1" would badge the whole timeline.
 *
 * See [MigrationDb] for why the v12 schema is written by hand.
 */
@RunWith(RobolectricTestRunner::class)
class Migration12To13Test {

    private val fixture = MigrationDb(12, ::createV12Schema)
    private val db: SupportSQLiteDatabase get() = fixture.db

    @After fun tearDown() = fixture.close()

    @Test fun `existing tracks migrate unmarked, with their other columns intact`() {
        db.execSQL(
            "INSERT INTO tracks (id, activityType, startedAt, endedAt, distanceMeters, " +
                "pointCount, ignoredCount, startLat, startLon, endLat, endLon) " +
                "VALUES (7, 'WALKING', 1000, 2000, 840.5, 96, 3, 1.00, -2.00, 1.01, -2.01)",
        )
        db.execSQL(
            "INSERT INTO tracks (id, activityType, startedAt, discardedAt, discardReason) " +
                "VALUES (8, 'DRIVING', 3000, 4000, 'filtered')",
        )

        AppDatabase.MIGRATION_12_13.migrate(db)

        db.query("SELECT * FROM tracks ORDER BY id").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(c.getColumnIndexOrThrow("needsReview")))
            assertEquals(840.5, c.getDouble(c.getColumnIndexOrThrow("distanceMeters")), 1e-9)
            assertEquals(96, c.getInt(c.getColumnIndexOrThrow("pointCount")))
            assertEquals(3, c.getInt(c.getColumnIndexOrThrow("ignoredCount")))
            assertEquals(1.00, c.getDouble(c.getColumnIndexOrThrow("startLat")), 1e-9)
            assertEquals(-2.01, c.getDouble(c.getColumnIndexOrThrow("endLon")), 1e-9)
            assertTrue(c.moveToNext())
            assertEquals(0, c.getInt(c.getColumnIndexOrThrow("needsReview")))
            assertEquals("filtered", c.getString(c.getColumnIndexOrThrow("discardReason")))
            assertTrue("nullable columns stay null", c.isNull(c.getColumnIndexOrThrow("endedAt")))
        }
    }
}

private fun createV12Schema(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE tracks (
          id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, activityType TEXT NOT NULL,
          startedAt INTEGER NOT NULL, endedAt INTEGER, distanceMeters REAL NOT NULL DEFAULT 0,
          pointCount INTEGER NOT NULL DEFAULT 0, ignoredCount INTEGER NOT NULL DEFAULT 0,
          startLat REAL, startLon REAL, endLat REAL, endLon REAL,
          discardedAt INTEGER, discardReason TEXT)
        """,
    )
}
