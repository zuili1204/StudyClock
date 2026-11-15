package com.tiji.studyclock.domain.timer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single writer for timer state. The ViewModel, the foreground service and notification
 * actions all go through here; none of them may persist a start or a result on their own.
 *
 * Settlement is scheduled in an application-scoped coroutine, so destroying the activity does not
 * stop a countdown from being settled while the process is alive. A fully killed process still
 * settles on the next launch, with [SessionTimer.finishAtTarget] reconstructing the target instant.
 */
class TimerController(
    private val store: TimerStore,
    private val clock: TimerClock,
    private val bootCount: Int,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val state = MutableStateFlow(StoredTimers())
    val timers: StateFlow<StoredTimers> = state.asStateFlow()
    /** True once the stored state has been read at least once; the service waits for it. */
    private val loadedState = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = loadedState.asStateFlow()
    val active: StateFlow<TimerStart?> =
        state.map { it.active }.stateIn(scope, SharingStarted.Eagerly, null)
    val finished: StateFlow<FinishedTimer?> =
        state.map { it.finished }.stateIn(scope, SharingStarted.Eagerly, null)

    private val errorChannel = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = errorChannel.asSharedFlow()

    private var settleJob: Job? = null
    private var scheduledKey: Triple<Long, Int?, Int>? = null

    init {
        scope.launch {
            try {
                store.timers.collect { timers ->
                    publish(timers)
                    loadedState.value = true
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                loadedState.value = true
                errorChannel.tryEmit("暂时无法读取计时状态；如果正在计时，请回到应用核对。")
            }
        }
    }

    suspend fun start(subjectId: Long, categoryId: Long, targetSec: Int?, focus: Boolean = false): TimerStart? {
        awaitLoaded()
        return mutex.withLock {
            if (state.value.active != null) return@withLock null
            if (subjectId <= 0 || categoryId <= 0) return@withLock null
            if (targetSec != null && targetSec <= 0) return@withLock null
            val start = TimerStart(
                subjectId = subjectId,
                categoryId = categoryId,
                startAt = clock.wallNow(),
                startElapsed = clock.elapsedNow(),
                bootCount = bootCount,
                targetSec = targetSec,
                focus = focus,
            )
            store.saveStart(start)
            publish(StoredTimers(active = start))
            start
        }
    }

    suspend fun pause(): TimerStart? {
        awaitLoaded()
        return mutex.withLock {
            val start = state.value.active ?: return@withLock null
            if (start.isPaused) return@withLock start
            val nowAt = clock.wallNow()
            val nowElapsed = clock.elapsedNow()
            // A pause at the exact target must settle, not stretch the countdown.
            if (SessionTimer.remainingSeconds(start, nowAt, nowElapsed, bootCount) == 0) {
                settleLocked()
                return@withLock null
            }
            val paused = SessionTimer.pause(start, nowAt, nowElapsed)
            store.saveStart(paused)
            publish(StoredTimers(active = paused))
            paused
        }
    }

    suspend fun resume(): TimerStart? {
        awaitLoaded()
        return mutex.withLock {
            val start = state.value.active ?: return@withLock null
            if (!start.isPaused) return@withLock start
            val resumed = SessionTimer.resume(start, clock.wallNow(), clock.elapsedNow(), bootCount)
            if (resumed == start) return@withLock start
            store.saveStart(resumed)
            publish(StoredTimers(active = resumed))
            resumed
        }
    }

    suspend fun finish(): FinishedTimer? {
        awaitLoaded()
        return mutex.withLock {
            val start = state.value.active ?: return@withLock null
            val nowAt = clock.wallNow()
            val nowElapsed = clock.elapsedNow()
            val finished = SessionTimer.finishAtTarget(start, nowAt, nowElapsed, bootCount)
                ?: SessionTimer.finish(start, nowAt, nowElapsed, bootCount).let { raw ->
                    start.targetSec?.let { target -> raw.copy(durationSec = raw.durationSec.coerceAtMost(target)) } ?: raw
                }
            store.saveFinished(finished)
            publish(StoredTimers(finished = finished))
            finished
        }
    }

    suspend fun discard(): Boolean {
        awaitLoaded()
        return mutex.withLock {
            if (state.value.active == null && state.value.finished == null) return@withLock false
            store.clear()
            publish(StoredTimers())
            true
        }
    }

    /** Called after a timed session has been saved as a practice record. */
    suspend fun clearResult() {
        awaitLoaded()
        mutex.withLock {
            if (state.value.finished == null) return
            store.clear()
            publish(StoredTimers())
        }
    }

    /**
     * Writes must not run against the empty initial state: a notification action arriving right
     * after a process restart would otherwise look like "no timer" and silently do nothing.
     */
    private suspend fun awaitLoaded() {
        if (!loadedState.value) loadedState.first { it }
    }

    private fun publish(timers: StoredTimers) {
        state.value = timers
        val running = timers.active
        if (running == null || running.isPaused || running.targetSec == null) {
            cancelSettlement()
            return
        }
        scheduleSettlement(running)
    }

    private fun scheduleSettlement(start: TimerStart) {
        val key = Triple(start.startAt, start.targetSec, start.pausedTotalSec)
        if (settleJob?.isActive == true && scheduledKey == key) return
        settleJob?.cancel()
        scheduledKey = key
        settleJob = scope.launch {
            while (true) {
                val current = state.value.active ?: return@launch
                if (current.startAt != start.startAt || current.isPaused || current.targetSec == null) return@launch
                val remaining = SessionTimer.remainingSeconds(
                    current,
                    clock.wallNow(),
                    clock.elapsedNow(),
                    bootCount,
                ) ?: return@launch
                if (remaining <= 0) {
                    settleIfDue()
                    return@launch
                }
                delay(remaining * 1000L)
            }
        }
    }

    private fun cancelSettlement() {
        settleJob?.cancel()
        settleJob = null
        scheduledKey = null
    }

    private suspend fun settleIfDue(): Boolean = mutex.withLock { settleLocked() }

    private suspend fun settleLocked(): Boolean {
        val start = state.value.active ?: return false
        val finished = SessionTimer.finishAtTarget(
            start,
            clock.wallNow(),
            clock.elapsedNow(),
            bootCount,
        ) ?: return false
        return try {
            store.saveFinished(finished)
            // The current settlement job is about to end; do not cancel it from inside publish().
            settleJob = null
            scheduledKey = null
            publish(StoredTimers(finished = finished))
            true
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            errorChannel.tryEmit("倒计时已到，但结算状态未能写入本机；请尽快填写并保存结果。")
            settleJob = scope.launch {
                delay(SETTLEMENT_RETRY_MILLIS)
                settleIfDue()
            }
            false
        }
    }

    private companion object {
        const val SETTLEMENT_RETRY_MILLIS = 60_000L
    }
}
