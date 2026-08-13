package com.example.trex_kotlin.store

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The store sits on the launch path, so its contract is mostly about what it refuses to do:
 * it must not throw, must not leave a half-written file behind, and must not let a damaged or
 * oversized file decide how much the launch path allocates.
 */
class FileTrexStoreTest {

    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = File.createTempFile("trex-store-test", "").let { probe ->
            probe.delete()
            probe.mkdirs()
            probe
        }
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun storeFile() = File(directory, TREX_STORE_FILE_NAME)

    private fun snapshot() = TrexSnapshot(
        guideDone = true,
        loggedIn = true,
        onboarded = true,
        plan = listOf(
            PersistedWorkout("id", "PLANK", "60초 x 3세트", "5분", PersistedCameraMode.Guide, null, null),
        ),
        history = listOf(
            PersistedHistoryDay(20_678L, 5, 30, listOf(PersistedHistoryItem("PLANK", "60초", 5, 30))),
        ),
    )

    @Test
    fun aSavedSnapshotLoadsBack() {
        val store = FileTrexStore(storeFile())

        store.save(snapshot())

        assertEquals(snapshot(), FileTrexStore(storeFile()).load())
    }

    @Test
    fun anAbsentFileIsAFirstLaunchNotAnError() {
        assertNull(FileTrexStore(storeFile()).load())
    }

    @Test
    fun aSaveLeavesNoTemporaryFileBehind() {
        val store = FileTrexStore(storeFile())

        store.save(snapshot())

        val leftovers = directory.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }
        assertTrue("Temporary files left behind: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun aSaveOverAnExistingFileReplacesItRatherThanFailing() {
        val store = FileTrexStore(storeFile())
        store.save(snapshot())

        val second = snapshot().copy(loggedIn = false)
        store.save(second)

        assertEquals(second, store.load())
    }

    @Test
    fun garbageBytesLoadAsNullRatherThanThrowing() {
        storeFile().writeBytes(ByteArray(512) { (it % 251).toByte() })

        assertNull(FileTrexStore(storeFile()).load())
    }

    @Test
    fun aFilePastTheCapIsRefusedWithoutBeingParsed() {
        val store = FileTrexStore(storeFile(), maxBytes = 64)
        store.save(snapshot())

        assertTrue("Fixture must exceed the cap", storeFile().length() > 64)
        assertNull(store.load())
    }

    @Test
    fun aFileUnderTheCapIsStillRead() {
        val store = FileTrexStore(storeFile(), maxBytes = 1_000_000)
        store.save(snapshot())

        assertNotNull(store.load())
    }

    @Test
    fun aDirectoryThatDoesNotExistYetIsCreated() {
        val nested = File(directory, "nested/deeper/${TREX_STORE_FILE_NAME}")
        val store = FileTrexStore(nested)

        store.save(snapshot())

        assertTrue(nested.isFile)
        assertEquals(snapshot(), store.load())
    }

    @Test
    fun concurrentSavesLeaveAWholeFileNotAShreddedOne() {
        // Three writers overlap in the app: the debounced collector, the session-completion flush
        // and the pause flush. Without synchronisation their temp-file dance interleaves and the
        // rename can land on a file another thread is still writing.
        val store = FileTrexStore(storeFile())
        val writers = 8
        val start = CountDownLatch(1)
        val done = CountDownLatch(writers)
        val failures = mutableListOf<Throwable>()

        repeat(writers) { index ->
            Thread {
                try {
                    start.await()
                    repeat(20) { store.save(snapshot().copy(loggedIn = index % 2 == 0)) }
                } catch (error: Throwable) {
                    synchronized(failures) { failures += error }
                } finally {
                    done.countDown()
                }
            }.start()
        }

        start.countDown()
        assertTrue("Writers did not finish", done.await(30, TimeUnit.SECONDS))
        assertTrue("Writers threw: $failures", failures.isEmpty())
        assertNotNull("Concurrent saves produced an unreadable file", store.load())
        assertFalse(
            "Temporary files left behind",
            directory.listFiles().orEmpty().any { it.name.endsWith(".tmp") },
        )
    }

    @Test
    fun theInMemoryStoreStandsInForTheRealOne() {
        val store = InMemoryTrexStore()

        assertNull(store.load())
        store.save(snapshot())

        assertEquals(snapshot(), store.load())
        assertEquals(1, store.saveCount)
    }
}
