package com.tiji.studyclock.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderBackupTest {
    @Test
    fun onlyOwnJsonBackupsAreCandidates() {
        assertTrue(FolderBackup.isOwnBackup("tiji-backup-20261031-101500.json"))
        assertEquals(false, FolderBackup.isOwnBackup("题迹备份-2026-10-31.json"))
        assertEquals(false, FolderBackup.isOwnBackup("tiji-backup-notes.txt"))
        assertEquals(false, FolderBackup.isOwnBackup("something.json"))
    }

    @Test
    fun keepsNewestFilesAndIgnoresForeignFiles() {
        val names = (1..9).map { "tiji-backup-2026100${it}-120000.json" } + "random.json"
        val stale = FolderBackup.staleBackups(names, keep = 7)
        assertEquals(
            setOf(
                "tiji-backup-20261001-120000.json",
                "tiji-backup-20261002-120000.json",
            ),
            stale,
        )
    }

    @Test
    fun noDeletionWhenWithinLimit() {
        val names = (1..7).map { "tiji-backup-2026100${it}-120000.json" }
        assertTrue(FolderBackup.staleBackups(names, keep = 7).isEmpty())
    }
}
