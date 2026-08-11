package io.github.valeronm.breadcrumb.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.util.TableInfo
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The tables [AppDatabase.MIGRATION_16_17] leaves holding nothing — the two it re-keys, and the
 *  one whose membership figures are meaningless once the members are gone. */
private val DERIVED_TABLES = listOf("derived_clusters", "cluster_members", "derived_intervals")

/**
 * v17 keys `cluster_members` and `derived_intervals` on what already identified a row, and indexes
 * `tracks(startedAt)`.
 */
@RunWith(RobolectricTestRunner::class)
class Migration16To17Test {

    /**
     * A v16 database, reached by running v16's own migration rather than by hand-writing its schema
     * — so what this migrates is what an install actually holds. `tracks` is a stub of the one
     * column v17 touches: an index needs something to sit on, and nothing here reads the rest.
     */
    private val fixture = MigrationDb(16) { db ->
        db.execSQL(
            "CREATE TABLE tracks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "startedAt INTEGER NOT NULL)",
        )
        AppDatabase.MIGRATION_15_16.migrate(db)
    }
    private val db: SupportSQLiteDatabase get() = fixture.db

    @After fun tearDown() = fixture.close()

    /**
     * SQLite cannot re-key a table in place, so the rows go. That is the whole argument for doing it
     * this way rather than copying them across: they are output, and
     * [io.github.valeronm.breadcrumb.data.DerivationStore.LOGIC_VERSION] moves with this migration
     * so the next launch derives them again. A row surviving here would be one derived under the old
     * keys, which is the half-filled state v16 refused to create.
     */
    @Test fun `the derived rows go, all three tables' worth`() {
        db.execSQL(
            "INSERT INTO derived_clusters (id, placeId, anchorLat, anchorLon, radiusM, " +
                "sumLat, sumLon, memberCount) VALUES (1, NULL, 1.0, -2.0, 60.0, 1.0, -2.0, 1)",
        )
        db.execSQL(
            "INSERT INTO cluster_members (clusterId, trackId, isStart, lat, lon, atMs) " +
                "VALUES (1, 7, 1, 1.0, -2.0, 1000)",
        )
        db.execSQL(
            "INSERT INTO derived_intervals (type, start, endedAt, afterTrackId) " +
                "VALUES ('STAY', 1000, 2000, 7)",
        )

        AppDatabase.MIGRATION_16_17.migrate(db)

        DERIVED_TABLES.forEach { table ->
            db.query("SELECT COUNT(*) FROM $table").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("$table should be left empty", 0, c.getInt(0))
            }
        }
    }

    /**
     * **The guard this migration is actually exposed to**, and it belongs to whichever migration is
     * newest: Room validates the schema it finds against the one its entities describe, on the first
     * open after an upgrade, so a column type, a nullability, a missing index or the
     * `ON UPDATE NO ACTION` half of a foreign key is not a test failure but a crash on a real
     * install with real history behind it. Only the *end* of the chain is ever compared that way, so
     * a case pinned to an older migration stops asking anything the moment another follows it — this
     * one moves to the next migration's suite when there is one.
     *
     * With `exportSchema = false` there is no schema JSON for Room's own `MigrationTestHelper` to
     * read, so the comparison is made here instead, against the tables Room generates from the same
     * entities. [TableInfo] is the shape Room compares, rather than the `CREATE` text, so formatting
     * is not mistaken for drift and neither is drift for formatting.
     *
     * Its `SupportSQLiteDatabase` overload is deprecated — Room's own generated code reads through
     * an `SQLiteConnection` now — and kept because that connection is not reachable from a test
     * holding this database. If it is withdrawn, compare the normalized `CREATE` statements out of
     * `sqlite_master` instead, which catches the same drift less precisely.
     */
    @Suppress("DEPRECATION")
    @Test
    fun `the migrated schema is exactly what Room would create`() {
        AppDatabase.MIGRATION_16_17.migrate(db)

        val room = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val generated = room.openHelper.writableDatabase
            DERIVED_TABLES.forEach { table ->
                assertEquals(table, TableInfo.read(generated, table), TableInfo.read(db, table))
            }
        } finally {
            room.close()
        }
    }

    /**
     * `tracks` is compared by its index alone rather than through [TableInfo]: the fixture holds a
     * stub of it, this migration adds one index to it, and a whole-table comparison would be a
     * second copy of the track schema kept in step by hand for nothing.
     */
    @Test fun `a track's start is indexed`() {
        AppDatabase.MIGRATION_16_17.migrate(db)

        val indexed = db.query("PRAGMA index_list(tracks)").use { c ->
            generateSequence { if (c.moveToNext()) c.getString(c.getColumnIndexOrThrow("name")) else null }
                .toList()
        }
        assertTrue("tracks(startedAt) should be indexed, got $indexed", "index_tracks_startedAt" in indexed)
    }
}
