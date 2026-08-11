package io.github.valeronm.breadcrumb.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v19 drops the liveness log and the `provenance` column of `derived_intervals`. Unlike v17's
 * re-keying, the interval rows are *carried across*: nothing about them changed but the column
 * going, so a migrated install must not owe a re-derivation. That the schema it reaches is the one
 * Room builds from the entities is `SchemaMatchesEntitiesTest`'s, asked of the whole chain.
 */
@RunWith(RobolectricTestRunner::class)
class Migration18To19Test {

    /** A v18 database, reached by running the real chain from v16 over the tables it touches. */
    private val fixture = MigrationDb(18) { db ->
        db.execSQL(
            "CREATE TABLE tracks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "startedAt INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS liveness_events (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "type TEXT NOT NULL, at INTEGER NOT NULL, until INTEGER)",
        )
        AppDatabase.MIGRATION_15_16.migrate(db)
        AppDatabase.MIGRATION_16_17.migrate(db)
        AppDatabase.MIGRATION_17_18.migrate(db)
    }
    private val db: SupportSQLiteDatabase get() = fixture.db

    @After fun tearDown() = fixture.close()

    @Test fun `the liveness log goes and the intervals survive without their provenance`() {
        db.execSQL("INSERT INTO liveness_events (type, at) VALUES ('ARMED', 1000)")
        db.execSQL(
            "INSERT INTO derived_intervals (type, start, endedAt, afterTrackId, provenance, clusterId) " +
                "VALUES ('STAY', 1000, 2000, 7, 'OBSERVED', 3)",
        )
        db.execSQL(
            "INSERT INTO derived_intervals (type, start, endedAt, afterTrackId, reason, " +
                "fromClusterId, toClusterId, fromLat, fromLon, toLat, toLon) " +
                "VALUES ('GAP', 3000, 4000, 8, 'MOVED_UNRECORDED', 3, 4, 1.0, -2.0, 1.01, -2.01)",
        )

        AppDatabase.MIGRATION_18_19.migrate(db)

        val tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
        }
        assertFalse("liveness_events should be gone, got $tables", "liveness_events" in tables)

        val columns = db.query("PRAGMA table_info(derived_intervals)").use { c ->
            generateSequence {
                if (c.moveToNext()) c.getString(c.getColumnIndexOrThrow("name")) else null
            }.toList()
        }
        assertFalse("provenance should be gone, got $columns", "provenance" in columns)

        db.query(
            "SELECT type, start, endedAt, afterTrackId, clusterId FROM derived_intervals " +
                "ORDER BY start",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("STAY", c.getString(0))
            assertEquals(1000L, c.getLong(1))
            assertEquals(2000L, c.getLong(2))
            assertEquals(7L, c.getLong(3))
            assertEquals(3L, c.getLong(4))
            assertTrue(c.moveToNext())
            assertEquals("GAP", c.getString(0))
            assertEquals(8L, c.getLong(3))
        }
        db.query("SELECT reason, toLon FROM derived_intervals WHERE afterTrackId = 8").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("MOVED_UNRECORDED", c.getString(0))
            assertEquals(-2.01, c.getDouble(1), 1e-9)
        }
    }

    @Test fun `the interval start index survives the rebuild`() {
        AppDatabase.MIGRATION_18_19.migrate(db)

        val indexed = db.query("PRAGMA index_list(derived_intervals)").use { c ->
            generateSequence { if (c.moveToNext()) c.getString(c.getColumnIndexOrThrow("name")) else null }
                .toList()
        }
        assertTrue(
            "derived_intervals(start) should be indexed, got $indexed",
            "index_derived_intervals_start" in indexed,
        )
    }
}
