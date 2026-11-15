package com.tiji.studyclock.data.backup

internal object BackupFixtures {
    const val T0 = 1_790_000_000_000L

    fun validPayload() = BackupPayload(
        schemaVersion = BackupCodec.SCHEMA_VERSION,
        exportedAt = T0 + 1_000,
        appVersion = "0.7.0-dev",
        subjects = listOf(
            SubjectRecord(1, "言语理解与判断", "#4C6FFF", 0, false, "CIVIL_SERVANT"),
        ),
        categories = listOf(
            CategoryRecord(10, 1, "主旨概括", null, 0, false),
        ),
        sessions = listOf(
            SessionRecord(
                id = 100, categoryId = 10, subjectId = 1,
                startAt = T0, endAt = T0 + 600_000, durationSec = 600,
                pausedSec = 0, pauseCount = 0,
                totalCount = 10, correctCount = 6, answerSequence = "1100101011",
                wrongTags = "属性规律|增长率",
                difficulty = 2, energy = 1, source = "TIMER",
                prescriptionId = 7, examType = "CIVIL_SERVANT",
                createdAt = T0, updatedAt = T0,
            ),
        ),
        prescriptions = listOf(
            PrescriptionRecord(
                id = 7, categoryId = 10, type = "BASELINE", targetCount = 20,
                targetMinutes = 25, targetAccuracy = 0.8,
                attribution = "正确率", reason = "先做 20 题找回手感",
                status = "DONE", createdAt = T0, startedAt = T0,
                completedAt = T0 + 1_000, dueAt = T0 + 86_400_000,
                invalidatedReason = null,
            ),
        ),
        prescriptionResults = listOf(
            PrescriptionResultRecord(
                id = 5, prescriptionId = 7,
                beforeAccuracy = 0.6, afterAccuracy = 0.8,
                beforeSecPerQ = 12.0, afterSecPerQ = 9.0,
                beforeCount = 10, afterCount = 10,
                beforeSessionIds = listOf(100), afterSessionIds = listOf(100),
                verdict = "EFFECTIVE", evaluatedAt = T0 + 2_000,
            ),
        ),
        examPlans = listOf(
            ExamPlanRecord("CIVIL_SERVANT", "国考", "2026-11-29", 140),
        ),
        settings = SettingsRecord(37, null, false, "START", "SYSTEM"),
        currentExam = "CIVIL_SERVANT",
    )
}
