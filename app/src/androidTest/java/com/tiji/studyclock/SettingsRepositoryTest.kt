package com.tiji.studyclock

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tiji.studyclock.data.DataModule
import com.tiji.studyclock.data.repo.SettingsRepository
import com.tiji.studyclock.domain.model.DateAttribution
import com.tiji.studyclock.domain.model.ThemeMode
import com.tiji.studyclock.domain.model.UserSettings
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
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsRepositoryTest {
    private lateinit var context: Context
    private lateinit var file: File
    private lateinit var scope: CoroutineScope
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        file = File(context.cacheDir, "settings-${UUID.randomUUID()}.preferences_pb")
        openStore()
    }

    private fun openStore() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repository = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
    }

    @After
    fun tearDown() {
        runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
        file.delete()
    }

    @Test
    fun defaultsRoundTripAndReopenPreservePreferences() = runTest {
        assertEquals(UserSettings(), repository.settings.first())
        val expected = UserSettings(
            dailyQuestionGoal = 30, dailyMinuteGoal = 25, selfAssessment = true,
            dateAttribution = DateAttribution.END, theme = ThemeMode.DARK,
        )
        repository.save(expected)
        assertEquals(expected, repository.settings.first())
        scope.coroutineContext[Job]!!.cancelAndJoin()
        openStore()
        assertEquals(expected, repository.settings.first())
        repository.save(UserSettings())
        assertEquals(UserSettings(), repository.settings.first())
        repository.save(UserSettings(dailyMinuteGoal = 1))
        assertEquals(UserSettings(dailyMinuteGoal = 1), repository.settings.first())
        repository.save(UserSettings(dailyQuestionGoal = 1, theme = ThemeMode.LIGHT))
        assertEquals(UserSettings(dailyQuestionGoal = 1, theme = ThemeMode.LIGHT), repository.settings.first())
    }

    @Test
    fun invalidGoalsDoNotPartiallyWritePreferences() = runTest {
        val original = UserSettings(dailyQuestionGoal = 10, selfAssessment = true)
        repository.save(original)
        val invalid = listOf(
            UserSettings(dailyQuestionGoal = 0), UserSettings(dailyQuestionGoal = -1),
            UserSettings(dailyMinuteGoal = 0), UserSettings(dailyMinuteGoal = -1),
            UserSettings(dailyQuestionGoal = 20, dailyMinuteGoal = -1, theme = ThemeMode.DARK),
        )
        for (settings in invalid) {
            try {
                repository.save(settings)
                fail("Expected invalid goal to be rejected")
            } catch (_: IllegalArgumentException) {
                // Validation must occur before DataStore.edit.
            }
            assertEquals(original, repository.settings.first())
        }
    }

    @Test
    fun applicationContextUsesOneProductionDataStore() {
        assertSame(
            DataModule.provideSettingsDataStore(context),
            DataModule.provideSettingsDataStore(context.applicationContext),
        )
    }
}
