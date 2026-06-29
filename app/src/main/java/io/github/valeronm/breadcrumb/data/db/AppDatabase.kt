package io.github.valeronm.breadcrumb.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Track::class, TrackPoint::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        // v2 adds the `ignored` bad-fix flag. The column defaults to 0; existing points are then
        // reprocessed in Kotlin (TrackRepository.reprocessAllTracks) to backfill the real flags and
        // recompute distances, since the rule needs per-track haversine the migration SQL can't do.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE track_points ADD COLUMN ignored INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tracks.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
