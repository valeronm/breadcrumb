package io.github.valeronm.breadcrumb.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.util.TableInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The tables the migrated chain builds from scratch, and so the ones this can compare. */
private val CHAIN_BUILT_TABLES =
    listOf("derived_clusters", "cluster_members", "derived_intervals")

/**
 * **The guard every migration is actually exposed to**: Room validates the schema it finds against
 * the one its entities describe, on the first open after an upgrade — so a column type, a
 * nullability, a missing index or the `ON UPDATE NO ACTION` half of a foreign key is not a test
 * failure but a crash on a real install with real history behind it.
 *
 * Only the **end** of the chain is ever compared that way, which is why this is a suite of its own
 * rather than a case inside one migration's: pinned to a single step it would stop asking anything
 * the moment another followed it, and moving it each time is a rule someone has to remember. It
 * walks [AppDatabase.MIGRATIONS], so a migration added there is checked here by having been added
 * there.
 *
 * With `exportSchema = false` there is no schema JSON for Room's own `MigrationTestHelper` to read,
 * so the comparison is made against the tables Room generates from the same entities. [TableInfo] is
 * the shape Room compares, rather than the `CREATE` text, so formatting is not mistaken for drift
 * and neither is drift for formatting.
 *
 * Its `SupportSQLiteDatabase` overload is deprecated — Room's own generated code reads through an
 * `SQLiteConnection` now — and kept because that connection is not reachable from a test holding
 * this database. If it is withdrawn, compare the normalized `CREATE` statements out of
 * `sqlite_master` instead, which catches the same drift less precisely.
 */
@RunWith(RobolectricTestRunner::class)
class SchemaMatchesEntitiesTest {

    /**
     * What the chain from [FROM_VERSION] needs to already exist. `tracks` is a stub of the one column
     * those migrations touch — an index needs something to sit on, and nothing reads the rest of it,
     * so it stays out of the comparison. `liveness_events` exists only so the chain has something to
     * operate on — v18 indexes it, v19 drops it — and cannot be compared, there being no entity at
     * head to compare against; that the drop leaves no trace is `Migration18To19Test`'s.
     */
    private val fixture = MigrationDb(FROM_VERSION) { db ->
        db.execSQL(
            "CREATE TABLE tracks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "startedAt INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE liveness_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "type TEXT NOT NULL, at INTEGER NOT NULL, until INTEGER)",
        )
        db.execSQL("CREATE INDEX index_liveness_events_at ON liveness_events(at)")
    }

    @After fun tearDown() = fixture.close()

    @Suppress("DEPRECATION")
    @Test
    fun `the schema the migration chain reaches is the one Room would create`() {
        AppDatabase.MIGRATIONS
            .filter { it.startVersion >= FROM_VERSION }
            .sortedBy { it.startVersion }
            .forEach { it.migrate(fixture.db) }

        val room = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val generated = room.openHelper.writableDatabase
            CHAIN_BUILT_TABLES.forEach { table ->
                assertEquals(table, TableInfo.read(generated, table), TableInfo.read(fixture.db, table))
            }
        } finally {
            room.close()
        }
    }

    private companion object {
        /** Where the chain under comparison starts. Earlier than this the migrations rebuild
         *  `track_points` and `places` wholesale, which would be most of the schema hand-written into
         *  the fixture to check a part of it none of them touch. Never *raise* it without checking
         *  what leaves [CHAIN_BUILT_TABLES] with it — a table dropped from the comparison stops being
         *  checked silently. */
        const val FROM_VERSION = 15
    }
}
