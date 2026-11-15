package com.tiji.studyclock.data.backup

import com.tiji.studyclock.data.db.CategoryEntity
import com.tiji.studyclock.data.db.ExamPlanEntity
import com.tiji.studyclock.data.db.ReviewAttemptEntity
import com.tiji.studyclock.data.db.WrongBatchEntity
import com.tiji.studyclock.data.db.PrescriptionEntity
import com.tiji.studyclock.data.db.PrescriptionResultEntity
import com.tiji.studyclock.data.db.SessionEntity
import com.tiji.studyclock.data.db.SubjectEntity
import com.tiji.studyclock.domain.model.DateAttribution
import com.tiji.studyclock.domain.model.ExamType
import com.tiji.studyclock.domain.model.PrescriptionStatus
import com.tiji.studyclock.domain.model.PrescriptionType
import com.tiji.studyclock.domain.model.RecordMode
import com.tiji.studyclock.domain.model.SessionKind
import com.tiji.studyclock.domain.model.SessionSource
import com.tiji.studyclock.domain.model.ThemeMode
import com.tiji.studyclock.domain.model.UserSettings
import com.tiji.studyclock.domain.model.Verdict

internal fun SubjectEntity.toRecord() = SubjectRecord(
    id = id, name = name, colorHex = colorHex, sortOrder = sortOrder,
    archived = archived, examType = examType,
)

internal fun SubjectRecord.toEntity() = SubjectEntity(
    id = id, name = name, colorHex = colorHex, sortOrder = sortOrder,
    archived = archived, examType = ExamType.fromKey(examType).key,
)

internal fun CategoryEntity.toRecord() = CategoryRecord(
    id = id, subjectId = subjectId, name = name, groupName = groupName,
    sortOrder = sortOrder, archived = archived,
    defaultMode = defaultMode, defaultMaxScoreTenths = defaultMaxScoreTenths,
)

internal fun CategoryRecord.toEntity() = CategoryEntity(
    id = id, subjectId = subjectId, name = name, groupName = groupName,
    sortOrder = sortOrder, archived = archived,
    defaultMode = defaultMode, defaultMaxScoreTenths = defaultMaxScoreTenths,
)

internal fun SessionEntity.toRecord() = SessionRecord(
    id = id, categoryId = categoryId, subjectId = subjectId, startAt = startAt,
    endAt = endAt, durationSec = durationSec, pausedSec = pausedSec, pauseCount = pauseCount,
    totalCount = totalCount, correctCount = correctCount, answerSequence = answerSequence,
    wrongTags = wrongTags, difficulty = difficulty, energy = energy,
    source = source.name,
    prescriptionId = prescriptionId, examType = examType,
    createdAt = createdAt, updatedAt = updatedAt,
    mode = mode, kind = kind, scoreTenths = scoreTenths, maxScoreTenths = maxScoreTenths,
    scoreLabel = scoreLabel, batchId = batchId,
)

internal fun SessionRecord.toEntity() = SessionEntity(
    id = id, categoryId = categoryId, subjectId = subjectId, startAt = startAt,
    endAt = endAt, durationSec = durationSec, pausedSec = pausedSec, pauseCount = pauseCount,
    totalCount = totalCount, correctCount = correctCount, answerSequence = answerSequence,
    wrongTags = wrongTags, difficulty = difficulty, energy = energy,
    source = SessionSource.valueOf(source),
    prescriptionId = prescriptionId, examType = ExamType.fromKey(examType).key,
    createdAt = createdAt, updatedAt = updatedAt,
    mode = RecordMode.fromKey(mode).name, kind = SessionKind.fromKey(kind).name,
    scoreTenths = scoreTenths, maxScoreTenths = maxScoreTenths,
    scoreLabel = scoreLabel, batchId = batchId,
)

internal fun PrescriptionEntity.toRecord() = PrescriptionRecord(
    id = id, categoryId = categoryId, type = type.name, targetCount = targetCount,
    targetMinutes = targetMinutes, targetAccuracy = targetAccuracy,
    attribution = attribution, reason = reason, status = status.name,
    createdAt = createdAt, startedAt = startedAt, completedAt = completedAt,
    dueAt = dueAt, invalidatedReason = invalidatedReason, batchId = batchId,
)

internal fun PrescriptionRecord.toEntity() = PrescriptionEntity(
    id = id, categoryId = categoryId, type = PrescriptionType.valueOf(type),
    targetCount = targetCount, targetMinutes = targetMinutes, targetAccuracy = targetAccuracy,
    attribution = attribution, reason = reason, status = PrescriptionStatus.valueOf(status),
    createdAt = createdAt, startedAt = startedAt, completedAt = completedAt,
    dueAt = dueAt, invalidatedReason = invalidatedReason, batchId = batchId,
)

internal fun PrescriptionResultEntity.toRecord() = PrescriptionResultRecord(
    id = id, prescriptionId = prescriptionId,
    beforeAccuracy = beforeAccuracy, afterAccuracy = afterAccuracy,
    beforeSecPerQ = beforeSecPerQ, afterSecPerQ = afterSecPerQ,
    beforeCount = beforeCount, afterCount = afterCount,
    beforeSessionIds = beforeSessionIds, afterSessionIds = afterSessionIds,
    verdict = verdict.name, evaluatedAt = evaluatedAt,
)

internal fun PrescriptionResultRecord.toEntity() = PrescriptionResultEntity(
    id = id, prescriptionId = prescriptionId,
    beforeAccuracy = beforeAccuracy, afterAccuracy = afterAccuracy,
    beforeSecPerQ = beforeSecPerQ, afterSecPerQ = afterSecPerQ,
    beforeCount = beforeCount, afterCount = afterCount,
    beforeSessionIds = beforeSessionIds, afterSessionIds = afterSessionIds,
    verdict = Verdict.valueOf(verdict), evaluatedAt = evaluatedAt,
)

internal fun ExamPlanEntity.toRecord() = ExamPlanRecord(
    examType = examType, name = name, examDate = examDate, targetScore = targetScore,
    targetMaxScoreTenths = targetMaxScoreTenths,
)

internal fun ExamPlanRecord.toEntity() = ExamPlanEntity(
    examType = ExamType.fromKey(examType).key, name = name, examDate = examDate, targetScore = targetScore,
    targetMaxScoreTenths = targetMaxScoreTenths,
)

internal fun WrongBatchEntity.toRecord() = WrongBatchRecord(
    id = id, originSessionId = originSessionId, categoryId = categoryId, subjectId = subjectId,
    examType = examType, wrongCount = wrongCount, wrongTags = wrongTags, note = note,
    originDate = originDate, archived = archived, backfilled = backfilled, createdAt = createdAt,
)

internal fun WrongBatchRecord.toEntity() = WrongBatchEntity(
    id = id, originSessionId = originSessionId, categoryId = categoryId, subjectId = subjectId,
    examType = ExamType.fromKey(examType).key, wrongCount = wrongCount, wrongTags = wrongTags,
    note = note, originDate = originDate, archived = archived, backfilled = backfilled,
    createdAt = createdAt,
)

internal fun ReviewAttemptEntity.toRecord() = ReviewAttemptRecord(
    id = id, batchId = batchId, sessionId = sessionId,
    attemptedCount = attemptedCount, passedCount = passedCount, createdAt = createdAt,
)

internal fun ReviewAttemptRecord.toEntity() = ReviewAttemptEntity(
    id = id, batchId = batchId, sessionId = sessionId,
    attemptedCount = attemptedCount, passedCount = passedCount, createdAt = createdAt,
)

internal fun UserSettings.toRecord() = SettingsRecord(
    dailyQuestionGoal = dailyQuestionGoal,
    dailyMinuteGoal = dailyMinuteGoal,
    selfAssessment = selfAssessment,
    dateAttribution = dateAttribution.name,
    theme = theme.name,
)

internal fun SettingsRecord.toSettings() = UserSettings(
    dailyQuestionGoal = dailyQuestionGoal,
    dailyMinuteGoal = dailyMinuteGoal,
    selfAssessment = selfAssessment,
    dateAttribution = DateAttribution.entries.first { it.name == dateAttribution },
    theme = ThemeMode.entries.first { it.name == theme },
)
