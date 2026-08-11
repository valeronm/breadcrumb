package io.github.valeronm.breadcrumb.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Track::class, TrackPoint::class, LivenessEvent::class, Place::class,
        DerivedCluster::class, ClusterMember::class, DerivedInterval::class,
    ],
    version = 18,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun livenessDao(): LivenessDao
    abstract fun placeDao(): PlaceDao
    abstract fun derivedDao(): DerivedDao

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
         * onto its row, so the timeline queries stop reading `track_points` ([TrackDao]'s observed
         * queries). The backfill is SQL rather than a Kotlin pass ([TrackStats]) to be atomic with
         * the schema change: a migrated-but-unfilled row would show a finished track with no points
         * or endpoints, out of the timeline and stay derivation until the pass caught up.
         * `distanceMeters` needs no backfill: already stored, and SQL can't do its great-circle walk.
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
         * v12 drops `track_points.provider` (the fused path gone, raw GPS is the only live source,
         * the column carried no information). What it could still have told apart — a recorded fix
         * from an imported one — the fixes answer without it, by whether they carry fix-quality
         * metadata at all; that is the rule the v15 `source` fill runs. minSdk 26's SQLite predates `ALTER TABLE … DROP
         * COLUMN`, so the table is rebuilt (new table, copy, drop, rename) and the composite index
         * recreated; the DDL must match the entity annotations exactly or Room's schema validation
         * crashes at open.
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
         * user — deliberately a plain flag, not a measurement: it answers "is a decision pending
         * here", and the screen recomputes the detail when opened. Nothing writes it today: the
         * edge stay it was built for stopped needing confirmation once the overrun became a flag
         * on the points rather than a destructive cut. The one release that wrote it left marks
         * on installed devices, never cleared — harmless, since nothing reads the column, and the
         * next feature to claim it derives its own verdict rather than trusting a stored one. Kept
         * for the mid-track dwell split; [Track.needsReview] carries the full account.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN needsReview INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v14 adds a place's category (`PlaceCategory.code`). Nullable with no default: untagged is
        // a real state, so every existing place stays untagged until the user says otherwise.
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE places ADD COLUMN category TEXT")
            }
        }

        /**
         * v15 adds `tracks.source` (`TrackOrigin.code`), declared from here on by whoever inserts a
         * track. Existing rows never declared one, so they are filled here from the only surviving
         * trace — the recorder stores an accuracy radius, a parsed GPX has none — which is
         * `TrackOrigin.inferFrom` expressed in SQL.
         *
         * The fill runs **in the migration rather than as a backfill pass** so the column is never
         * half-written: with a later pass, null would mean both "unknown" and "not computed yet",
         * and every reader in between would have to guess which. The correlated `EXISTS` rides
         * `index_track_points_trackId_timestamp` and stops at a recorded track's first point;
         * measured at ~0.5 s over a 6-million-point history on a dev box, so a few seconds at DB
         * open once. A track with no points at all declares nothing and stays null.
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN source TEXT")
                db.execSQL(
                    """
                    UPDATE tracks SET source = CASE
                      WHEN EXISTS(SELECT 1 FROM track_points p
                                  WHERE p.trackId = tracks.id AND p.accuracy IS NOT NULL)
                        THEN 'recorded'
                      WHEN EXISTS(SELECT 1 FROM track_points p WHERE p.trackId = tracks.id)
                        THEN 'imported'
                      ELSE NULL END
                    """,
                )
            }
        }

        /**
         * v16 adds the derived stay/place tables. DDL only, and they start **empty**: what belongs
         * in them is a function of the tracks, the places and the liveness log already stored, so it
         * is derived on the next launch rather than carried across by hand. A half-filled set of
         * rows would be worse than none, nothing here distinguishing a row not yet written from one
         * whose inputs said nothing.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS derived_clusters (
                      id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                      placeId INTEGER,
                      anchorLat REAL NOT NULL, anchorLon REAL NOT NULL,
                      radiusM REAL NOT NULL,
                      sumLat REAL NOT NULL, sumLon REAL NOT NULL,
                      memberCount INTEGER NOT NULL)
                    """,
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_derived_clusters_placeId " +
                        "ON derived_clusters(placeId)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS cluster_members (
                      id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                      clusterId INTEGER NOT NULL,
                      trackId INTEGER NOT NULL,
                      isStart INTEGER NOT NULL,
                      lat REAL NOT NULL, lon REAL NOT NULL,
                      atMs INTEGER NOT NULL,
                      FOREIGN KEY(clusterId) REFERENCES derived_clusters(id)
                        ON UPDATE NO ACTION ON DELETE CASCADE )
                    """,
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_cluster_members_trackId_isStart " +
                        "ON cluster_members(trackId, isStart)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_cluster_members_clusterId " +
                        "ON cluster_members(clusterId)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS derived_intervals (
                      id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                      type TEXT NOT NULL,
                      start INTEGER NOT NULL,
                      endedAt INTEGER NOT NULL,
                      afterTrackId INTEGER NOT NULL,
                      provenance TEXT,
                      clusterId INTEGER,
                      reason TEXT,
                      fromClusterId INTEGER, toClusterId INTEGER,
                      fromLat REAL, fromLon REAL,
                      toLat REAL, toLon REAL)
                    """,
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_derived_intervals_afterTrackId " +
                        "ON derived_intervals(afterTrackId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_derived_intervals_start " +
                        "ON derived_intervals(start)",
                )
            }
        }

        /**
         * v17 keys the two derived tables on what already identified a row and indexes `startedAt`.
         *
         * A membership *is* a track's start or its end, and an interval *is* what follows a track,
         * so each carried a generated id beside a unique index over the columns that said the same
         * thing — a second index over the largest of these tables, maintained on every rebuild, for
         * a column nothing read. SQLite cannot re-key a table in place, and these rows are output:
         * the two are dropped and recreated empty, as v16 first created them.
         *
         * [DerivationStore.LOGIC_VERSION] moves with this, which is what refills them — the seed
         * clusters are emptied rather than dropped, so a history with no named place still has a
         * reason to derive.
         *
         * `tracks(startedAt)` is the index the repair's two neighbour lookups want: each is an
         * `ORDER BY startedAt LIMIT 1`, run inside the transaction that finishes a track, and
         * without it each scans every track in the history. Its write cost lands per track row,
         * which is per finish, never per fix.
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS cluster_members")
                db.execSQL("DROP TABLE IF EXISTS derived_intervals")
                db.execSQL("DELETE FROM derived_clusters")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS cluster_members (
                      clusterId INTEGER NOT NULL,
                      trackId INTEGER NOT NULL,
                      isStart INTEGER NOT NULL,
                      lat REAL NOT NULL, lon REAL NOT NULL,
                      atMs INTEGER NOT NULL,
                      PRIMARY KEY(trackId, isStart),
                      FOREIGN KEY(clusterId) REFERENCES derived_clusters(id)
                        ON UPDATE NO ACTION ON DELETE CASCADE )
                    """,
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_cluster_members_clusterId " +
                        "ON cluster_members(clusterId)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS derived_intervals (
                      type TEXT NOT NULL,
                      start INTEGER NOT NULL,
                      endedAt INTEGER NOT NULL,
                      afterTrackId INTEGER NOT NULL,
                      provenance TEXT,
                      clusterId INTEGER,
                      reason TEXT,
                      fromClusterId INTEGER, toClusterId INTEGER,
                      fromLat REAL, fromLon REAL,
                      toLat REAL, toLon REAL,
                      PRIMARY KEY(afterTrackId) )
                    """,
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_derived_intervals_start " +
                        "ON derived_intervals(start)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_tracks_startedAt ON tracks(startedAt)",
                )
            }
        }

        /**
         * v18 indexes `liveness_events(type, at)`, which is what makes reading the log over one
         * stretch of time cheaper than reading all of it — [LivenessDao.eventsAround] is the query
         * it exists for and says why. DDL only; no row is read or written.
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_liveness_events_type_at " +
                        "ON liveness_events(type, at)",
                )
            }
        }

        /**
         * Every migration, in order — **one list, because two would be kept in step by hand.** The
         * builder spreads it, and `SchemaMatchesEntitiesTest` walks it to check the schema the chain
         * reaches is the one Room builds from the entities, which is the check a real upgrade makes
         * on its first open. A migration appended here therefore joins both at once; appended to
         * only one of two lists, it would either never run or never be checked.
         */
        val MIGRATIONS = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
            MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
            MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15,
            MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18,
        )

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
