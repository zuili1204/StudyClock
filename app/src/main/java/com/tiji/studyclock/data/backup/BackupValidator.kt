package com.tiji.studyclock.data.backup

import com.tiji.studyclock.domain.model.DateAttribution
import com.tiji.studyclock.domain.model.ExamType
import com.tiji.studyclock.domain.model.PrescriptionStatus
import com.tiji.studyclock.domain.model.PrescriptionType
import com.tiji.studyclock.domain.model.RecordMode
import com.tiji.studyclock.domain.model.ScoreRules
import com.tiji.studyclock.domain.model.SessionKind
import com.tiji.studyclock.domain.model.SessionSource
import com.tiji.studyclock.domain.model.TagRules
import com.tiji.studyclock.domain.model.ThemeMode
import com.tiji.studyclock.domain.model.Verdict
import java.time.LocalDate

/**
 * 恢复前的一次性完整性检查：只有全部通过才允许清库，避免“恢复到一半才发现坏数据”。
 * 所有提示都面向用户，不出现表名、字段名或内部枚举值。
 */
object BackupValidator {
    private val examKeys = ExamType.entries.map { it.key }.toSet()
    private val sourceNames = SessionSource.entries.map { it.name }.toSet()
    private val prescriptionTypes = PrescriptionType.entries.map { it.name }.toSet()
    private val prescriptionStatuses = PrescriptionStatus.entries.map { it.name }.toSet()
    private val verdicts = Verdict.entries.map { it.name }.toSet()
    private val attributions = DateAttribution.entries.map { it.name }.toSet()
    private val themes = ThemeMode.entries.map { it.name }.toSet()
    private val modes = RecordMode.entries.map { it.name }.toSet()
    private val defaultModes = setOf(RecordMode.COUNT.name, RecordMode.SCORE.name)
    private val kinds = SessionKind.entries.map { it.name }.toSet()
    private val colorPattern = Regex("^#[0-9A-Fa-f]{6}$")

    fun validate(payload: BackupPayload): List<String> {
        val errors = linkedSetOf<String>()
        if (payload.schemaVersion !in BackupCodec.MIN_SCHEMA_VERSION..BackupCodec.SCHEMA_VERSION) {
            errors += "备份文件版本与当前应用不一致，未恢复。"
        }
        if (payload.exportedAt <= 0L) errors += "备份文件缺少导出时间。"
        if (payload.appVersion.isBlank()) errors += "备份文件缺少应用版本。"
        if (payload.subjects.isEmpty()) errors += "备份中没有可恢复的科目。"

        val subjects = payload.subjects.associateBy { it.id }
        if (!uniquePositiveIds(payload.subjects.map { it.id })) {
            errors += "备份中的科目编号重复或无效。"
        }
        payload.subjects.forEach { subject ->
            if (subject.name.isBlank()) errors += "备份中的科目名称不合法。"
            if (subject.examType !in examKeys) errors += "备份中的考试类型无法识别。"
            if (!colorPattern.matches(subject.colorHex)) errors += "备份中的科目颜色格式不正确。"
        }

        val categories = payload.categories.associateBy { it.id }
        if (!uniquePositiveIds(payload.categories.map { it.id })) {
            errors += "备份中的类别编号重复或无效。"
        }
        payload.categories.forEach { category ->
            if (category.name.isBlank()) errors += "备份中的类别名称不合法。"
            if (category.subjectId !in subjects) errors += "备份中的类别没有对应科目。"
            if (category.defaultMode !in defaultModes) errors += "备份中的默认记录方式无法识别。"
            val remembered = category.defaultMaxScoreTenths
            if (remembered != null && remembered !in 1..ScoreRules.MAX_SCORE_TENTHS) {
                errors += "备份中的默认满分不合法。"
            }
        }

        val sessions = payload.sessions.associateBy { it.id }
        if (!uniquePositiveIds(payload.sessions.map { it.id })) {
            errors += "备份中的记录编号重复或无效。"
        }
        payload.sessions.forEach { session ->
            if (session.examType !in examKeys) errors += "备份中的考试类型无法识别。"
            if (session.source !in sourceNames) errors += "备份中的记录来源无法识别。"
            if (session.startAt <= 0L) errors += "备份中的记录时间不合法。"
            if (session.endAt != null && session.endAt < session.startAt) {
                errors += "备份中的记录结束时间早于开始时间。"
            }
            if (session.durationSec != null && session.durationSec <= 0) {
                errors += "备份中的记录时长不合法。"
            }
            if (session.pausedSec < 0 || session.pauseCount < 0) {
                errors += "备份中的暂停数据不合法。"
            }
            if (session.mode == RecordMode.FOCUS.name) {
                if (session.totalCount != 0 || session.correctCount != 0) {
                    errors += "备份中的专注记录带有题量或答对数。"
                }
                if (session.durationSec == null || session.durationSec <= 0) {
                    errors += "备份中的专注记录缺少时长。"
                }
            } else if (session.totalCount <= 0 || session.correctCount !in 0..session.totalCount) {
                errors += "备份中的题量或答对数不合法。"
            }
            if (session.difficulty !in 0..3 || session.energy !in 0..3) {
                errors += "备份中的自评数据不合法。"
            }
            val category = categories[session.categoryId]
            val subject = subjects[session.subjectId]
            if (category == null || subject == null || category.subjectId != session.subjectId) {
                errors += "备份中的记录与科目或类别对应不上。"
            } else if (subject.examType != session.examType) {
                errors += "备份中的记录与科目所属考试不一致。"
            }
            val sequence = session.answerSequence
            if (sequence != null && (
                sequence.length != session.totalCount ||
                    sequence.any { it != '0' && it != '1' } ||
                    sequence.count { it == '1' } != session.correctCount
                )
            ) {
                errors += "备份中的答题序列与题量或答对数不一致。"
            }
            val tags = session.wrongTags
            if (tags != null && (
                TagRules.error(tags) != null ||
                    TagRules.normalize(tags) != tags
                )
            ) {
                errors += "备份中的错题关键词格式不正确。"
            }
            // ---- v5：模式、性质与计分字段 ----
            if (session.mode !in modes || session.kind !in kinds) {
                errors += "备份中的记录模式无法识别。"
            }
            if (session.mode == RecordMode.SCORE.name) {
                if (ScoreRules.error(session.scoreTenths, session.maxScoreTenths, session.scoreLabel) != null) {
                    errors += "备份中的得分或满分不合法。"
                }
                if (session.answerSequence != null) errors += "备份中的计分记录带有对错序列。"
                if (session.kind == SessionKind.NORMAL.name && session.correctCount != 0) {
                    errors += "备份中的计分记录答对数不合法。"
                }
            } else if (session.mode == RecordMode.FOCUS.name) {
                if (session.scoreTenths != null || session.maxScoreTenths != null || session.scoreLabel != null) {
                    errors += "备份中的专注记录带有计分字段。"
                }
                if (session.answerSequence != null) errors += "备份中的专注记录带有对错序列。"
                if (session.kind != SessionKind.NORMAL.name) errors += "备份中的专注记录带有复做性质。"
                if (session.wrongTags != null) errors += "备份中的专注记录带有错题关键词。"
            } else if (
                session.scoreTenths != null || session.maxScoreTenths != null || session.scoreLabel != null
            ) {
                errors += "备份中的客观题记录带有计分字段。"
            }
            if (session.kind == SessionKind.NORMAL.name && session.batchId != null) {
                errors += "备份中的普通记录带有错题袋关联。"
            }
        }

        val batches = payload.wrongBatches.associateBy { it.id }
        if (!uniquePositiveIds(payload.wrongBatches.map { it.id })) {
            errors += "备份中的错题袋编号重复或无效。"
        }
        if (payload.wrongBatches.map { it.originSessionId }.size !=
            payload.wrongBatches.map { it.originSessionId }.toSet().size
        ) {
            errors += "备份中存在来源重复的错题袋。"
        }
        payload.wrongBatches.forEach { batch ->
            val origin = sessions[batch.originSessionId]
            if (origin == null) {
                errors += "备份中的错题袋没有对应的原始记录。"
            } else {
                if (origin.categoryId != batch.categoryId || origin.subjectId != batch.subjectId) {
                    errors += "备份中的错题袋与原始记录的科目类别不一致。"
                }
                if (origin.mode == RecordMode.COUNT.name && origin.kind == SessionKind.NORMAL.name &&
                    batch.wrongCount != origin.totalCount - origin.correctCount
                ) {
                    errors += "备份中的错题袋题数与原始记录不一致。"
                }
            }
            if (batch.categoryId !in categories) errors += "备份中的错题袋没有对应类别。"
            if (batch.examType !in examKeys) errors += "备份中的考试类型无法识别。"
            if (batch.wrongCount <= 0) errors += "备份中的错题袋题数不合法。"
            if (batch.createdAt <= 0L) errors += "备份中的错题袋时间不合法。"
            runCatching { LocalDate.parse(batch.originDate) }.getOrNull()
                ?: run { errors += "备份中的错题袋日期格式不正确。" }
            if (batch.note != null && batch.note.length > ScoreRules.MAX_NOTE_LENGTH) {
                errors += "备份中的错题袋备注过长。"
            }
        }

        if (!uniquePositiveIds(payload.reviewAttempts.map { it.id })) {
            errors += "备份中的复做轮次编号重复或无效。"
        }
        payload.reviewAttempts.forEach { attempt ->
            if (attempt.batchId !in batches) errors += "备份中的复做轮次没有对应错题袋。"
            val linked = sessions[attempt.sessionId]
            if (linked == null) {
                errors += "备份中的复做轮次没有对应记录。"
            } else {
                if (linked.kind != SessionKind.REDO.name) errors += "备份中的复做轮次关联了普通记录。"
                if (linked.batchId != attempt.batchId) errors += "备份中的复做轮次与记录的错题袋不一致。"
                if (linked.correctCount != attempt.passedCount || linked.totalCount != attempt.attemptedCount) {
                    errors += "备份中的复做轮次与记录的题数或通过数不一致。"
                }
            }
            if (attempt.attemptedCount <= 0 || attempt.passedCount !in 0..attempt.attemptedCount) {
                errors += "备份中的复做轮次题数不合法。"
            }
            if (attempt.createdAt <= 0L) errors += "备份中的复做轮次时间不合法。"
        }
        // 每个已挂接的 REDO 会话恰好一条事件（CSV 导入的未挂接行除外）。
        payload.sessions.count { it.kind == SessionKind.REDO.name && it.batchId != null }.let { linkedRedo ->
            if (linkedRedo != payload.reviewAttempts.size) {
                errors += "备份中的复做记录与轮次数量不一致。"
            }
        }
        payload.sessions.forEach { session ->
            val batchId = session.batchId ?: return@forEach
            if (batchId !in batches) errors += "备份中的记录关联了不存在的错题袋。"
        }

        val prescriptions = payload.prescriptions.associateBy { it.id }
        if (!uniquePositiveIds(payload.prescriptions.map { it.id })) {
            errors += "备份中的处方编号重复或无效。"
        }
        payload.prescriptions.forEach { prescription ->
            if (prescription.categoryId !in categories) errors += "备份中的处方没有对应类别。"
            if (prescription.batchId != null && prescription.batchId !in batches) {
                errors += "备份中的处方关联了不存在的错题袋。"
            }
            if (prescription.type !in prescriptionTypes || prescription.status !in prescriptionStatuses) {
                errors += "备份中的处方状态无法识别。"
            }
            if (prescription.targetCount <= 0) errors += "备份中的处方题量不合法。"
            if (prescription.targetMinutes != null && prescription.targetMinutes <= 0) {
                errors += "备份中的处方时长不合法。"
            }
            if (prescription.targetAccuracy != null && prescription.targetAccuracy !in 0.0..1.0) {
                errors += "备份中的处方正确率不合法。"
            }
            if (prescription.attribution.isBlank() || prescription.reason.isBlank()) {
                errors += "备份中的处方说明不完整。"
            }
            if (prescription.createdAt <= 0L || prescription.dueAt <= 0L) {
                errors += "备份中的处方时间不合法。"
            }
            val started = prescription.startedAt
            val completed = prescription.completedAt
            if ((started != null && started <= 0L) || (completed != null && completed <= 0L)) {
                errors += "备份中的处方时间不合法。"
            }
            if (started != null && completed != null && completed < started) {
                errors += "备份中的处方完成时间早于开始时间。"
            }
        }

        payload.sessions.forEach { session ->
            val prescriptionId = session.prescriptionId ?: return@forEach
            val prescription = prescriptions[prescriptionId]
            if (prescription == null) {
                errors += "备份中的记录关联了不存在的处方。"
            } else if (prescription.categoryId != session.categoryId) {
                errors += "备份中的记录与处方类别不一致。"
            }
        }

        if (!uniquePositiveIds(payload.prescriptionResults.map { it.id })) {
            errors += "备份中的处方结果编号重复或无效。"
        }
        payload.prescriptionResults.forEach { result ->
            if (result.prescriptionId !in prescriptions) {
                errors += "备份中的处方结果没有对应处方。"
            }
            if (result.verdict !in verdicts) errors += "备份中的处方效果无法识别。"
            if (result.beforeCount < 0 || result.afterCount < 0) {
                errors += "备份中的处方样本数不合法。"
            }
            listOf(result.beforeAccuracy, result.afterAccuracy).forEach { accuracy ->
                if (accuracy != null && accuracy !in 0.0..1.0) errors += "备份中的正确率不合法。"
            }
            listOf(result.beforeSecPerQ, result.afterSecPerQ).forEach { seconds ->
                if (seconds != null && seconds < 0.0) errors += "备份中的单题时间不合法。"
            }
            if (result.evaluatedAt <= 0L) errors += "备份中的处方评估时间不合法。"
            if ((result.beforeSessionIds + result.afterSessionIds).any { it !in sessions }) {
                errors += "备份中的处方结果引用了不存在的记录。"
            }
        }

        val planTypes = payload.examPlans.map { it.examType }
        if (planTypes.size != planTypes.toSet().size) errors += "备份中的考试计划重复。"
        payload.examPlans.forEach { plan ->
            if (plan.examType !in examKeys) errors += "备份中的考试类型无法识别。"
            if (plan.examDate != null) {
                val parsed = runCatching { LocalDate.parse(plan.examDate) }.getOrNull()
                if (parsed == null) errors += "备份中的考试日期格式不正确。"
            }
            if (plan.targetScore != null && plan.targetScore <= 0) {
                errors += "备份中的目标分数不合法。"
            }
            val targetMax = plan.targetMaxScoreTenths
            if (targetMax != null && targetMax !in 1..ScoreRules.MAX_SCORE_TENTHS) {
                errors += "备份中的目标满分口径不合法。"
            }
        }

        payload.settings?.let { settings ->
            if (settings.dailyQuestionGoal != null && settings.dailyQuestionGoal <= 0) {
                errors += "备份中的每日题量目标不合法。"
            }
            if (settings.dailyMinuteGoal != null && settings.dailyMinuteGoal <= 0) {
                errors += "备份中的每日分钟目标不合法。"
            }
            if (settings.dateAttribution !in attributions) errors += "备份中的记录归属设置无法识别。"
            if (settings.theme !in themes) errors += "备份中的外观设置无法识别。"
        }
        if (payload.currentExam != null && payload.currentExam !in examKeys) {
            errors += "备份中的当前考试无法识别。"
        }

        return errors.toList()
    }

    fun requireValid(payload: BackupPayload) {
        validate(payload).firstOrNull()?.let { throw IllegalArgumentException(it) }
    }

    private fun uniquePositiveIds(ids: List<Long>): Boolean =
        ids.all { it > 0L } && ids.size == ids.toSet().size
}
