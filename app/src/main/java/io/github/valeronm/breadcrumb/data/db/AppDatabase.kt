package io.github.valeronm.breadcrumb.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Track::class, TrackPoint::class],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao

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

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tracks.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { instance = it }
            }
    }
}
