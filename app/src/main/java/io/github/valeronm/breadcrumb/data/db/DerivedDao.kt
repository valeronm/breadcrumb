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

    @Query("SELECT * FROM cluster_members ORDER BY atMs ASC")
    suspend fun membersOnce(): List<ClusterMember>

    /** The stored endpoints of a handful of tracks — what a repair has to take back. */
    @Query("SELECT * FROM cluster_members WHERE trackId IN (:trackIds) ORDER BY atMs ASC")
    suspend fun membersForTracks(trackIds: List<Long>): List<ClusterMember>

    @Query("UPDATE derived_clusters SET sumLat = :sumLat, sumLon = :sumLon, memberCount = :count WHERE id = :id")
    suspend fun setClusterMembership(id: Long, sumLat: Double, sumLon: Double, count: Int)

    /** Move a seed cluster onto its place's circle. The membership columns are left alone: the
     *  rebuild that must follow rewrites them from the derivation this move changes. */
    @Query(
        "UPDATE derived_clusters SET anchorLat = :anchorLat, anchorLon = :anchorLon, " +
            "radiusM = :radiusM WHERE id = :id",
    )
    suspend fun setClusterSeed(id: Long, anchorLat: Double, anchorLon: Double, radiusM: Double)

    /**
     * Move a cluster's membership by a difference rather than to a value — the repair knows only
     * what joined and what left, and reading the row to add to it would be a read the writer of the
     * next repair could interleave with.
     */
    @Query(
        "UPDATE derived_clusters SET sumLat = sumLat + :sumLat, sumLon = sumLon + :sumLon, " +
            "memberCount = memberCount + :count WHERE id = :id",
    )
    suspend fun shiftClusterMembership(id: Long, sumLat: Double, sumLon: Double, count: Int)

    /**
     * The stored intervals a window could bear on — every row that overlaps it, whatever its type.
     * Deliberately a *candidate* filter and not a rule: what an overlap means for a stay's
     * provenance is the domain's to say, and a query that decided it here would be that rule's
     * second author.
     */
    @Query("SELECT * FROM derived_intervals WHERE start < :until AND endedAt > :at")
    suspend fun intervalsOverlapping(at: Long, until: Long): List<DerivedInterval>

    @Query("UPDATE derived_intervals SET provenance = :provenance WHERE id = :id")
    suspend fun setIntervalProvenance(id: Long, provenance: String)

    @Query("DELETE FROM derived_intervals WHERE afterTrackId IN (:trackIds)")
    suspend fun deleteIntervalsAfter(trackIds: List<Long>)

    @Query("DELETE FROM cluster_members WHERE trackId IN (:trackIds)")
    suspend fun deleteMembersOf(trackIds: List<Long>)

    @Query("DELETE FROM derived_clusters WHERE id IN (:ids)")
    suspend fun deleteClusters(ids: List<Long>)

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
