package com.tiji.studyclock.domain.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupReminderTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = 1_800_000_000_000L
    private val oldRecord = now - 30 * day

    @Test
    fun noRecordsNeverReminds() {
        assertFalse(BackupReminder.evaluate(null, null, null, now).due)
    }

    @Test
    fun neverBackedUpStartsCountingFromOldestRecord() {
        val fresh = BackupReminder.evaluate(null, null, now - 3 * day, now)
        assertFalse(fresh.due)
        assertEquals(3L, fresh.daysSince)

        val stale = BackupReminder.evaluate(null, null, oldRecord, now)
        assertTrue(stale.due)
        assertTrue(stale.neverBackedUp)
        assertEquals(30L, stale.daysSince)
    }

    @Test
    fun recentBackupSuppressesReminder() {
        val state = BackupReminder.evaluate(now - 10 * day, null, oldRecord, now)
        assertFalse(state.due)
        assertFalse(state.neverBackedUp)
        assertEquals(10L, state.daysSince)
    }

    @Test
    fun reminderBecomesDueAtFourteenDays() {
        assertFalse(BackupReminder.evaluate(now - 13 * day, null, oldRecord, now).due)
        val state = BackupReminder.evaluate(now - 14 * day, null, oldRecord, now)
        assertTrue(state.due)
        assertEquals(14L, state.daysSince)
    }

    @Test
    fun dismissalHidesReminderForSevenDaysThenReturns() {
        val lastBackup = now - 20 * day
        assertFalse(BackupReminder.evaluate(lastBackup, now - day, oldRecord, now).due)
        assertTrue(BackupReminder.evaluate(lastBackup, now - 8 * day, oldRecord, now).due)
    }

    @Test
    fun clockGoingBackwardsDoesNotRemind() {
        val state = BackupReminder.evaluate(now + day, null, oldRecord, now)
        assertFalse(state.due)
        assertEquals(0L, state.daysSince)
    }
}
