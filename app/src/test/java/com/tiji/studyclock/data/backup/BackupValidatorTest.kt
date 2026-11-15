package com.tiji.studyclock.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupValidatorTest {
    private val valid = BackupFixtures.validPayload()

    private fun errors(payload: BackupPayload) = BackupValidator.validate(payload)

    private fun assertMentions(payload: BackupPayload, text: String) {
        val errors = errors(payload)
        assertTrue("expected an error containing \"$text\", got $errors", errors.any { it.contains(text) })
    }

    @Test
    fun minimalCompleteBackupPasses() {
        assertEquals(emptyList<String>(), errors(valid))
    }

    @Test
    fun newerSchemaIsRejectedButV3RemainsRestorable() {
        // v0.11 把备份升到 6（新增 FOCUS 记录模式，仍无 DDL）；只有 7 及以上才算未来版本。
        assertEquals(6, BackupCodec.SCHEMA_VERSION)
        assertEquals(emptyList<String>(), errors(valid.copy(schemaVersion = 6)))
        assertEquals(emptyList<String>(), errors(valid.copy(schemaVersion = 5)))
        assertMentions(valid.copy(schemaVersion = 7), "版本与当前应用不一致")
        assertEquals(emptyList<String>(), errors(valid.copy(schemaVersion = 3)))
        assertEquals(emptyList<String>(), errors(valid.copy(schemaVersion = 4)))
    }

    @Test
    fun wrongTagsMustAlreadyBeCanonical() {
        val spaced = valid.sessions.first().copy(wrongTags = "属性规律,增长率")
        assertMentions(valid.copy(sessions = listOf(spaced)), "错题关键词格式不正确")
        val ok = valid.sessions.first().copy(wrongTags = "属性规律|增长率")
        assertEquals(emptyList<String>(), errors(valid.copy(sessions = listOf(ok))))
    }

    @Test
    fun duplicateSubjectIdsAreRejected() {
        val duplicate = valid.subjects.first().copy(name = "另一个科目")
        assertMentions(valid.copy(subjects = valid.subjects + duplicate), "科目编号重复")
    }

    @Test
    fun categoryMustBelongToAnExistingSubject() {
        val orphan = valid.categories.first().copy(subjectId = 99)
        assertMentions(valid.copy(categories = listOf(orphan)), "类别没有对应科目")
    }

    @Test
    fun recordMustUseACategoryOfItsOwnSubject() {
        val secondSubject = valid.subjects.first().copy(id = 2, name = "数量关系", examType = "CET")
        val foreignCategory = CategoryRecord(20, 2, "数字推理", null, 0, false)
        val session = valid.sessions.first().copy(categoryId = 20)
        assertMentions(
            valid.copy(subjects = valid.subjects + secondSubject, categories = valid.categories + foreignCategory, sessions = listOf(session)),
            "对应不上",
        )
    }

    @Test
    fun questionCountsMustBeConsistent() {
        val badCount = valid.sessions.first().copy(totalCount = 10, correctCount = 11)
        assertMentions(valid.copy(sessions = listOf(badCount)), "题量或答对数不合法")
    }

    @Test
    fun answerSequenceMustMatchCounts() {
        val badSequence = valid.sessions.first().copy(answerSequence = "111")
        assertMentions(valid.copy(sessions = listOf(badSequence)), "答题序列与题量或答对数不一致")
    }

    @Test
    fun sessionExamMustMatchItsSubject() {
        val mismatch = valid.sessions.first().copy(examType = "CET")
        assertMentions(valid.copy(sessions = listOf(mismatch)), "与科目所属考试不一致")
    }

    @Test
    fun sessionMustNotReferenceMissingPrescription() {
        val orphan = valid.sessions.first().copy(prescriptionId = 99)
        assertMentions(valid.copy(sessions = listOf(orphan)), "关联了不存在的处方")
    }

    @Test
    fun sessionPrescriptionMustShareItsCategory() {
        val otherCategory = CategoryRecord(11, 1, "语句填充", null, 1, false)
        val session = valid.sessions.first().copy(categoryId = 11)
        assertMentions(
            valid.copy(categories = valid.categories + otherCategory, sessions = listOf(session)),
            "与处方类别不一致",
        )
    }

    @Test
    fun resultMustReferenceExistingSessions() {
        val broken = valid.prescriptionResults.first().copy(afterSessionIds = listOf(999))
        assertMentions(valid.copy(prescriptionResults = listOf(broken)), "引用了不存在的记录")
    }

    @Test
    fun planDateAndTargetScoreAreValidated() {
        val badDate = valid.examPlans.first().copy(examDate = "2026-02-30")
        assertMentions(valid.copy(examPlans = listOf(badDate)), "考试日期格式不正确")
        val badScore = valid.examPlans.first().copy(targetScore = 0)
        assertMentions(valid.copy(examPlans = listOf(badScore)), "目标分数不合法")
    }

    @Test
    fun duplicateExamPlansAreRejected() {
        val plan = valid.examPlans.first()
        assertMentions(valid.copy(examPlans = listOf(plan, plan)), "考试计划重复")
    }

    @Test
    fun preferencesAreValidated() {
        val settings = valid.settings!!.copy(dailyQuestionGoal = 0, theme = "NEON")
        assertMentions(valid.copy(settings = settings), "每日题量目标不合法")
        assertMentions(valid.copy(settings = settings), "外观设置无法识别")
        assertMentions(valid.copy(currentExam = "NOPE"), "当前考试无法识别")
    }

    @Test
    fun requireValidStopsAtFirstProblem() {
        val error = runCatching {
            BackupValidator.requireValid(valid.copy(schemaVersion = 99))
        }.exceptionOrNull()
        assertEquals("备份文件版本与当前应用不一致，未恢复。", error?.message)
    }
}
