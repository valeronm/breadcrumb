package io.github.valeronm.breadcrumb.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * The derived stay/place tables — `derived_clusters`, `cluster_members` and `derived_intervals`,
 * and nothing else. Keeping them here rather than on [TrackDao] is what lets a query be added to
 * one without touching what the recorder writes: nothing in this file is on the path a fix takes.
 */
@Dao
interface DerivedDao {

    @Insert
    suspend fun insertCluster(cluster: DerivedCluster): Long

    @Insert
    suspend fun insertMembers(members: List<ClusterMember>)

    @Insert
    suspend fun insertIntervals(intervals: List<DerivedInterval>)

    /** The seed list a rebuild derives from, ordered so a caller can map seed index → row id. */
    @Query("SELECT * FROM derived_clusters WHERE placeId IS NOT NULL ORDER BY id ASC")
    suspend fun namedClusters(): List<DerivedCluster>

    @Query("SELECT * FROM derived_clusters ORDER BY id ASC")
    suspend fun clustersOnce(): List<DerivedCluster>

    @Query("SELECT * FROM derived_intervals ORDER BY start ASC")
    suspend fun intervalsOnce(): List<DerivedInterval>

    @Query("UPDATE derived_clusters SET sumLat = :sumLat, sumLon = :sumLon, memberCount = :count WHERE id = :id")
    suspend fun setClusterMembership(id: Long, sumLat: Double, sumLon: Double, count: Int)

    @Query("DELETE FROM derived_intervals")
    suspend fun deleteAllIntervals()

    @Query("DELETE FROM cluster_members")
    suspend fun deleteAllMembers()

    /**
     * A rebuild's wipe stops here. A named cluster survives with its id because that id is what a
     * stay's place *is* — dropping it and deriving a new one would repoint every stay in the
     * history at a different row while looking like a no-op. Its members go with the rest (the
     * cascade takes an unnamed cluster's), and it is re-counted from the fresh derivation.
     */
    @Query("DELETE FROM derived_clusters WHERE placeId IS NULL")
    suspend fun deleteUnnamedClusters()
}
