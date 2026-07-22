package io.github.valeronm.breadcrumb.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Track::class, TrackPoint::class, LivenessEvent::class, Place::class],
    version = 13,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun livenessDao(): LivenessDao
    abstract fun placeDao(): PlaceDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        // v2 adds the `ignored` bad-fix flag. The column defaults to 0 and is set live as points are
        // recorded (the recorder runs the bad-fix rule on each fix); pre-v2 points stay unflagged.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE track_points ADD COLUMN ignored INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v3 adds the `segmentStart` flag marking auto-pause/resume boundaries (GPX <trkseg>). The
        // column defaults to 0 and is set live as points are recorded (the recorder flags the first
        // fix after a resume); pre-v3 tracks simply have no segment breaks.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE track_points ADD COLUMN segmentStart INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v4 adds per-point fix-quality metadata (accuracy siblings + GNSS satellite/signal info).
        // All nullable — the recorder fills them live as points are recorded; pre-v4 points stay null.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE track_points ADD COLUMN verticalAccuracy REAL")
                db.execSQL("ALTER TABLE track_points ADD COLUMN speedAccuracy REAL")
                db.execSQL("ALTER TABLE track_points ADD COLUMN bearingAccuracy REAL")
                db.execSQL("ALTER TABLE track_points ADD COLUMN satellitesInFix INTEGER")
                db.execSQL("ALTER TABLE track_points ADD COLUMN cn0 REAL")
                db.execSQL("ALTER TABLE track_points ADD COLUMN provider TEXT")
            }
        }

        // v5 adds the `ignoreReason` code saying which rule flagged an ignored point. Nullable —
        // set live as points are recorded; pre-v5 ignored points stay null ("unknown reason").
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE track_points ADD COLUMN ignoreReason TEXT")
            }
        }

        // v6 adds the liveness_events table (recorder-lifecycle evidence for stay derivation) and
        // upgrades the track_points FK index to (trackId, timestamp) so the first/last-endpoint
        // subqueries walk the index. DDL must match the entity annotations exactly — Room
        // validates the schema at open and crashes on mismatch.
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS liveness_events (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "type TEXT NOT NULL, at INTEGER NOT NULL, until INTEGER)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_liveness_events_at ON liveness_events(at)",
                )
                db.execSQL("DROP INDEX IF EXISTS index_track_points_trackId")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_track_points_trackId_timestamp " +
                        "ON track_points(trackId, timestamp)",
                )
            }
        }

        // v7 adds the places table — user-assigned labels for recurring stay locations.
        // Everything else about places (clustering, visit counts) stays derived on read.
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS places (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "label TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, " +
                        "createdAt INTEGER NOT NULL)",
                )
            }
        }

        // v8 adds the per-place capture radius (default 150 m, matching the organic cluster
        // radius); users widen it for big venues whose GPS scatter exceeds the default.
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE places ADD COLUMN radiusM REAL NOT NULL DEFAULT 150.0")
            }
        }

        // Soft-delete for keep-threshold-filtered tracks: null = kept, timestamp = discarded.
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN discardedAt INTEGER")
            }
        }

        // v10 adds why a track was discarded ("deleted" | "filtered" | "merged") for the
        // Recently deleted screen. Nullable — pre-v10 rows show without a reason.
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN discardReason TEXT")
            }
        }

        /**
         * v11 denormalizes each track's point aggregates (counts + first/last good coordinates)
         * onto its row, so the timeline queries stop reading `track_points` — see [TrackDao]'s
         * observed queries. The backfill is SQL rather than a Kotlin pass ([TrackStats]) because it
         * has to be atomic with the schema change: a migrated-but-unfilled row would show a
         * finished track with no points and no endpoints, dropping it out of the timeline and the
         * stay derivation until the pass caught up. `distanceMeters` needs no backfill — it was
         * already stored, and the SQL couldn't reproduce its great-circle walk anyway.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN pointCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tracks ADD COLUMN ignoredCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tracks ADD COLUMN startLat REAL")
                db.execSQL("ALTER TABLE tracks ADD COLUMN startLon REAL")
                db.execSQL("ALTER TABLE tracks ADD COLUMN endLat REAL")
                db.execSQL("ALTER TABLE tracks ADD COLUMN endLon REAL")
                db.execSQL(
                    """
                    UPDATE tracks SET
                      pointCount = (SELECT COUNT(*) FROM track_points p
                                     WHERE p.trackId = tracks.id AND p.ignored = 0),
                      ignoredCount = (SELECT COUNT(*) FROM track_points p
                                       WHERE p.trackId = tracks.id AND p.ignored = 1),
                      startLat = (SELECT p.latitude FROM track_points p
                                   WHERE p.trackId = tracks.id AND p.ignored = 0
                                   ORDER BY p.timestamp ASC, p.id ASC LIMIT 1),
                      startLon = (SELECT p.longitude FROM track_points p
                                   WHERE p.trackId = tracks.id AND p.ignored = 0
                                   ORDER BY p.timestamp ASC, p.id ASC LIMIT 1),
                      endLat = (SELECT p.latitude FROM track_points p
                                 WHERE p.trackId = tracks.id AND p.ignored = 0
                                 ORDER BY p.timestamp DESC, p.id DESC LIMIT 1),
                      endLon = (SELECT p.longitude FROM track_points p
                                 WHERE p.trackId = tracks.id AND p.ignored = 0
                                 ORDER BY p.timestamp DESC, p.id DESC LIMIT 1)
                    """,
                )
            }
        }

        /**
         * v12 drops `track_points.provider`: with the fused path removed there is only one live
         * source (raw GPS), so the column carried no information. minSdk 26's SQLite predates
         * `ALTER TABLE … DROP COLUMN`, so the table is rebuilt — new table, copy, drop, rename —
         * and the composite index is recreated. The DDL must match the entity annotations exactly
         * (Room validates the schema at open and crashes on mismatch).
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE track_points_new (
                      id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, trackId INTEGER NOT NULL,
                      latitude REAL NOT NULL, longitude REAL NOT NULL, altitude REAL, accuracy REAL,
                      speed REAL, bearing REAL, timestamp INTEGER NOT NULL, verticalAccuracy REAL,
                      speedAccuracy REAL, bearingAccuracy REAL, satellitesInFix INTEGER, cn0 REAL,
                      ignored INTEGER NOT NULL DEFAULT 0, ignoreReason TEXT,
                      segmentStart INTEGER NOT NULL DEFAULT 0,
                      FOREIGN KEY(trackId) REFERENCES tracks(id)
                        ON UPDATE NO ACTION ON DELETE CASCADE)
                    """,
                )
                db.execSQL(
                    """
                    INSERT INTO track_points_new
                        (id, trackId, latitude, longitude, altitude, accuracy, speed, bearing,
                         timestamp, verticalAccuracy, speedAccuracy, bearingAccuracy,
                         satellitesInFix, cn0, ignored, ignoreReason, segmentStart)
                    SELECT id, trackId, latitude, longitude, altitude, accuracy, speed, bearing,
                           timestamp, verticalAccuracy, speedAccuracy, bearingAccuracy,
                           satellitesInFix, cn0, ignored, ignoreReason, segmentStart
                    FROM track_points
                    """,
                )
                db.execSQL("DROP TABLE track_points")
                db.execSQL("ALTER TABLE track_points_new RENAME TO track_points")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_track_points_trackId_timestamp " +
                        "ON track_points(trackId, timestamp)",
                )
            }
        }

        /**
         * v13 adds `tracks.needsReview`: one boolean saying a cut on this track is waiting on the
         * user. Deliberately a plain flag rather than a measurement — it answers "is there a
         * decision pending here", and the screen recomputes the detail when opened.
         *
         * Nothing writes it today: the edge stay it was built for stopped needing confirmation
         * once the overrun became a flag on the points rather than a destructive cut. The one
         * release that did write it left marks behind on installed devices, and they were never
         * cleared — harmless, because nothing reads the column, and the next feature to claim it
         * derives its own verdict rather than trusting a stored one. Kept for the mid-track dwell
         * split; [Track.needsReview] carries the full account.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN needsReview INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tracks.db",
                ).addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                    MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
                    MIGRATION_11_12, MIGRATION_12_13,
                ).build().also { instance = it }
            }
    }
}
