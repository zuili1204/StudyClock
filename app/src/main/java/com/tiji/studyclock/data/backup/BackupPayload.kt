package com.tiji.studyclock.data.backup

import kotlinx.serialization.Serializable

/**
 * JSON 全量备份的稳定格式，与 Room 实体分开定义，避免表结构演进而无意改变备份契约。
 * 枚举与考试类型都以 [com.tiji.studyclock.domain.model.ExamType.key] 之类的稳定字符串保存。
 */
@Serializable
data class BackupPayload(
    val schemaVersion: Int,
    val exportedAt: Long,
    val appVersion: String,
    val subjects: List<SubjectRecord>,
    val categories: List<CategoryRecord>,
    val sessions: List<SessionRecord>,
    val prescriptions: List<PrescriptionRecord>,
    val prescriptionResults: List<PrescriptionResultRecord>,
    val examPlans: List<ExamPlanRecord>,
    /** 恢复时允许不导入偏好；为 null 表示这份备份不含设置快照。 */
    val settings: SettingsRecord? = null,
    val currentExam: String? = null,
    /** v5 起：两张新表；旧 v3/v4 备份缺省为空列表，仍可恢复。 */
    val wrongBatches: List<WrongBatchRecord> = emptyList(),
    val reviewAttempts: List<ReviewAttemptRecord> = emptyList(),
)

@Serializable
data class SubjectRecord(
    val id: Long,
    val name: String,
    val colorHex: String,
    val sortOrder: Int,
    val archived: Boolean,
    val examType: String,
)

@Serializable
data class CategoryRecord(
    val id: Long,
    val subjectId: Long,
    val name: String,
    val groupName: String?,
    val sortOrder: Int,
    val archived: Boolean,
    /** v5；旧备份缺省为 COUNT，不改写历史记录。 */
    val defaultMode: String = "COUNT",
    val defaultMaxScoreTenths: Int? = null,
)

@Serializable
data class SessionRecord(
    val id: Long,
    val categoryId: Long,
    val subjectId: Long,
    val startAt: Long,
    val endAt: Long?,
    val durationSec: Int?,
    val pausedSec: Int,
    val pauseCount: Int,
    val totalCount: Int,
    val correctCount: Int,
    val answerSequence: String?,
    /** v4 起记录；旧备份缺省为 null，恢复后仍可正常使用。 */
    val wrongTags: String? = null,
    val difficulty: Int,
    val energy: Int,
    val source: String,
    val prescriptionId: Long?,
    val examType: String,
    val createdAt: Long,
    val updatedAt: Long,
    // ---- v5：全部带默认值，v3/v4 备份缺这些字段时按「客观题 / 普通」解码 ----
    val mode: String = "COUNT",
    val kind: String = "NORMAL",
    val scoreTenths: Int? = null,
    val maxScoreTenths: Int? = null,
    val scoreLabel: String? = null,
    /** CSV 不表达该关联，只有 JSON 备份保留。 */
    val batchId: Long? = null,
)

@Serializable
data class WrongBatchRecord(
    val id: Long,
    val originSessionId: Long,
    val categoryId: Long,
    val subjectId: Long,
    val examType: String,
    val wrongCount: Int,
    val wrongTags: String? = null,
    val note: String? = null,
    val originDate: String,
    val archived: Boolean = false,
    val backfilled: Boolean = false,
    val createdAt: Long,
)

@Serializable
data class ReviewAttemptRecord(
    val id: Long,
    val batchId: Long,
    val sessionId: Long,
    val attemptedCount: Int,
    val passedCount: Int,
    val createdAt: Long,
)

@Serializable
data class PrescriptionRecord(
    val id: Long,
    val categoryId: Long,
    val type: String,
    val targetCount: Int,
    val targetMinutes: Int?,
    val targetAccuracy: Double?,
    val attribution: String,
    val reason: String,
    val status: String,
    val createdAt: Long,
    val startedAt: Long?,
    val completedAt: Long?,
    val dueAt: Long,
    val invalidatedReason: String?,
    /** v5：复习/攻坚处方指向的错题袋。 */
    val batchId: Long? = null,
)

@Serializable
data class PrescriptionResultRecord(
    val id: Long,
    val prescriptionId: Long,
    val beforeAccuracy: Double?,
    val afterAccuracy: Double?,
    val beforeSecPerQ: Double?,
    val afterSecPerQ: Double?,
    val beforeCount: Int,
    val afterCount: Int,
    val beforeSessionIds: List<Long>,
    val afterSessionIds: List<Long>,
    val verdict: String,
    val evaluatedAt: Long,
)

@Serializable
data class ExamPlanRecord(
    val examType: String,
    val name: String,
    val examDate: String?,
    val targetScore: Int?,
    /** v5：目标分的满分口径；null 表示口径不明，不显示目标分差。 */
    val targetMaxScoreTenths: Int? = null,
)

@Serializable
data class SettingsRecord(
    val dailyQuestionGoal: Int?,
    val dailyMinuteGoal: Int?,
    val selfAssessment: Boolean,
    val dateAttribution: String,
    val theme: String,
)

data class BackupSummary(
    val subjects: Int,
    val categories: Int,
    val sessions: Int,
    val prescriptions: Int,
    val examPlans: Int,
    val exportedAt: Long,
    val hasSettings: Boolean,
    /** v5：恢复预览里可见的错题袋数与复做轮数。 */
    val wrongBatches: Int = 0,
    val reviewAttempts: Int = 0,
)
