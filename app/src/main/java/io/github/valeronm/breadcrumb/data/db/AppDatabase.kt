package io.github.valeronm.breadcrumb.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Track::class, TrackPoint::class, LivenessEvent::class, Place::class],
    version = 9,
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

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tracks.db",
                ).addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                    MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                ).build().also { instance = it }
            }
    }
}
