package com.tiji.studyclock.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.tiji.studyclock.domain.model.PrescriptionStatus
import com.tiji.studyclock.domain.model.PrescriptionType
import com.tiji.studyclock.domain.model.SessionSource
import com.tiji.studyclock.domain.model.Verdict

@Entity(tableName = "subject", indices = [Index(value = ["examType", "name"], unique = true)])
data class SubjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorHex: String,
    val sortOrder: Int,
    val archived: Boolean = false,
    /** [com.tiji.studyclock.domain.model.ExamType.key]；schema v3 起科目归属某个考试，名称只在该考试内唯一。 */
    @ColumnInfo(defaultValue = "CIVIL_SERVANT")
    val examType: String = "CIVIL_SERVANT",
)

@Entity(
    tableName = "category",
    foreignKeys = [ForeignKey(
        entity = SubjectEntity::class,
        parentColumns = ["id"], childColumns = ["subjectId"],
        onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.RESTRICT,
    )],
    indices = [Index(value = ["subjectId", "name"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subjectId: Long,
    val name: String,
    val groupName: String? = null,
    val sortOrder: Int = 0,
    val archived: Boolean = false,
    /** v5：结算页预填的记录模式；迁移按主观题类别 ID 回填 SCORE。 */
    @ColumnInfo(defaultValue = "COUNT")
    val defaultMode: String = "COUNT",
    /** v5：用户首次录入计分后按类别记忆的常用满分，null 表示未设置。 */
    val defaultMaxScoreTenths: Int? = null,
)

@Entity(
    tableName = "session",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"], childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = SubjectEntity::class,
            parentColumns = ["id"], childColumns = ["subjectId"],
            onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = PrescriptionEntity::class,
            parentColumns = ["id"], childColumns = ["prescriptionId"],
            onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["categoryId", "startAt"]),
        Index(value = ["startAt"]),
        Index(value = ["subjectId"]),
        Index(value = ["prescriptionId"]),
        Index(value = ["examType", "startAt"]),
        Index(value = ["mode", "kind", "startAt"]),
        Index(value = ["batchId"]),
    ],
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long,
    val subjectId: Long,
    val startAt: Long,
    val endAt: Long? = null,
    val durationSec: Int? = null,
    val pausedSec: Int = 0,
    val pauseCount: Int = 0,
    val totalCount: Int,
    val correctCount: Int,
    val answerSequence: String? = null,
    /** 错题关键词，`|` 分隔；v4 起记录。 */
    val wrongTags: String? = null,
    val difficulty: Int = 0,
    val energy: Int = 0,
    val source: SessionSource = SessionSource.MANUAL,
    val prescriptionId: Long? = null,
    /** 记录归属的考试类型。由类别→科目推导后落库，切换考试时不靠联表也能快速筛选。 */
    @ColumnInfo(defaultValue = "CIVIL_SERVANT")
    val examType: String = "CIVIL_SERVANT",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    // ---- v5 ----
    @ColumnInfo(defaultValue = "COUNT")
    val mode: String = "COUNT",
    @ColumnInfo(defaultValue = "NORMAL")
    val kind: String = "NORMAL",
    val scoreTenths: Int? = null,
    val maxScoreTenths: Int? = null,
    val scoreLabel: String? = null,
    val batchId: Long? = null,
)

@Entity(
    tableName = "prescription",
    foreignKeys = [ForeignKey(
        entity = CategoryEntity::class,
        parentColumns = ["id"], childColumns = ["categoryId"],
        onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.RESTRICT,
    )],
    indices = [Index(value = ["categoryId"])],
)
data class PrescriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long,
    val type: PrescriptionType,
    val targetCount: Int,
    val targetMinutes: Int? = null,
    val targetAccuracy: Double? = null,
    val attribution: String,
    val reason: String,
    val status: PrescriptionStatus = PrescriptionStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val dueAt: Long,
    val invalidatedReason: String? = null,
    /** v5：复习/攻坚处方指向的错题袋；不声明外键，避免迁移期重建表。 */
    val batchId: Long? = null,
)

@Entity(
    tableName = "prescription_result",
    foreignKeys = [ForeignKey(
        entity = PrescriptionEntity::class,
        parentColumns = ["id"], childColumns = ["prescriptionId"],
        onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.RESTRICT,
    )],
    indices = [Index(value = ["prescriptionId"], unique = true)],
)
data class PrescriptionResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val prescriptionId: Long,
    val beforeAccuracy: Double? = null,
    val afterAccuracy: Double? = null,
    val beforeSecPerQ: Double? = null,
    val afterSecPerQ: Double? = null,
    val beforeCount: Int,
    val afterCount: Int,
    val beforeSessionIds: List<Long>,
    val afterSessionIds: List<Long>,
    val verdict: Verdict,
    val evaluatedAt: Long = System.currentTimeMillis(),
)

/**
 * v5 错题袋。剩余量、状态、消化率**不落库**，由 ReviewAttempt 事件派生（D17）。
 * originSessionId 唯一约束保证一次练习只生成一个袋。
 */
@Entity(
    tableName = "wrong_batch",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"], childColumns = ["originSessionId"],
            onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"], childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["originSessionId"], unique = true, name = "index_wrong_batch_originSessionId"),
        Index(value = ["examType", "categoryId"], name = "index_wrong_batch_exam_category"),
        Index(value = ["categoryId"], name = "index_wrong_batch_categoryId"),
        Index(value = ["originDate"], name = "index_wrong_batch_originDate"),
    ],
)
data class WrongBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originSessionId: Long,
    val categoryId: Long,
    val subjectId: Long,
    @ColumnInfo(defaultValue = "CIVIL_SERVANT")
    val examType: String = "CIVIL_SERVANT",
    val wrongCount: Int,
    val wrongTags: String? = null,
    val note: String? = null,
    val originDate: String,
    val archived: Boolean = false,
    val backfilled: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

/** v5 一轮重做；sessionId 唯一，与 REDO 会话一一对应，同事务写入/删除/恢复。 */
@Entity(
    tableName = "review_attempt",
    foreignKeys = [
        ForeignKey(
            entity = WrongBatchEntity::class,
            parentColumns = ["id"], childColumns = ["batchId"],
            onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"], childColumns = ["sessionId"],
            onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["sessionId"], unique = true, name = "index_review_attempt_sessionId"),
        Index(value = ["batchId", "createdAt"], name = "index_review_attempt_batch"),
    ],
)
data class ReviewAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val batchId: Long,
    val sessionId: Long,
    val attemptedCount: Int,
    val passedCount: Int,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "exam_plan")
data class ExamPlanEntity(
    /** [com.tiji.studyclock.domain.model.ExamType.key]；每个考试类型一份计划，互不覆盖。 */
    @PrimaryKey val examType: String,
    /** 考试名称由用户决定，空串表示没起名，展示时回退到类型标签。 */
    val name: String = "",
    val examDate: String? = null,
    val targetScore: Int? = null,
    /** v5：目标分的满分口径；为空时不显示目标分差（D22）。 */
    val targetMaxScoreTenths: Int? = null,
)
