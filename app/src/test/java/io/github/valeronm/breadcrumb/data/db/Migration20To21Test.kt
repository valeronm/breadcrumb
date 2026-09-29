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
 * v21 moves a place's identities at other sources into `place_identities` and names who last wrote
 * the row in `places.source`. `places` is rebuilt while tracks state their ends to it, so both its
 * rows and those statements must come across it.
 */
@RunWith(RobolectricTestRunner::class)
class Migration20To21Test {

    /** A v20 database: the tables v21 touches. Hand-written and frozen from `20.json`. */
    private val fixture = MigrationDb(20) { db ->
        db.execSQL(
            "CREATE TABLE places (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, label TEXT, " +
                "lat REAL NOT NULL, lon REAL NOT NULL, createdAt INTEGER NOT NULL, radiusM REAL NOT NULL, " +
                "category TEXT, externalProvider TEXT, externalId TEXT)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX index_places_externalProvider_externalId ON places(externalProvider, externalId)",
        )
        db.execSQL(
            "CREATE TABLE tracks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "activityType TEXT NOT NULL, startedAt INTEGER NOT NULL, endedAt INTEGER, source TEXT, " +
                "distanceMeters REAL NOT NULL, pointCount INTEGER NOT NULL, ignoredCount INTEGER NOT NULL, " +
                "startLat REAL, startLon REAL, endLat REAL, endLon REAL, discardedAt INTEGER, " +
                "discardReason TEXT, needsReview INTEGER NOT NULL, startPlaceId INTEGER, endPlaceId INTEGER, " +
                "FOREIGN KEY(startPlaceId) REFERENCES places(id) ON UPDATE NO ACTION ON DELETE SET NULL, " +
                "FOREIGN KEY(endPlaceId) REFERENCES places(id) ON UPDATE NO ACTION ON DELETE SET NULL)",
        )
        db.execSQL("CREATE INDEX index_tracks_startedAt ON tracks(startedAt)")
        db.execSQL("CREATE INDEX index_tracks_startPlaceId ON tracks(startPlaceId)")
        db.execSQL("CREATE INDEX index_tracks_endPlaceId ON tracks(endPlaceId)")
    }
    private val db: SupportSQLiteDatabase get() = fixture.db

    @After fun tearDown() = fixture.close()

    private fun insertV20Rows() {
        db.execSQL(
            "INSERT INTO places (id, label, lat, lon, createdAt, radiusM, category) " +
                "VALUES (3, 'Home', 1.0, -2.0, 100, 150.0, 'home')",
        )
        db.execSQL(
            "INSERT INTO places (id, label, lat, lon, createdAt, radiusM, externalProvider, externalId) " +
                "VALUES (5, NULL, 1.01, -2.01, 200, 90.0, 'google', 'ChIJ-5')",
        )
        db.execSQL(
            "INSERT INTO tracks (id, activityType, startedAt, endedAt, source, distanceMeters, pointCount, " +
                "ignoredCount, needsReview, startPlaceId, endPlaceId) " +
                "VALUES (7, 'WALKING', 1000, 2000, 'google_timeline', 812.5, 40, 2, 0, 3, 5)",
        )
    }

    @Test fun `places come across with their source, and their ids at Google as identities`() {
        insertV20Rows()

        AppDatabase.MIGRATION_20_21.migrate(db)

        db.query("SELECT id, label, lat, lon, createdAt, radiusM, category, source FROM places ORDER BY id").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(3L, c.getLong(0))
            assertEquals("Home", c.getString(1))
            assertEquals(1.0, c.getDouble(2), 0.0)
            assertEquals(-2.0, c.getDouble(3), 0.0)
            assertEquals(100L, c.getLong(4))
            assertEquals(150.0, c.getDouble(5), 0.0)
            assertEquals("home", c.getString(6))
            assertEquals("manual", c.getString(7))
            assertTrue(c.moveToNext())
            assertEquals(5L, c.getLong(0))
            assertTrue(c.isNull(1))
            assertEquals(90.0, c.getDouble(5), 0.0)
            assertEquals("google_timeline", c.getString(7))
            assertFalse(c.moveToNext())
        }
        db.query("SELECT provider, externalId, placeId FROM place_identities").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("google", c.getString(0))
            assertEquals("ChIJ-5", c.getString(1))
            assertEquals(5L, c.getLong(2))
            assertFalse(c.moveToNext())
        }
    }

    @Test fun `the track ends stated to a place survive its rebuild`() {
        insertV20Rows()

        AppDatabase.MIGRATION_20_21.migrate(db)

        db.query("SELECT startPlaceId, endPlaceId FROM tracks WHERE id = 7").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(3L, c.getLong(0))
            assertEquals(5L, c.getLong(1))
        }
    }

    @Test fun `deleting a place deletes its identities and clears the ends stated to it`() {
        insertV20Rows()
        AppDatabase.MIGRATION_20_21.migrate(db)
        db.execSQL("PRAGMA foreign_keys = ON")

        db.execSQL("DELETE FROM places WHERE id = 5")

        db.query("SELECT COUNT(*) FROM place_identities").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        db.query("SELECT startPlaceId, endPlaceId FROM tracks WHERE id = 7").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(3L, c.getLong(0))
            assertTrue(c.isNull(1))
        }
    }

    /**
     * **The guard a real upgrade is exposed to**, and this is where it belongs: Room compares what
     * it finds against its entities on the first open after an upgrade, and only the *end* of the
     * chain is ever compared that way — which is here. The cases above read values, and so cannot
     * see a nullability, a column type, a foreign key or an index; those live in the hand-written
     * DDL the chain runs, and get someone a crash on open rather than a failure here. [TableInfo] is
     * the shape Room compares, rather than the `CREATE` text, so formatting is not mistaken for
     * drift.
     *
     * Move it into the next migration's test when one lands, for the same reason it sits here.
     */
    @Suppress("DEPRECATION")
    @Test
    fun `the migrated tables are the shape Room builds from the entities`() {
        AppDatabase.MIGRATION_20_21.migrate(db)

        val room = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val generated = room.openHelper.writableDatabase
            for (table in listOf("places", "place_identities", "tracks")) {
                assertEquals(TableInfo.read(generated, table), TableInfo.read(db, table))
            }
        } finally {
            room.close()
        }
    }
}
