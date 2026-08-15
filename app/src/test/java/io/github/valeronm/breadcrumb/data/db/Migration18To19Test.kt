package io.github.valeronm.breadcrumb.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.util.TableInfo
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
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
 * going, so a migrated install must not owe a re-derivation. The shape it reaches is Room's own to
 * validate on the first open after an upgrade; what that validation cannot see — and what this
 * covers — is whether the rows came across the rebuild at all.
 */
@RunWith(RobolectricTestRunner::class)
class Migration18To19Test {

    /**
     * A v18 database: the two tables v19 touches. Hand-written and frozen — v18 is history, so this
     * shape can never change again, and no schema JSON below v19 was ever exported to derive it
     * from. It is what the retired v16→v17 rebuild left, which is where to check it against.
     */
    private val fixture = MigrationDb(18) { db ->
        db.execSQL(
            "CREATE TABLE liveness_events (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "type TEXT NOT NULL, at INTEGER NOT NULL, until INTEGER)",
        )
        db.execSQL(
            """
            CREATE TABLE derived_intervals (
              type TEXT NOT NULL,
              start INTEGER NOT NULL,
              endedAt INTEGER NOT NULL,
              afterTrackId INTEGER NOT NULL,
              provenance TEXT,
              clusterId INTEGER,
              reason TEXT,
              fromClusterId INTEGER, toClusterId INTEGER,
              fromLat REAL, fromLon REAL,
              toLat REAL, toLon REAL,
              PRIMARY KEY(afterTrackId) )
            """,
        )
        db.execSQL("CREATE INDEX index_derived_intervals_start ON derived_intervals(start)")
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

    /**
     * **The guard a real upgrade is exposed to**, and this is where it belongs: Room compares what
     * it finds against its entities on the first open after an upgrade, and only the *end* of the
     * chain is ever compared that way — which, the chain being one migration long, is here. The
     * case above reads column and index names, and so cannot see a nullability, a column type or
     * the primary key; those live in the hand-written `CREATE TABLE` this migration rebuilds with,
     * and get someone a crash on open rather than a failure here. [TableInfo] is the shape Room
     * compares, rather than the `CREATE` text, so formatting is not mistaken for drift.
     *
     * Move it into the next migration's test when one lands, for the same reason it sits here.
     */
    @Suppress("DEPRECATION")
    @Test
    fun `the rebuilt table is the shape Room builds from the entities`() {
        AppDatabase.MIGRATION_18_19.migrate(db)

        val room = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val generated = room.openHelper.writableDatabase
            assertEquals(
                TableInfo.read(generated, "derived_intervals"),
                TableInfo.read(db, "derived_intervals"),
            )
        } finally {
            room.close()
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
