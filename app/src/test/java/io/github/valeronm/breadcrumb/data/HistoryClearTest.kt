package io.github.valeronm.breadcrumb.data

import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.data.db.PlaceIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HistoryClearTest {

    private val test = TestDb()
    private val repository get() = test.repository

    @After fun tearDown() = test.close()

    @Test fun `clearing the history empties every table in the database`() = runBlocking {
        fillEveryTable()
        val tables = tables()
        for (table in tables) assertTrue("$table holds a row before the clear", long("SELECT COUNT(*) FROM `$table`") > 0)

        repository.clearHistory()

        for (table in tables) assertEquals(table, 0L, long("SELECT COUNT(*) FROM `$table`"))
        assertEquals("foreign keys enforced again", 1L, long("PRAGMA foreign_keys"))
    }

    @Test fun `an observer sees the history cleared`() = runBlocking {
        fillEveryTable()
        val scope = CoroutineScope(Dispatchers.IO)
        val anyRows = repository.observeAnyRows().stateIn(scope)
        withTimeout(TIMEOUT_MS) { anyRows.first { it } }

        repository.clearHistory()

        withTimeout(TIMEOUT_MS) { anyRows.first { !it } }
        scope.cancel()
    }

    private suspend fun fillEveryTable() {
        val deleted = test.walk(TEST_START, 0, 5)
        test.walk(TEST_START + 120_000L, 6, 11)
        test.walk(TEST_START + 240_000L, 11, 6)
        repository.deleteTrack(deleted)
        val home = test.db.placeDao().insert(test.place("Home", 1.0, -2.0))
        test.db.placeIdentityDao().upsert(listOf(PlaceIdentity("google_timeline", "home", home)))
        DerivationStore(ApplicationProvider.getApplicationContext(), test.db).reconcile()
    }

    private suspend fun tables(): List<String> = test.db.useReaderConnection { connection ->
        connection.usePrepared(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT IN ('android_metadata', 'room_master_table')",
        ) { statement -> buildList { while (statement.step()) add(statement.getText(0)) } }
    }

    private suspend fun long(sql: String): Long = test.db.useWriterConnection { connection ->
        connection.usePrepared(sql) {
            it.step()
            it.getLong(0)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
