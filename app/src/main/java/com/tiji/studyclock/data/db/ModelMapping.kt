package com.tiji.studyclock.data.db

import com.tiji.studyclock.data.seed.CategorySeed
import com.tiji.studyclock.domain.model.Category
import com.tiji.studyclock.domain.model.ExamTarget
import com.tiji.studyclock.domain.model.ExamType
import com.tiji.studyclock.domain.model.Prescription
import com.tiji.studyclock.domain.model.PrescriptionResult
import com.tiji.studyclock.domain.model.RecordMode
import com.tiji.studyclock.domain.model.ReviewAttempt
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.SessionKind
import com.tiji.studyclock.domain.model.Subject
import com.tiji.studyclock.domain.model.WrongBatch
import java.time.LocalDate

internal fun SubjectEntity.toDomain() = Subject(
    id = id, name = name, colorHex = colorHex, sortOrder = sortOrder, archived = archived,
    examType = ExamType.fromKey(examType),
    isPreset = id in CategorySeed.presetSubjectIds,
)
internal fun CategoryEntity.toDomain() = Category(
    id, subjectId, name, groupName, sortOrder, archived,
    // 类别默认方式只有 COUNT / SCORE；历史脏值或误写 FOCUS 一律退回 COUNT，避免补记预填成专注。
    defaultMode = RecordMode.fromKey(defaultMode).takeIf { it != RecordMode.FOCUS } ?: RecordMode.COUNT,
    defaultMaxScoreTenths = defaultMaxScoreTenths,
    isPreset = id in CategorySeed.presetCategoryIds,
)

internal fun SessionEntity.toDomain() = Session(
    id = id, categoryId = categoryId, subjectId = subjectId,
    startAt = startAt, endAt = endAt, durationSec = durationSec,
    pausedSec = pausedSec, pauseCount = pauseCount,
    totalCount = totalCount, correctCount = correctCount, answerSequence = answerSequence,
    wrongTags = wrongTags, difficulty = difficulty, energy = energy, source = source,
    prescriptionId = prescriptionId, examType = ExamType.fromKey(examType),
    createdAt = createdAt, updatedAt = updatedAt,
    mode = RecordMode.fromKey(mode), kind = SessionKind.fromKey(kind),
    scoreTenths = scoreTenths, maxScoreTenths = maxScoreTenths,
    scoreLabel = scoreLabel, batchId = batchId,
)

internal fun Session.toEntity() = SessionEntity(
    id = id, categoryId = categoryId, subjectId = subjectId,
    startAt = startAt, endAt = endAt, durationSec = durationSec,
    pausedSec = pausedSec, pauseCount = pauseCount,
    totalCount = totalCount, correctCount = correctCount, answerSequence = answerSequence,
    wrongTags = wrongTags, difficulty = difficulty, energy = energy, source = source,
    prescriptionId = prescriptionId, examType = examType.key,
    createdAt = createdAt, updatedAt = updatedAt,
    mode = mode.name, kind = kind.name, scoreTenths = scoreTenths, maxScoreTenths = maxScoreTenths,
    scoreLabel = scoreLabel, batchId = batchId,
)

internal fun PrescriptionEntity.toDomain() = Prescription(
    id = id, categoryId = categoryId, type = type,
    targetCount = targetCount, targetMinutes = targetMinutes, targetAccuracy = targetAccuracy,
    attribution = attribution, reason = reason, status = status,
    createdAt = createdAt, startedAt = startedAt, completedAt = completedAt,
    dueAt = dueAt, invalidatedReason = invalidatedReason, batchId = batchId,
)

internal fun Prescription.toEntity() = PrescriptionEntity(
    id = id, categoryId = categoryId, type = type,
    targetCount = targetCount, targetMinutes = targetMinutes, targetAccuracy = targetAccuracy,
    attribution = attribution, reason = reason, status = status,
    createdAt = createdAt, startedAt = startedAt, completedAt = completedAt,
    dueAt = dueAt, invalidatedReason = invalidatedReason, batchId = batchId,
)

internal fun PrescriptionResultEntity.toDomain() = PrescriptionResult(
    id = id, prescriptionId = prescriptionId,
    beforeAccuracy = beforeAccuracy, afterAccuracy = afterAccuracy,
    beforeSecPerQ = beforeSecPerQ, afterSecPerQ = afterSecPerQ,
    beforeCount = beforeCount, afterCount = afterCount,
    beforeSessionIds = beforeSessionIds, afterSessionIds = afterSessionIds,
    verdict = verdict, evaluatedAt = evaluatedAt,
)

internal fun PrescriptionResult.toEntity() = PrescriptionResultEntity(
    id = id, prescriptionId = prescriptionId,
    beforeAccuracy = beforeAccuracy, afterAccuracy = afterAccuracy,
    beforeSecPerQ = beforeSecPerQ, afterSecPerQ = afterSecPerQ,
    beforeCount = beforeCount, afterCount = afterCount,
    beforeSessionIds = beforeSessionIds, afterSessionIds = afterSessionIds,
    verdict = verdict, evaluatedAt = evaluatedAt,
)

internal fun ExamPlanEntity.toDomain() = ExamTarget(
    type = ExamType.fromKey(examType),
    name = name,
    examDate = examDate?.let(LocalDate::parse),
    targetScore = targetScore,
    targetMaxScoreTenths = targetMaxScoreTenths,
)

internal fun ExamTarget.toEntity() = ExamPlanEntity(
    examType = type.key, name = name, examDate = examDate?.toString(), targetScore = targetScore,
    targetMaxScoreTenths = targetMaxScoreTenths,
)

internal fun WrongBatchEntity.toDomain() = WrongBatch(
    id = id, originSessionId = originSessionId, categoryId = categoryId, subjectId = subjectId,
    examType = ExamType.fromKey(examType), wrongCount = wrongCount, wrongTags = wrongTags,
    note = note, originDate = originDate, archived = archived, backfilled = backfilled,
    createdAt = createdAt,
)

internal fun WrongBatch.toEntity() = WrongBatchEntity(
    id = id, originSessionId = originSessionId, categoryId = categoryId, subjectId = subjectId,
    examType = examType.key, wrongCount = wrongCount, wrongTags = wrongTags,
    note = note, originDate = originDate, archived = archived, backfilled = backfilled,
    createdAt = createdAt,
)

internal fun ReviewAttemptEntity.toDomain() = ReviewAttempt(
    id = id, batchId = batchId, sessionId = sessionId,
    attemptedCount = attemptedCount, passedCount = passedCount, createdAt = createdAt,
)

internal fun ReviewAttempt.toEntity() = ReviewAttemptEntity(
    id = id, batchId = batchId, sessionId = sessionId,
    attemptedCount = attemptedCount, passedCount = passedCount, createdAt = createdAt,
)
