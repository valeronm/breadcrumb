package io.github.valeronm.breadcrumb.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Track::class, TrackPoint::class, Place::class, PlaceIdentity::class,
        DerivedCluster::class, ClusterMember::class, DerivedInterval::class,
    ],
    version = 21,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun placeDao(): PlaceDao
    abstract fun placeIdentityDao(): PlaceIdentityDao
    abstract fun derivedDao(): DerivedDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /**
         * v19 retires the liveness log: the `liveness_events` table goes, and `derived_intervals`
         * loses its `provenance` column — observed/inferred described the app's attention, not
         * where anyone was, and nothing rendered it. The trailing stay's disarm bound, the one
         * user-visible fact the log fed, now lives as a timestamp in [Settings]. The log's own
         * trailing disarm is deliberately not carried into that timestamp: an install upgrading
         * while disarmed shows its trailing stay open until the next disarm writes one, which was
         * judged cheaper than a one-shot backfill for a value the next toggle re-establishes.
         * minSdk 26's SQLite
         * predates `ALTER TABLE … DROP COLUMN`, so the table is rebuilt and its rows copied — they
         * are unchanged by the column going, so no re-derivation follows.
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS liveness_events")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS derived_intervals_new (
                      type TEXT NOT NULL,
                      start INTEGER NOT NULL,
                      endedAt INTEGER NOT NULL,
                      afterTrackId INTEGER NOT NULL,
                      clusterId INTEGER,
                      reason TEXT,
                      fromClusterId INTEGER, toClusterId INTEGER,
                      fromLat REAL, fromLon REAL,
                      toLat REAL, toLon REAL,
                      PRIMARY KEY(afterTrackId) )
                    """,
                )
                db.execSQL(
                    """
                    INSERT INTO derived_intervals_new
                        (type, start, endedAt, afterTrackId, clusterId, reason,
                         fromClusterId, toClusterId, fromLat, fromLon, toLat, toLon)
                    SELECT type, start, endedAt, afterTrackId, clusterId, reason,
                           fromClusterId, toClusterId, fromLat, fromLon, toLat, toLon
                    FROM derived_intervals
                    """,
                )
                db.execSQL("DROP TABLE derived_intervals")
                db.execSQL("ALTER TABLE derived_intervals_new RENAME TO derived_intervals")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_derived_intervals_start " +
                        "ON derived_intervals(start)",
                )
            }
        }

        /**
         * A place may have no name and may carry where it came from; a track's ends may be stated
         * to a place. minSdk 26's SQLite cannot relax a NOT NULL in place, so `places` is rebuilt;
         * the track columns are nullable references, which `ADD COLUMN` accepts.
         */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS places_new (
                      id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                      label TEXT,
                      lat REAL NOT NULL,
                      lon REAL NOT NULL,
                      createdAt INTEGER NOT NULL,
                      radiusM REAL NOT NULL,
                      category TEXT,
                      externalProvider TEXT,
                      externalId TEXT )
                    """,
                )
                db.execSQL(
                    "INSERT INTO places_new (id, label, lat, lon, createdAt, radiusM, category) " +
                        "SELECT id, label, lat, lon, createdAt, radiusM, category FROM places",
                )
                db.execSQL("DROP TABLE places")
                db.execSQL("ALTER TABLE places_new RENAME TO places")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_places_externalProvider_externalId " +
                        "ON places(externalProvider, externalId)",
                )
                for (column in listOf("startPlaceId", "endPlaceId")) {
                    db.execSQL(
                        "ALTER TABLE tracks ADD COLUMN $column INTEGER " +
                            "REFERENCES places(id) ON UPDATE NO ACTION ON DELETE SET NULL",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_$column ON tracks($column)")
                }
            }
        }

        /**
         * A place's identities at other sources move to `place_identities`, where a place can hold
         * several and a merge can hand them on, and `places` names who last wrote the row instead.
         * The migration runs before Room turns foreign keys on, so rebuilding `places` leaves the
         * track ends stated to it in place.
         */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS place_identities (
                      provider TEXT NOT NULL,
                      externalId TEXT NOT NULL,
                      placeId INTEGER NOT NULL,
                      PRIMARY KEY(provider, externalId),
                      FOREIGN KEY(placeId) REFERENCES places(id) ON UPDATE NO ACTION ON DELETE CASCADE )
                    """,
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_place_identities_placeId ON place_identities(placeId)")
                db.execSQL(
                    "INSERT INTO place_identities (provider, externalId, placeId) " +
                        "SELECT externalProvider, externalId, id FROM places " +
                        "WHERE externalProvider IS NOT NULL AND externalId IS NOT NULL",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS places_new (
                      id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                      label TEXT,
                      lat REAL NOT NULL,
                      lon REAL NOT NULL,
                      createdAt INTEGER NOT NULL,
                      radiusM REAL NOT NULL,
                      category TEXT,
                      source TEXT )
                    """,
                )
                // The Google Timeline import is the only writer that ever set a provider.
                db.execSQL(
                    "INSERT INTO places_new (id, label, lat, lon, createdAt, radiusM, category, source) " +
                        "SELECT id, label, lat, lon, createdAt, radiusM, category, " +
                        "CASE WHEN externalProvider = 'google' THEN 'google_timeline' ELSE 'manual' END FROM places",
                )
                db.execSQL("DROP TABLE places")
                db.execSQL("ALTER TABLE places_new RENAME TO places")
            }
        }

        /**
         * The list the builder spreads, in order; the next migration is appended here.
         *
         * v18 is the floor: a database older than that fails to open rather than migrating.
         * Deliberately — the crash leaves `tracks.db` intact for the previous build to read and
         * export, where a destructive fallback would discard a history nothing else holds. The
         * `version` above never renumbers downward for the same reason, since every installed
         * database would then present itself as a downgrade.
         */
        private val MIGRATIONS = arrayOf(MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21)

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tracks.db",
                ).addMigrations(*MIGRATIONS).build().also { instance = it }
            }
    }
}
