package io.github.valeronm.breadcrumb.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v14 adds `places.category`. A plain ADD COLUMN, so the point of the test is that the column
 * arrives *empty*: untagged is a real state, and a place migrated into some category would put a
 * wrong chip on every stay it ever captured. The user's own words — the label, pin and radius —
 * have to come across untouched, since a place row is the only place they exist.
 *
 * See [MigrationDb] for why the v13 schema is written by hand.
 */
@RunWith(RobolectricTestRunner::class)
class Migration13To14Test {

    private val fixture = MigrationDb(13, ::createV13Schema)
    private val db: SupportSQLiteDatabase get() = fixture.db

    @After fun tearDown() = fixture.close()

    @Test fun `existing places migrate untagged, with what the user said intact`() {
        db.execSQL(
            "INSERT INTO places (id, label, lat, lon, createdAt, radiusM) " +
                "VALUES (7, 'Corner shop', 1.00, -2.00, 1000, 325.0)",
        )
        db.execSQL(
            "INSERT INTO places (id, label, lat, lon, createdAt, radiusM) " +
                "VALUES (8, 'Trailhead', 1.01, -2.01, 2000, 150.0)",
        )

        AppDatabase.MIGRATION_13_14.migrate(db)

        db.query("SELECT * FROM places ORDER BY id").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue("untagged, not defaulted", c.isNull(c.getColumnIndexOrThrow("category")))
            assertEquals("Corner shop", c.getString(c.getColumnIndexOrThrow("label")))
            assertEquals(1.00, c.getDouble(c.getColumnIndexOrThrow("lat")), 1e-9)
            assertEquals(-2.00, c.getDouble(c.getColumnIndexOrThrow("lon")), 1e-9)
            assertEquals(1000L, c.getLong(c.getColumnIndexOrThrow("createdAt")))
            assertEquals(325.0, c.getDouble(c.getColumnIndexOrThrow("radiusM")), 1e-9)
            assertTrue(c.moveToNext())
            assertTrue(c.isNull(c.getColumnIndexOrThrow("category")))
            assertEquals("Trailhead", c.getString(c.getColumnIndexOrThrow("label")))
        }
    }
}

private fun createV13Schema(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE places (
          id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, label TEXT NOT NULL,
          lat REAL NOT NULL, lon REAL NOT NULL, createdAt INTEGER NOT NULL,
          radiusM REAL NOT NULL)
        """,
    )
}
