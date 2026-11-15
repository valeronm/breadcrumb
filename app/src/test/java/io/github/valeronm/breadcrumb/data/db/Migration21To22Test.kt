package io.github.valeronm.breadcrumb.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** v22 adds the user's statement that the stay before or after a track is hidden. */
@RunWith(RobolectricTestRunner::class)
class Migration21To22Test {

    /** A v21 database: the tables v22 touches. Hand-written and frozen from `21.json`. */
    private val fixture = MigrationDb(21) { db ->
        db.execSQL(
            "CREATE TABLE places (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, label TEXT, " +
                "lat REAL NOT NULL, lon REAL NOT NULL, createdAt INTEGER NOT NULL, radiusM REAL NOT NULL, " +
                "category TEXT, source TEXT)",
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

    @Test fun `existing tracks come across hiding no stay`() {
        db.execSQL(
            "INSERT INTO tracks (id, activityType, startedAt, endedAt, distanceMeters, pointCount, " +
                "ignoredCount, needsReview) VALUES (7, 'WALKING', 1000, 2000, 812.5, 40, 2, 0)",
        )

        AppDatabase.MIGRATION_21_22.migrate(db)

        db.query("SELECT hidesStayBefore, hidesStayAfter FROM tracks WHERE id = 7").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
            assertEquals(0, c.getInt(1))
        }
    }
}
