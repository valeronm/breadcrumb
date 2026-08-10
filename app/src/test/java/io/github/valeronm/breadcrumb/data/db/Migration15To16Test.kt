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

/** The tables [AppDatabase.MIGRATION_15_16] creates, which is the whole of what it does. */
private val DERIVED_TABLES = listOf("derived_clusters", "cluster_members", "derived_intervals")

/** v16 adds the derived stay/place tables — see [AppDatabase.MIGRATION_15_16] for why empty. */
@RunWith(RobolectricTestRunner::class)
class Migration15To16Test {

    // Nothing pre-existing needs to be here: the migration creates tables and touches none.
    private val fixture = MigrationDb(15) {}
    private val db: SupportSQLiteDatabase get() = fixture.db

    @After fun tearDown() = fixture.close()

    @Test fun `the derived tables arrive, and arrive empty`() {
        AppDatabase.MIGRATION_15_16.migrate(db)

        DERIVED_TABLES.forEach { table ->
            db.query("SELECT COUNT(*) FROM $table").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("$table should start empty", 0, c.getInt(0))
            }
        }
    }

    /**
     * **The guard this migration is actually exposed to.** Room validates the schema it finds
     * against the one its entities describe, on the first open after an upgrade — so a column type,
     * a nullability, a missing index or the `ON UPDATE NO ACTION` half of a foreign key is not a
     * test failure but a crash on a real install with real history behind it. With
     * `exportSchema = false` there is no schema JSON for Room's own `MigrationTestHelper` to read,
     * so the comparison is made here instead, against the tables Room generates from the same
     * entities. [TableInfo] is the shape Room compares, rather than the `CREATE` text, so
     * formatting is not mistaken for drift and neither is drift for formatting.
     *
     * Its `SupportSQLiteDatabase` overload is deprecated — Room's own generated code reads through
     * an `SQLiteConnection` now — and kept because that connection is not reachable from a test
     * holding this database. If it is withdrawn, compare the normalized `CREATE` statements out of
     * `sqlite_master` instead, which catches the same drift less precisely.
     */
    @Suppress("DEPRECATION")
    @Test
    fun `the migration creates exactly what Room would create`() {
        AppDatabase.MIGRATION_15_16.migrate(db)

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
}
