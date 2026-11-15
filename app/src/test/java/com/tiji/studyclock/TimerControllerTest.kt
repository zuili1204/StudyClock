package com.tiji.studyclock

import com.tiji.studyclock.domain.timer.FinishedTimer
import com.tiji.studyclock.domain.timer.StoredTimers
import com.tiji.studyclock.domain.timer.TimerClock
import com.tiji.studyclock.domain.timer.TimerController
import com.tiji.studyclock.domain.timer.TimerStart
import com.tiji.studyclock.domain.timer.TimerStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TimerControllerTest {
    private class FakeClock(
        private val baseWall: Long,
        private val baseElapsed: Long,
        private val scheduler: TestCoroutineScheduler,
    ) : TimerClock {
        override fun wallNow(): Long = baseWall + scheduler.currentTime
        override fun elapsedNow(): Long = baseElapsed + scheduler.currentTime
    }

    private class FakeStore : TimerStore {
        private val state = MutableStateFlow(StoredTimers())
        override val timers: Flow<StoredTimers> = state
        var failFinished = false
        var failStart = false

        override suspend fun saveStart(start: TimerStart) {
            if (failStart) throw IllegalStateException("disk full")
            state.value = StoredTimers(active = start)
        }

        override suspend fun saveFinished(finished: FinishedTimer) {
            if (failFinished) throw IllegalStateException("disk full")
            state.value = StoredTimers(finished = finished)
        }

        override suspend fun clear() {
            state.value = StoredTimers()
        }

        fun current(): StoredTimers = state.value
    }

    @Test
    fun startPersistsAndExposesActiveTimer() = runTest {
        val store = FakeStore()
        val controller = TimerController(store, FakeClock(1_000_000, 5_000, testScheduler), 7, backgroundScope)
        runCurrent()

        val start = controller.start(subjectId = 3, categoryId = 17, targetSec = 1_500)
        runCurrent()

        assertNotNull(start)
        assertEquals(3L, start!!.subjectId)
        assertEquals(1_000_000L, start.startAt)
        assertEquals(7, start.bootCount)
        assertEquals(start, controller.active.value)
        assertEquals(start, store.current().active)
        assertNull(store.current().finished)
    }

    /** D32：专注复用同一状态机，focus 必须一路带到结算，进程被杀后才不会退化成练习。 */
    @Test
    fun focusFlagSurvivesToTheFinishedTimer() = runTest {
        val store = FakeStore()
        val controller = TimerController(store, FakeClock(1_000_000, 5_000, testScheduler), 7, backgroundScope)
        runCurrent()

        val start = controller.start(subjectId = 3, categoryId = 17, targetSec = null, focus = true)
        runCurrent()
        assertTrue(start!!.focus)
        assertTrue(store.current().active!!.focus)

        advanceTimeBy(90_000)
        runCurrent()
        val finished = controller.finish()
        runCurrent()
        assertNotNull(finished)
        assertTrue(finished!!.focus)
        assertTrue(store.current().finished!!.focus)
        assertEquals(90, finished.durationSec)
    }

    @Test
    fun countdownSettlesAtTargetWithoutAnyUi() = runTest {        val store = FakeStore()
        val clock = FakeClock(1_000_000, 5_000, testScheduler)
        val controller = TimerController(store, clock, 7, backgroundScope)
        runCurrent()

        controller.start(3, 17, targetSec = 60)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertNull(controller.active.value)
        val finished = controller.finished.value
        assertNotNull(finished)
        assertEquals(60, finished!!.durationSec)
        assertEquals(1_000_000L, finished.startAt)
        assertEquals(1_060_000L, finished.endAt)
        assertEquals(finished, store.current().finished)
        assertNull(store.current().active)
    }

    @Test
    fun pausedTimeIsExcludedFromNetDuration() = runTest {
        val store = FakeStore()
        val controller = TimerController(store, FakeClock(2_000_000, 10_000, testScheduler), 7, backgroundScope)
        runCurrent()

        controller.start(1, 2, targetSec = 60)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        val paused = controller.pause()
        assertTrue(paused!!.isPaused)

        advanceTimeBy(30_000)
        runCurrent()
        val resumed = controller.resume()
        assertFalse(resumed!!.isPaused)
        assertEquals(30, resumed.pausedTotalSec)

        advanceTimeBy(50_000)
        runCurrent()

        val finished = controller.finished.value
        assertNotNull(finished)
        assertEquals(60, finished!!.durationSec)
        assertEquals(30, finished.pausedSec)
        assertEquals(2_090_000L, finished.endAt)
    }

    @Test
    fun pauseAtTheExactTargetSettlesInsteadOfPausing() = runTest {
        val store = FakeStore()
        val controller = TimerController(store, FakeClock(3_000_000, 0, testScheduler), 7, backgroundScope)
        runCurrent()

        controller.start(1, 2, targetSec = 1)
        runCurrent()
        // 只推进时间，不让延迟 1 秒的结算任务先跑；此时暂停必须结算而不是挂起。
        advanceTimeBy(1_000)
        assertNull(controller.pause())
        runCurrent()
        assertNull(controller.active.value)
        assertEquals(1, controller.finished.value!!.durationSec)
    }

    @Test
    fun finishingAnOpenEndedTimerKeepsRealDuration() = runTest {
        val store = FakeStore()
        val controller = TimerController(store, FakeClock(4_000_000, 0, testScheduler), 7, backgroundScope)
        runCurrent()

        controller.start(1, 2, targetSec = null)
        runCurrent()
        advanceTimeBy(12_000)
        runCurrent()
        val finished = controller.finish()
        runCurrent()

        assertNotNull(finished)
        assertEquals(12, finished!!.durationSec)
        assertNull(finished.targetSec)
        assertNull(controller.active.value)
        assertEquals(finished, controller.finished.value)
    }

    @Test
    fun earlyStopOfACountdownNeverExceedsTheTarget() = runTest {
        val store = FakeStore()
        val controller = TimerController(store, FakeClock(5_000_000, 0, testScheduler), 7, backgroundScope)
        runCurrent()

        controller.start(1, 2, targetSec = 60)
        runCurrent()
        advanceTimeBy(20_000)
        runCurrent()
        val finished = controller.finish()

        assertNotNull(finished)
        assertEquals(20, finished!!.durationSec)
        assertEquals(60, finished.targetSec)
    }

    @Test
    fun discardClearsBothStatesAndAllowsANewTimer() = runTest {
        val store = FakeStore()
        val controller = TimerController(store, FakeClock(6_000_000, 0, testScheduler), 7, backgroundScope)
        runCurrent()

        controller.start(1, 2, null)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        controller.finish()
        runCurrent()
        assertNotNull(controller.finished.value)

        assertTrue(controller.discard())
        runCurrent()
        assertNull(controller.active.value)
        assertNull(controller.finished.value)
        assertEquals(StoredTimers(), store.current())

        assertNotNull(controller.start(4, 5, 60))
    }

    @Test
    fun clearResultRemovesThePendingFinishedTimer() = runTest {
        val store = FakeStore()
        val controller = TimerController(store, FakeClock(7_000_000, 0, testScheduler), 7, backgroundScope)
        runCurrent()

        controller.start(1, 2, null)
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        controller.finish()
        controller.clearResult()
        runCurrent()

        assertNull(controller.finished.value)
        assertEquals(StoredTimers(), store.current())
    }

    @Test
    fun failedSettlementKeepsTheActiveTimerAndRetries() = runTest {
        val store = FakeStore()
        val errors = mutableListOf<String>()
        val controller = TimerController(store, FakeClock(8_000_000, 0, testScheduler), 7, backgroundScope)
        backgroundScope.launch { controller.errors.collect { errors += it } }
        runCurrent()

        store.failFinished = true
        controller.start(1, 2, targetSec = 10)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        assertNull(controller.finished.value)
        assertNotNull(controller.active.value)
        assertEquals(1, errors.size)

        store.failFinished = false
        advanceTimeBy(60_000)
        runCurrent()

        assertNotNull(controller.finished.value)
        assertNull(controller.active.value)
    }

    @Test
    fun invalidOrDuplicateStartIsIgnored() = runTest {
        val store = FakeStore()
        val controller = TimerController(store, FakeClock(9_000_000, 0, testScheduler), 7, backgroundScope)
        runCurrent()

        assertNull(controller.start(0, 2, null))
        assertNull(controller.start(1, 0, null))
        assertNull(controller.start(1, 2, 0))
        runCurrent()
        assertEquals(StoredTimers(), store.current())

        val first = controller.start(1, 2, 60)
        runCurrent()
        assertNull(controller.start(9, 9, 120))
        assertEquals(first, controller.active.value)
    }

    @Test
    fun startFailureLeavesNoPhantomTimer() = runTest {
        val store = FakeStore().apply { failStart = true }
        val controller = TimerController(store, FakeClock(10_000_000, 0, testScheduler), 7, backgroundScope)
        runCurrent()

        val result = runCatching { controller.start(1, 2, 60) }
        runCurrent()

        assertTrue(result.isFailure)
        assertNull(controller.active.value)
        assertEquals(StoredTimers(), store.current())
    }
}
