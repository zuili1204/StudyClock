package com.tiji.studyclock

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tiji.studyclock.data.repo.TimerRepository
import com.tiji.studyclock.domain.timer.FinishedTimer
import com.tiji.studyclock.domain.timer.TimerStart
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimerRepositoryTest {
    private lateinit var file: File
    private lateinit var scope: CoroutineScope
    private lateinit var repository: TimerRepository

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        file = File(context.cacheDir, "timer-${UUID.randomUUID()}.preferences_pb")
        openStore()
    }

    private fun openStore() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repository = TimerRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
    }

    @After
    fun tearDown() {
        runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
        file.delete()
    }

    @Test
    fun runningTimerSurvivesStoreReopen() = runTest {
        assertNull(repository.active.first())
        val start = TimerStart(subjectId = 3, categoryId = 17, startAt = 1_700_000_000_000, startElapsed = 12_345, bootCount = 42)
        repository.saveStart(start)
        assertEquals(start, repository.active.first())
        scope.coroutineContext[Job]!!.cancelAndJoin()
        openStore()
        assertEquals(start, repository.active.first())
    }

    @Test
    fun clearingRemovesEveryField() = runTest {
        repository.saveStart(TimerStart(1, 2, 1_700_000_000_000, 100))
        repository.clear()
        assertNull(repository.active.first())
        repository.saveStart(TimerStart(4, 5, 1_700_000_100_000, 200))
        assertEquals(TimerStart(4, 5, 1_700_000_100_000, 200), repository.active.first())
    }

    @Test
    fun finishedTimerSurvivesStoreReopen() = runTest {
        val finished = FinishedTimer(
            subjectId = 3,
            categoryId = 17,
            startAt = 1_700_000_000_000,
            endAt = 1_700_001_500_000,
            durationSec = 1_500,
            pausedSec = 0,
            pauseCount = 0,
            targetSec = 1_500,
        )
        repository.saveFinished(finished)
        assertNull(repository.active.first())
        assertEquals(finished, repository.timers.first().finished)
        scope.coroutineContext[Job]!!.cancelAndJoin()
        openStore()
        assertEquals(finished, repository.timers.first().finished)
        assertNull(repository.active.first())
    }

    @Test
    fun settlingReplacesRunningTimerWithFinishedResult() = runTest {
        repository.saveStart(TimerStart(3, 17, 1_700_000_000_000, 100, targetSec = 1_500))
        val finished = FinishedTimer(3, 17, 1_700_000_000_000, 1_700_001_500_000, 1_500, targetSec = 1_500)
        repository.saveFinished(finished)
        val stored = repository.timers.first()
        assertNull(stored.active)
        assertEquals(finished, stored.finished)
    }

    @Test
    fun startingANewTimerClearsThePendingResult() = runTest {
        repository.saveFinished(FinishedTimer(3, 17, 1_700_000_000_000, 1_700_001_500_000, 1_500))
        val next = TimerStart(4, 18, 1_700_002_000_000, 300)
        repository.saveStart(next)
        val stored = repository.timers.first()
        assertEquals(next, stored.active)
        assertNull(stored.finished)
    }

    @Test
    fun clearRemovesPendingResultToo() = runTest {
        repository.saveFinished(FinishedTimer(3, 17, 1_700_000_000_000, 1_700_001_500_000, 1_500))
        repository.clear()
        val stored = repository.timers.first()
        assertNull(stored.active)
        assertNull(stored.finished)
    }

    @Test
    fun invalidFinishedKeepsPreviousState() = runTest {
        val running = TimerStart(1, 2, 1_700_000_000_000, 100)
        repository.saveStart(running)
        val invalid = listOf(
            FinishedTimer(0, 2, 1_700_000_000_000, 1_700_000_100_000, 100),
            FinishedTimer(1, 0, 1_700_000_000_000, 1_700_000_100_000, 100),
            FinishedTimer(1, 2, 1_700_000_000_000, 1_700_000_100_000, 0),
            FinishedTimer(1, 2, 1_700_000_100_000, 1_700_000_000_000, 100),
            FinishedTimer(1, 2, 1_700_000_000_000, 1_700_000_100_000, 100, pausedSec = -1),
        )
        for (finished in invalid) {
            try {
                repository.saveFinished(finished)
                fail("Expected invalid finished timer to be rejected")
            } catch (_: IllegalArgumentException) {
                // Validation runs before DataStore.edit.
            }
            val stored = repository.timers.first()
            assertEquals(running, stored.active)
            assertNull(stored.finished)
        }
    }

    @Test
    fun invalidStartKeepsPreviousTimer() = runTest {
        val original = TimerStart(1, 2, 1_700_000_000_000, 100)
        repository.saveStart(original)
        val invalid = listOf(
            TimerStart(0, 2, 1_700_000_000_000, 100),
            TimerStart(1, 0, 1_700_000_000_000, 100),
            TimerStart(1, 2, 0, 100),
            TimerStart(1, 2, 1_700_000_000_000, -1),
        )
        for (start in invalid) {
            try {
                repository.saveStart(start)
                fail("Expected invalid timer to be rejected")
            } catch (_: IllegalArgumentException) {
                // Validation runs before DataStore.edit.
            }
            assertEquals(original, repository.active.first())
        }
    }
}
