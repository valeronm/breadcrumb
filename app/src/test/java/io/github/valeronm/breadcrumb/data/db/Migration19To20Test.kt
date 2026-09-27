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

/**
 * v20 lets a place go unnamed and name its source, and lets a track state the places at its ends.
 * `places` is rebuilt, so its rows must come across it; `tracks` only gains columns.
 */
@RunWith(RobolectricTestRunner::class)
class Migration19To20Test {

    /** A v19 database: the tables v20 touches, plus `derived_intervals` for the shape check below.
     *  Hand-written and frozen from `19.json`. */
    private val fixture = MigrationDb(19) { db ->
        db.execSQL(
            "CREATE TABLE tracks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "activityType TEXT NOT NULL, startedAt INTEGER NOT NULL, endedAt INTEGER, source TEXT, " +
                "distanceMeters REAL NOT NULL, pointCount INTEGER NOT NULL, ignoredCount INTEGER NOT NULL, " +
                "startLat REAL, startLon REAL, endLat REAL, endLon REAL, discardedAt INTEGER, " +
                "discardReason TEXT, needsReview INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX index_tracks_startedAt ON tracks(startedAt)")
        db.execSQL(
            "CREATE TABLE places (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, label TEXT NOT NULL, " +
                "lat REAL NOT NULL, lon REAL NOT NULL, createdAt INTEGER NOT NULL, radiusM REAL NOT NULL, " +
                "category TEXT)",
        )
        db.execSQL(
            "CREATE TABLE derived_intervals (type TEXT NOT NULL, start INTEGER NOT NULL, " +
                "endedAt INTEGER NOT NULL, afterTrackId INTEGER NOT NULL, clusterId INTEGER, reason TEXT, " +
                "fromClusterId INTEGER, toClusterId INTEGER, fromLat REAL, fromLon REAL, toLat REAL, " +
                "toLon REAL, PRIMARY KEY(afterTrackId))",
        )
        db.execSQL("CREATE INDEX index_derived_intervals_start ON derived_intervals(start)")
    }
    private val db: SupportSQLiteDatabase get() = fixture.db

    @After fun tearDown() = fixture.close()

    private fun insertV19Rows() {
        db.execSQL(
            "INSERT INTO places (id, label, lat, lon, createdAt, radiusM, category) " +
                "VALUES (3, 'Home', 1.0, -2.0, 100, 150.0, 'home')",
        )
        db.execSQL(
            "INSERT INTO places (id, label, lat, lon, createdAt, radiusM) VALUES (5, 'Gym', 1.01, -2.01, 200, 90.0)",
        )
        db.execSQL(
            "INSERT INTO tracks (id, activityType, startedAt, endedAt, source, distanceMeters, pointCount, " +
                "ignoredCount, startLat, startLon, endLat, endLon, needsReview) " +
                "VALUES (7, 'WALKING', 1000, 2000, 'recorded', 812.5, 40, 2, 1.0, -2.0, 1.01, -2.01, 0)",
        )
    }

    @Test fun `places and tracks come across with the new columns empty`() {
        insertV19Rows()

        AppDatabase.MIGRATION_19_20.migrate(db)

        db.query(
            "SELECT id, label, lat, lon, createdAt, radiusM, category, externalProvider, externalId " +
                "FROM places ORDER BY id",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(3L, c.getLong(0))
            assertEquals("Home", c.getString(1))
            assertEquals(1.0, c.getDouble(2), 0.0)
            assertEquals(-2.0, c.getDouble(3), 0.0)
            assertEquals(100L, c.getLong(4))
            assertEquals(150.0, c.getDouble(5), 0.0)
            assertEquals("home", c.getString(6))
            assertTrue(c.isNull(7))
            assertTrue(c.isNull(8))
            assertTrue(c.moveToNext())
            assertEquals(5L, c.getLong(0))
            assertEquals("Gym", c.getString(1))
            assertTrue(c.isNull(6))
        }
        db.query(
            "SELECT activityType, startedAt, endedAt, source, distanceMeters, pointCount, ignoredCount, " +
                "endLon, startPlaceId, endPlaceId FROM tracks WHERE id = 7",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("WALKING", c.getString(0))
            assertEquals(1000L, c.getLong(1))
            assertEquals(2000L, c.getLong(2))
            assertEquals("recorded", c.getString(3))
            assertEquals(812.5, c.getDouble(4), 0.0)
            assertEquals(40, c.getInt(5))
            assertEquals(2, c.getInt(6))
            assertEquals(-2.01, c.getDouble(7), 0.0)
            assertTrue(c.isNull(8))
            assertTrue(c.isNull(9))
        }
    }

    @Test fun `deleting a place clears the track ends stated to it`() {
        insertV19Rows()
        AppDatabase.MIGRATION_19_20.migrate(db)
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("UPDATE tracks SET startPlaceId = 3, endPlaceId = 5 WHERE id = 7")

        db.execSQL("DELETE FROM places WHERE id = 3")

        db.query("SELECT startPlaceId, endPlaceId FROM tracks WHERE id = 7").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0))
            assertEquals(5L, c.getLong(1))
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
        AppDatabase.MIGRATION_19_20.migrate(db)

        val room = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val generated = room.openHelper.writableDatabase
            for (table in listOf("places", "tracks", "derived_intervals")) {
                assertEquals(TableInfo.read(generated, table), TableInfo.read(db, table))
            }
        } finally {
            room.close()
        }
    }
}
