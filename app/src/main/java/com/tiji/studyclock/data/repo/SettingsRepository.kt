package com.tiji.studyclock.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tiji.studyclock.domain.model.DateAttribution
import com.tiji.studyclock.domain.model.ExamType
import com.tiji.studyclock.domain.model.ThemeMode
import com.tiji.studyclock.domain.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(private val dataStore: DataStore<Preferences>) {    val settings: Flow<UserSettings> = dataStore.data.map { values ->
        UserSettings(
            dailyQuestionGoal = values[QUESTION_GOAL]?.takeIf { it > 0 },
            dailyMinuteGoal = values[MINUTE_GOAL]?.takeIf { it > 0 },
            selfAssessment = values[SELF_ASSESSMENT] ?: false,
            dateAttribution = DateAttribution.entries.firstOrNull { it.name == values[DATE_ATTRIBUTION] }
                ?: DateAttribution.START,
            theme = ThemeMode.entries.firstOrNull { it.name == values[THEME] } ?: ThemeMode.SYSTEM,
        )
    }.distinctUntilChanged()

    /** 当前选中的考试类型。与 UserSettings 分开存，避免保存设置时把它覆盖回默认值。 */
    val currentExam: Flow<ExamType> = dataStore.data
        .map { values -> ExamType.entries.firstOrNull { it.key == values[CURRENT_EXAM] } ?: ExamType.CIVIL_SERVANT }
        .distinctUntilChanged()

    suspend fun setCurrentExam(type: ExamType) {
        dataStore.edit { values -> values[CURRENT_EXAM] = type.key }
    }

    /** 上次用过的自定义倒计时分钟数。与 UserSettings 分开存，避免整份保存时覆盖。 */
    val customTimerMinutes: Flow<Int?> = dataStore.data
        .map { values ->
            values[CUSTOM_TIMER_MINUTES]?.takeIf { it in MIN_CUSTOM_MINUTES..MAX_CUSTOM_MINUTES }
        }
        .distinctUntilChanged()

    suspend fun setCustomTimerMinutes(minutes: Int) {
        require(minutes in MIN_CUSTOM_MINUTES..MAX_CUSTOM_MINUTES) {
            "自定义时长需在 $MIN_CUSTOM_MINUTES–$MAX_CUSTOM_MINUTES 分钟之间"
        }
        dataStore.edit { values -> values[CUSTOM_TIMER_MINUTES] = minutes }
    }

    /** 备份提醒与外部文件夹设置；与 UserSettings 分开存，避免设置页整份保存时覆盖。 */
    val backupSettings: Flow<BackupSettings> = dataStore.data
        .map { values ->
            BackupSettings(
                lastBackupAt = values[LAST_BACKUP_AT]?.takeIf { it > 0 },
                folderUri = values[BACKUP_FOLDER_URI]?.takeIf { it.isNotBlank() },
                folderName = values[BACKUP_FOLDER_NAME]?.takeIf { it.isNotBlank() },
                reminderDismissedAt = values[BACKUP_REMINDER_DISMISSED_AT]?.takeIf { it > 0 },
            )
        }
        .distinctUntilChanged()

    /** 外部文件夹写入成功后才算一次备份，避免提醒被失败的导出“消音”。 */
    suspend fun markBackupDone(atMillis: Long = System.currentTimeMillis()) {
        dataStore.edit { values -> values[LAST_BACKUP_AT] = atMillis }
    }

    /** 传 null 清空文件夹设置；清除时一并删掉旧文件夹名。 */
    suspend fun setBackupFolder(uri: String?, name: String?) {
        dataStore.edit { values ->
            if (uri == null) {
                values.remove(BACKUP_FOLDER_URI)
                values.remove(BACKUP_FOLDER_NAME)
            } else {
                values[BACKUP_FOLDER_URI] = uri
                values[BACKUP_FOLDER_NAME] = name.orEmpty()
            }
        }
    }

    suspend fun dismissBackupReminder(atMillis: Long = System.currentTimeMillis()) {
        dataStore.edit { values -> values[BACKUP_REMINDER_DISMISSED_AT] = atMillis }
    }

    suspend fun save(settings: UserSettings) {
        require(settings.dailyQuestionGoal == null || settings.dailyQuestionGoal > 0) { "每日题量目标只能为空或正数" }
        require(settings.dailyMinuteGoal == null || settings.dailyMinuteGoal > 0) { "每日分钟目标只能为空或正数" }
        dataStore.edit { values ->
            val questions = settings.dailyQuestionGoal
            if (questions == null) values.remove(QUESTION_GOAL) else values[QUESTION_GOAL] = questions
            val minutes = settings.dailyMinuteGoal
            if (minutes == null) values.remove(MINUTE_GOAL) else values[MINUTE_GOAL] = minutes
            values[SELF_ASSESSMENT] = settings.selfAssessment
            values[DATE_ATTRIBUTION] = settings.dateAttribution.name
            values[THEME] = settings.theme.name
        }
    }

    companion object {
        const val MIN_CUSTOM_MINUTES = 5
        const val MAX_CUSTOM_MINUTES = 180

        private val QUESTION_GOAL = intPreferencesKey("daily_question_goal")
        private val MINUTE_GOAL = intPreferencesKey("daily_minute_goal")
        private val SELF_ASSESSMENT = booleanPreferencesKey("self_assessment")
        private val DATE_ATTRIBUTION = stringPreferencesKey("date_attribution")
        private val CURRENT_EXAM = stringPreferencesKey("current_exam")
        private val THEME = stringPreferencesKey("theme")
        private val CUSTOM_TIMER_MINUTES = intPreferencesKey("custom_timer_minutes")
        private val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
        private val BACKUP_FOLDER_URI = stringPreferencesKey("backup_folder_uri")
        private val BACKUP_FOLDER_NAME = stringPreferencesKey("backup_folder_name")
        private val BACKUP_REMINDER_DISMISSED_AT = longPreferencesKey("backup_reminder_dismissed_at")
    }
}

data class BackupSettings(
    val lastBackupAt: Long? = null,
    val folderUri: String? = null,
    val folderName: String? = null,
    val reminderDismissedAt: Long? = null,
)
