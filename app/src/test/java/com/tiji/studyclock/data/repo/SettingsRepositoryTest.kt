package com.tiji.studyclock.data.repo

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.tiji.studyclock.domain.model.UserSettings
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun repository(): SettingsRepository {
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = {
            File(temp.root, "settings.preferences_pb")
        })
        return SettingsRepository(store)
    }

    @Test
    fun customTimerMinutesStartsEmptyThenPersists() = runBlocking {
        val repository = repository()
        assertNull(repository.customTimerMinutes.first())

        repository.setCustomTimerMinutes(90)

        assertEquals(90, repository.customTimerMinutes.first())
    }

    @Test
    fun customTimerMinutesRejectsOutOfRangeValues() = runBlocking {
        val repository = repository()

        for (minutes in listOf(4, 181, 0, -30)) {
            runCatching { repository.setCustomTimerMinutes(minutes) }
        }

        assertNull(repository.customTimerMinutes.first())
    }

    @Test
    fun savingUserSettingsKeepsRememberedCustomMinutes() = runBlocking {
        val repository = repository()
        repository.setCustomTimerMinutes(50)

        repository.save(UserSettings(dailyQuestionGoal = 30))

        assertEquals(50, repository.customTimerMinutes.first())
        assertEquals(30, repository.settings.first().dailyQuestionGoal)
    }

    @Test
    fun backupSettingsTrackBackupFolderAndDismissalSeparately() = runBlocking {
        val repository = repository()
        assertEquals(BackupSettings(), repository.backupSettings.first())

        repository.markBackupDone(1_700_000_000_000)
        repository.setBackupFolder("content://tree/backup", "Documents")
        repository.dismissBackupReminder(1_700_000_100_000)

        val settings = repository.backupSettings.first()
        assertEquals(1_700_000_000_000, settings.lastBackupAt)
        assertEquals("content://tree/backup", settings.folderUri)
        assertEquals("Documents", settings.folderName)
        assertEquals(1_700_000_100_000, settings.reminderDismissedAt)

        repository.save(UserSettings(dailyMinuteGoal = 30))
        assertEquals(settings, repository.backupSettings.first())

        repository.setBackupFolder(null, null)
        val cleared = repository.backupSettings.first()
        assertNull(cleared.folderUri)
        assertNull(cleared.folderName)
        assertEquals(1_700_000_000_000, cleared.lastBackupAt)
    }
}
