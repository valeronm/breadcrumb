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
        AppDatabase.MIGRATION_21_22.migrate(db)

        val room = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val generated = room.openHelper.writableDatabase
            assertEquals(TableInfo.read(generated, "tracks"), TableInfo.read(db, "tracks"))
        } finally {
            room.close()
        }
    }
}
