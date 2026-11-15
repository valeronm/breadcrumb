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

/** v23 adds the step counter's running total to each fix. */
@RunWith(RobolectricTestRunner::class)
class Migration22To23Test {

    /** A v22 database: the tables v23 touches. Hand-written and frozen from `22.json`. */
    private val fixture = MigrationDb(22) { db ->
        db.execSQL(
            "CREATE TABLE tracks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "activityType TEXT NOT NULL, startedAt INTEGER NOT NULL, endedAt INTEGER, source TEXT, " +
                "distanceMeters REAL NOT NULL, pointCount INTEGER NOT NULL, ignoredCount INTEGER NOT NULL, " +
                "startLat REAL, startLon REAL, endLat REAL, endLon REAL, discardedAt INTEGER, " +
                "discardReason TEXT, needsReview INTEGER NOT NULL, startPlaceId INTEGER, endPlaceId INTEGER, " +
                "hidesStayBefore INTEGER NOT NULL DEFAULT 0, hidesStayAfter INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL(
            "CREATE TABLE track_points (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "trackId INTEGER NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, altitude REAL, " +
                "accuracy REAL, speed REAL, bearing REAL, timestamp INTEGER NOT NULL, verticalAccuracy REAL, " +
                "speedAccuracy REAL, bearingAccuracy REAL, satellitesInFix INTEGER, cn0 REAL, " +
                "ignored INTEGER NOT NULL, ignoreReason TEXT, segmentStart INTEGER NOT NULL, " +
                "FOREIGN KEY(trackId) REFERENCES tracks(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL("CREATE INDEX index_track_points_trackId_timestamp ON track_points(trackId, timestamp)")
    }
    private val db: SupportSQLiteDatabase get() = fixture.db

    @After fun tearDown() = fixture.close()

    @Test fun `existing fixes come across with no step total`() {
        db.execSQL(
            "INSERT INTO tracks (id, activityType, startedAt, endedAt, distanceMeters, pointCount, " +
                "ignoredCount, needsReview) VALUES (7, 'WALKING', 1000, 2000, 812.5, 1, 0, 0)",
        )
        db.execSQL(
            "INSERT INTO track_points (id, trackId, latitude, longitude, timestamp, ignored, segmentStart) " +
                "VALUES (3, 7, 0.001, 0.002, 1500, 0, 1)",
        )

        AppDatabase.MIGRATION_22_23.migrate(db)

        db.query("SELECT steps, latitude, timestamp, segmentStart FROM track_points WHERE id = 3").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0))
            assertEquals(0.001, c.getDouble(1), 0.0)
            assertEquals(1500L, c.getLong(2))
            assertEquals(1, c.getInt(3))
        }
    }

    /**
     * **The guard a real upgrade is exposed to**, and this is where it belongs: Room compares what
     * it finds against its entities on the first open after an upgrade, and only the *end* of the
     * chain is ever compared that way — which is here. The case above reads values, and so cannot
     * see a nullability, a column type, a default, a foreign key or an index; those live in the
     * hand-written DDL the chain runs, and get someone a crash on open rather than a failure here.
     * [TableInfo] is the shape Room compares, rather than the `CREATE` text, so formatting is not
     * mistaken for drift.
     *
     * Move it into the next migration's test when one lands, for the same reason it sits here.
     */
    @Suppress("DEPRECATION")
    @Test
    fun `the migrated tables are the shape Room builds from the entities`() {
        AppDatabase.MIGRATION_22_23.migrate(db)

        val room = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val generated = room.openHelper.writableDatabase
            assertEquals(TableInfo.read(generated, "track_points"), TableInfo.read(db, "track_points"))
        } finally {
            room.close()
        }
    }
}
