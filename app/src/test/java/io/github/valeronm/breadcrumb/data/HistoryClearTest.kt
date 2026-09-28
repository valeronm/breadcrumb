package io.github.valeronm.breadcrumb.data

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HistoryClearTest {

    private val test = TestDb()
    private val repository get() = test.repository

    @After fun tearDown() = test.close()

    @Test fun `clearing the history empties every table it lives in`() = runTest {
        val deleted = test.walk(TEST_START, 0, 5)
        test.walk(TEST_START + 120_000L, 6, 11)
        repository.deleteTrack(deleted)
        test.db.placeDao().insert(test.place("Home", 1.0, -2.0))

        repository.clearHistory()

        for (table in listOf("tracks", "track_points", "places", "derived_clusters", "cluster_members", "derived_intervals")) {
            test.db.query("SELECT COUNT(*) FROM $table", null).use { cursor ->
                cursor.moveToFirst()
                assertEquals(table, 0, cursor.getInt(0))
            }
        }
    }
}
