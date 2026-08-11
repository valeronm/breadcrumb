package io.github.valeronm.breadcrumb.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The tables [AppDatabase.MIGRATION_15_16] creates, which is the whole of what it does. */
private val DERIVED_TABLES = listOf("derived_clusters", "cluster_members", "derived_intervals")

/**
 * v16 adds the derived stay/place tables — see [AppDatabase.MIGRATION_15_16] for why empty.
 *
 * That the schema these reach is the one Room builds from the entities is asked at the *end* of the
 * migration chain, which is the only place it can be true — `Migration16To17Test` holds it, and it
 * moves again when another migration follows.
 */
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
}
