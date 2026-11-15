package com.tiji.studyclock.domain.timer

import kotlinx.coroutines.flow.Flow

/**
 * Exactly one of [active] and [finished] is expected to be non-null. Auto-settlement replaces the
 * running timer with a finished one in a single edit, so a process death can neither lose the
 * result nor leave both states behind.
 */
data class StoredTimers(val active: TimerStart? = null, val finished: FinishedTimer? = null)

/** Persistence contract used by [TimerController]; the Android implementation is a DataStore. */
interface TimerStore {
    val timers: Flow<StoredTimers>

    suspend fun saveStart(start: TimerStart)

    suspend fun saveFinished(finished: FinishedTimer)

    suspend fun clear()
}
