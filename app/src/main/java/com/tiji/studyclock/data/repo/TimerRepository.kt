package com.tiji.studyclock.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.tiji.studyclock.domain.timer.FinishedTimer
import com.tiji.studyclock.domain.timer.StoredTimers
import com.tiji.studyclock.domain.timer.TimerStart
import com.tiji.studyclock.domain.timer.TimerStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Keeps one running timer so a process death does not silently discard accumulated time. */
@Singleton
class TimerRepository @Inject constructor(private val dataStore: DataStore<Preferences>) : TimerStore {
    override val timers: Flow<StoredTimers> = dataStore.data.map(::readTimers).distinctUntilChanged()
    val active: Flow<TimerStart?> = timers.map { it.active }.distinctUntilChanged()

    override suspend fun saveStart(start: TimerStart) {
        require(start.subjectId > 0 && start.categoryId > 0) { "计时必须关联有效科目和类别" }
        require(start.startAt > 0 && start.startElapsed >= 0) { "计时起始时间无效" }
        require(start.targetSec == null || start.targetSec > 0) { "倒计时目标必须为正数秒" }
        require(start.pausedTotalSec >= 0 && start.pauseCount >= 0) { "暂停累计不能为负" }
        dataStore.edit { values ->
            values[SUBJECT_ID] = start.subjectId
            values[CATEGORY_ID] = start.categoryId
            values[START_AT] = start.startAt
            values[START_ELAPSED] = start.startElapsed
            values[BOOT_COUNT] = start.bootCount
            if (start.targetSec == null) values.remove(TARGET_SEC) else values[TARGET_SEC] = start.targetSec
            values[PAUSED_TOTAL_SEC] = start.pausedTotalSec
            values[PAUSE_COUNT] = start.pauseCount
            if (start.pauseStartedAt == null) values.remove(PAUSE_STARTED_AT) else values[PAUSE_STARTED_AT] = start.pauseStartedAt
            if (start.pauseStartedElapsed == null) values.remove(PAUSE_STARTED_ELAPSED) else values[PAUSE_STARTED_ELAPSED] = start.pauseStartedElapsed
            values[FOCUS] = start.focus
            clearFinished(values)
        }
    }

    override suspend fun saveFinished(finished: FinishedTimer) {
        require(finished.subjectId > 0 && finished.categoryId > 0) { "计时结果必须关联有效科目和类别" }
        require(finished.durationSec > 0) { "计时时长必须为正数秒" }
        require(finished.endAt >= finished.startAt) { "计时结束时间不能早于开始" }
        require(finished.pausedSec >= 0 && finished.pauseCount >= 0) { "暂停累计不能为负" }
        require(finished.targetSec == null || finished.targetSec > 0) { "倒计时目标必须为正数秒" }
        dataStore.edit { values ->
            values[FINISHED_SUBJECT_ID] = finished.subjectId
            values[FINISHED_CATEGORY_ID] = finished.categoryId
            values[FINISHED_START_AT] = finished.startAt
            values[FINISHED_END_AT] = finished.endAt
            values[FINISHED_DURATION_SEC] = finished.durationSec
            values[FINISHED_PAUSED_SEC] = finished.pausedSec
            values[FINISHED_PAUSE_COUNT] = finished.pauseCount
            if (finished.targetSec == null) values.remove(FINISHED_TARGET_SEC) else values[FINISHED_TARGET_SEC] = finished.targetSec
            values[FINISHED_FOCUS] = finished.focus
            clearActive(values)
        }
    }

    override suspend fun clear() {
        dataStore.edit { values ->
            clearActive(values)
            clearFinished(values)
        }
    }

    private fun readTimers(values: Preferences): StoredTimers = StoredTimers(
        active = values[SUBJECT_ID]?.let { subjectId ->
            val categoryId = values[CATEGORY_ID] ?: return@let null
            val startAt = values[START_AT] ?: return@let null
            val startElapsed = values[START_ELAPSED] ?: return@let null
            TimerStart(
                subjectId = subjectId,
                categoryId = categoryId,
                startAt = startAt,
                startElapsed = startElapsed,
                bootCount = values[BOOT_COUNT] ?: -1,
                targetSec = values[TARGET_SEC],
                pausedTotalSec = values[PAUSED_TOTAL_SEC] ?: 0,
                pauseCount = values[PAUSE_COUNT] ?: 0,
                pauseStartedAt = values[PAUSE_STARTED_AT],
                pauseStartedElapsed = values[PAUSE_STARTED_ELAPSED],
                focus = values[FOCUS] ?: false,
            )
        },
        finished = values[FINISHED_SUBJECT_ID]?.let { subjectId ->
            val categoryId = values[FINISHED_CATEGORY_ID] ?: return@let null
            val startAt = values[FINISHED_START_AT] ?: return@let null
            val endAt = values[FINISHED_END_AT] ?: return@let null
            val durationSec = values[FINISHED_DURATION_SEC] ?: return@let null
            FinishedTimer(
                subjectId = subjectId,
                categoryId = categoryId,
                startAt = startAt,
                endAt = endAt,
                durationSec = durationSec,
                pausedSec = values[FINISHED_PAUSED_SEC] ?: 0,
                pauseCount = values[FINISHED_PAUSE_COUNT] ?: 0,
                targetSec = values[FINISHED_TARGET_SEC],
                focus = values[FINISHED_FOCUS] ?: false,
            )
        },
    )

    private fun clearActive(values: MutablePreferences) {
        values.remove(SUBJECT_ID)
        values.remove(CATEGORY_ID)
        values.remove(START_AT)
        values.remove(START_ELAPSED)
        values.remove(BOOT_COUNT)
        values.remove(TARGET_SEC)
        values.remove(PAUSED_TOTAL_SEC)
        values.remove(PAUSE_COUNT)
        values.remove(PAUSE_STARTED_AT)
        values.remove(PAUSE_STARTED_ELAPSED)
        values.remove(FOCUS)
    }

    private fun clearFinished(values: MutablePreferences) {
        values.remove(FINISHED_SUBJECT_ID)
        values.remove(FINISHED_CATEGORY_ID)
        values.remove(FINISHED_START_AT)
        values.remove(FINISHED_END_AT)
        values.remove(FINISHED_DURATION_SEC)
        values.remove(FINISHED_PAUSED_SEC)
        values.remove(FINISHED_PAUSE_COUNT)
        values.remove(FINISHED_TARGET_SEC)
        values.remove(FINISHED_FOCUS)
    }

    private companion object {
        val SUBJECT_ID = longPreferencesKey("timer_subject_id")
        val CATEGORY_ID = longPreferencesKey("timer_category_id")
        val START_AT = longPreferencesKey("timer_start_at")
        val START_ELAPSED = longPreferencesKey("timer_start_elapsed")
        val BOOT_COUNT = intPreferencesKey("timer_boot_count")
        val TARGET_SEC = intPreferencesKey("timer_target_sec")
        val PAUSED_TOTAL_SEC = intPreferencesKey("timer_paused_total_sec")
        val PAUSE_COUNT = intPreferencesKey("timer_pause_count")
        val PAUSE_STARTED_AT = longPreferencesKey("timer_pause_started_at")
        val PAUSE_STARTED_ELAPSED = longPreferencesKey("timer_pause_started_elapsed")
        val FOCUS = booleanPreferencesKey("timer_focus")

        val FINISHED_SUBJECT_ID = longPreferencesKey("timer_finished_subject_id")
        val FINISHED_CATEGORY_ID = longPreferencesKey("timer_finished_category_id")
        val FINISHED_START_AT = longPreferencesKey("timer_finished_start_at")
        val FINISHED_END_AT = longPreferencesKey("timer_finished_end_at")
        val FINISHED_DURATION_SEC = intPreferencesKey("timer_finished_duration_sec")
        val FINISHED_PAUSED_SEC = intPreferencesKey("timer_finished_paused_sec")
        val FINISHED_PAUSE_COUNT = intPreferencesKey("timer_finished_pause_count")
        val FINISHED_TARGET_SEC = intPreferencesKey("timer_finished_target_sec")
        val FINISHED_FOCUS = booleanPreferencesKey("timer_finished_focus")
    }
}
