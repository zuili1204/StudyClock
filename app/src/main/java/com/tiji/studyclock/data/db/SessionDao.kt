package com.tiji.studyclock.data.db

import android.util.Log
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.tiji.studyclock.BuildConfig
import com.tiji.studyclock.data.validateSession
import com.tiji.studyclock.domain.model.PrescriptionStatus
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

/** 删除时被连带移除的关联，撤销时原样恢复，保证「删袋不丢账」（D17/R-3）。 */
data class DeletedSessionLinks(
    val attempt: ReviewAttemptEntity? = null,
    val batch: WrongBatchEntity? = null,
)

@Dao
abstract class SessionDao {
    @Query("SELECT * FROM session ORDER BY startAt DESC, id DESC")
    abstract fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM session WHERE id = :id")
    abstract suspend fun getById(id: Long): SessionEntity?

    /**
     * 单事务写入：会话 → 复做事件 → 错题袋同步。
     * [originDate] 由 Repository 按用户选择的 DateAttribution 折算；留空时按开始时刻的系统本地日期。
     */
    @Transaction
    open suspend fun insert(session: SessionEntity, originDate: String = ""): Long {
        require(session.id == 0L) { "新增记录必须使用 id = 0；撤销请使用 restore" }
        val validated = validated(session)
        val examType = validateCategory(validated)
        validatePrescription(validated, inserting = true)
        validateRedoLink(validated, excludeSessionId = null)
        val id = insertRow(validated.copy(examType = examType))
        syncAttempt(id, validated, existing = null)
        syncBatch(id, validated, originDate, existing = null)
        return id
    }

    @Transaction
    open suspend fun update(session: SessionEntity, originDate: String = ""): Int {
        require(session.id > 0) { "更新记录需要有效 ID" }
        val existing = requireNotNull(getById(session.id)) { "记录不存在，不能更新" }
        var validated = validated(session)
        validated = validated.copy(examType = validateCategory(validated))
        val categoryChanged = existing.categoryId != validated.categoryId
        if (categoryChanged) {
            require(validated.prescriptionId == null || validated.prescriptionId == existing.prescriptionId) {
                "编辑类别时不能同时关联另一处方"
            }
            // 已有复做事件的错题袋不能改类别，否则袋与类别的冗余字段会漂。
            val batch = batchByOrigin(session.id)
            if (batch != null) {
                require(attemptCount(batch.id) == 0) { "该记录的错题袋已有复做记录，不能改类别" }
            }
            val reason = "关联记录 ${session.id} 的类别已由 ${existing.categoryId} 改为 ${validated.categoryId}"
            existing.prescriptionId?.let { invalidatePrescription(it, reason) }
            // Evaluation samples use frozen ID lists rather than foreign keys. Invalidate those too.
            invalidateResultsUsingSession(session.id, reason)
            validated = validated.copy(prescriptionId = null)
        } else {
            require(validated.prescriptionId == existing.prescriptionId) { "不能直接修改记录的处方关联" }
            validatePrescription(validated, inserting = false)
        }
        require(existing.kind == validated.kind) { "不能把普通记录与复做记录互相转换" }
        validateRedoLink(validated, excludeSessionId = validated.id)
        check(updateRow(validated) == 1) { "记录更新失败" }
        syncAttempt(validated.id, validated, existing = attemptBySession(validated.id))
        syncBatch(validated.id, validated, originDate, existing = batchByOrigin(validated.id))
        return 1
    }

    /**
     * 删除普通记录时同步删除尚未被复做过的错题袋；删除复做记录时同步删除事件。
     * 返回的关联对象供 5 秒撤销原样恢复，禁止只删一边（R-3）。
     */
    @Transaction
    open suspend fun delete(id: Long): DeletedSessionLinks {
        require(id > 0) { "删除记录需要有效 ID" }
        val existing = getById(id) ?: return DeletedSessionLinks()
        require(existing.prescriptionId == null && !isUsedInResult(id)) {
            "关联处方或验证结果的记录暂不支持删除"
        }
        val attempt = attemptBySession(id)
        val batch = batchByOrigin(id)
        if (batch != null && attemptCount(batch.id) > 0) {
            // 复做记录应先各自删除；袋本体在所有轮次清空后才可随原记录删除。
            require(redoCountForBatch(batch.id) == 0) { "请先删除该错题袋的复做记录" }
        }
        if (attempt != null) deleteAttemptBySession(id)
        if (batch != null) deleteBatch(batch.id)
        deleteRow(id)
        return DeletedSessionLinks(attempt, batch)
    }

    /** 撤销删除：同 ID 恢复会话，并按原样恢复事件或错题袋。 */
    @Transaction
    open suspend fun restore(session: SessionEntity, links: DeletedSessionLinks = DeletedSessionLinks()): Long {
        require(session.id > 0) { "撤销删除需要原记录 ID" }
        require(session.prescriptionId == null && !isUsedInResult(session.id)) {
            "仅支持撤销普通记录的删除"
        }
        val validated = validated(session)
        validateCategory(validated)
        val id = insertRow(validated)
        links.attempt?.let { insertAttempt(it.copy(id = 0, sessionId = id)) }
        links.batch?.let { insertBatch(it.copy(id = 0, originSessionId = id)) }
        return id
    }

    private fun validated(session: SessionEntity): SessionEntity = validateSession(session.toDomain()) { reason ->
        if (BuildConfig.DEBUG) Log.d("SessionDao", "已清空不合规答题序列：$reason")
    }.toEntity()

    /** 校验科目一致性，并返回科目所属的考试类型；写入时以此为准，避免调用方传错。 */
    private suspend fun validateCategory(session: SessionEntity): String {
        val subjectId = requireNotNull(categorySubjectId(session.categoryId)) { "类别不存在" }
        require(subjectId == session.subjectId) { "记录科目与类别所属科目不一致" }
        return requireNotNull(subjectExamType(subjectId)) { "科目不存在" }
    }

    private suspend fun validatePrescription(session: SessionEntity, inserting: Boolean) {
        val id = session.prescriptionId ?: return
        val prescription = requireNotNull(findPrescription(id)) { "处方不存在" }
        require(prescription.categoryId == session.categoryId) { "记录类别与处方类别不一致" }
        require(!inserting || prescription.status != PrescriptionStatus.INVALIDATED) { "不能关联已作废处方" }
    }

    /** REDO 会话必须指向存在的袋，且本轮题数不得超过「轮前剩余」（D17）。 */
    private suspend fun validateRedoLink(session: SessionEntity, excludeSessionId: Long?) {
        if (session.kind != "REDO") return
        // 导入的 REDO 行没有挂接袋，跳过账本校验；它仍计入 REDO 轨的通过率。
        val batchId = session.batchId ?: return
        val batch = requireNotNull(findBatch(batchId)) { "错题袋不存在，不能登记复做" }
        require(batch.categoryId == session.categoryId) { "复做记录类别与错题袋不一致" }
        val excluded = excludeSessionId?.let { attemptBySession(it)?.passedCount ?: 0 } ?: 0
        val remaining = maxOf(0, batch.wrongCount - (sumPassed(batchId) - excluded))
        require(session.totalCount <= remaining) { "本轮题数 ${session.totalCount} 超过错题袋剩余 $remaining 道" }
    }

    /** 一条 REDO 会话恰好一条 ReviewAttempt，passed 与 correctCount 同源。 */
    private suspend fun syncAttempt(sessionId: Long, session: SessionEntity, existing: ReviewAttemptEntity?) {
        if (session.kind != "REDO" || session.batchId == null) {
            if (existing != null) deleteAttemptBySession(sessionId)
            return
        }
        val row = ReviewAttemptEntity(
            id = existing?.id ?: 0,
            batchId = session.batchId,
            sessionId = sessionId,
            attemptedCount = session.totalCount,
            passedCount = session.correctCount,
            createdAt = existing?.createdAt ?: session.createdAt,
        )
        if (existing == null) insertAttempt(row) else updateAttempt(row)
    }

    /** COUNT + NORMAL 且有错题时自动建袋；编辑原记录时同步 wrongCount，永远只有一个袋。 */
    private suspend fun syncBatch(
        sessionId: Long,
        session: SessionEntity,
        originDate: String,
        existing: WrongBatchEntity?,
    ) {
        val wrong = session.totalCount - session.correctCount
        val eligible = session.mode == "COUNT" && session.kind == "NORMAL"
        if (!eligible || wrong <= 0) {
            if (existing != null) {
                require(attemptCount(existing.id) == 0) { "该错题袋已有复做记录，不能改成无错题" }
                deleteBatch(existing.id)
            }
            return
        }
        val date = originDate.ifBlank { fallbackDate(session) }
        if (existing == null) {
            insertBatch(
                WrongBatchEntity(
                    originSessionId = sessionId,
                    categoryId = session.categoryId,
                    subjectId = session.subjectId,
                    examType = session.examType,
                    wrongCount = wrong,
                    wrongTags = session.wrongTags,
                    originDate = date,
                    createdAt = session.createdAt,
                ),
            )
        } else {
            require(attemptCount(existing.id) == 0 || existing.categoryId == session.categoryId) {
                "该错题袋已有复做记录，不能改类别"
            }
            updateBatch(
                existing.copy(
                    categoryId = session.categoryId,
                    subjectId = session.subjectId,
                    examType = session.examType,
                    wrongCount = wrong,
                    wrongTags = session.wrongTags,
                    originDate = date,
                ),
            )
        }
    }

    /** 无显式日期归属时的兜底：按开始时刻的系统本地日期，不读 DataStore，保持 DAO 无偏好依赖。 */
    private fun fallbackDate(session: SessionEntity): String =
        Instant.ofEpochMilli(session.startAt).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertRow(session: SessionEntity): Long

    /**
     * 导入专用的批量写入：调用方已在纯校验层校验过字段与类别映射，这里不再逐行查询。
     * 外键与唯一约束仍由数据库把关，事务由 ImportRepository 包住。
     * 导入不自动建袋（CSV 不表达袋与复做关联），由用户在应用内显式回填。
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertImported(sessions: List<SessionEntity>): List<Long>

    @Update(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun updateRow(session: SessionEntity): Int

    @Query("DELETE FROM session WHERE id = :id")
    protected abstract suspend fun deleteRow(id: Long): Int

    @Query("SELECT subjectId FROM category WHERE id = :id")
    protected abstract suspend fun categorySubjectId(id: Long): Long?

    @Query("SELECT examType FROM subject WHERE id = :subjectId")
    protected abstract suspend fun subjectExamType(subjectId: Long): String?

    @Query("SELECT * FROM prescription WHERE id = :id")
    protected abstract suspend fun findPrescription(id: Long): PrescriptionEntity?

    @Query("UPDATE prescription SET status = 'INVALIDATED', invalidatedReason = :reason WHERE id = :id")
    protected abstract suspend fun invalidatePrescription(id: Long, reason: String): Int

    @Query("""
        SELECT EXISTS(SELECT 1 FROM prescription_result
        WHERE instr(',' || beforeSessionIds || ',', ',' || :id || ',') > 0
           OR instr(',' || afterSessionIds || ',', ',' || :id || ',') > 0)
    """)
    protected abstract suspend fun isUsedInResult(id: Long): Boolean

    @Query("""
        UPDATE prescription SET status = 'INVALIDATED', invalidatedReason = :reason
        WHERE id IN (SELECT prescriptionId FROM prescription_result
        WHERE instr(',' || beforeSessionIds || ',', ',' || :id || ',') > 0
           OR instr(',' || afterSessionIds || ',', ',' || :id || ',') > 0)
    """)
    protected abstract suspend fun invalidateResultsUsingSession(id: Long, reason: String): Int

    // ---- wrong_batch / review_attempt：与会话同事务，禁止跨 DAO 单独写 ----
    @Query("SELECT * FROM wrong_batch WHERE id = :id")
    protected abstract suspend fun findBatch(id: Long): WrongBatchEntity?

    @Query("SELECT * FROM wrong_batch WHERE originSessionId = :sessionId")
    protected abstract suspend fun batchByOrigin(sessionId: Long): WrongBatchEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertBatch(row: WrongBatchEntity): Long

    @Update(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun updateBatch(row: WrongBatchEntity): Int

    @Query("DELETE FROM wrong_batch WHERE id = :id")
    protected abstract suspend fun deleteBatch(id: Long): Int

    @Query("SELECT COALESCE(SUM(passedCount), 0) FROM review_attempt WHERE batchId = :batchId")
    protected abstract suspend fun sumPassed(batchId: Long): Int

    @Query("SELECT COUNT(*) FROM review_attempt WHERE batchId = :batchId")
    protected abstract suspend fun attemptCount(batchId: Long): Int

    @Query("SELECT COUNT(*) FROM session WHERE kind = 'REDO' AND batchId = :batchId")
    protected abstract suspend fun redoCountForBatch(batchId: Long): Int

    @Query("SELECT * FROM review_attempt WHERE sessionId = :sessionId")
    protected abstract suspend fun attemptBySession(sessionId: Long): ReviewAttemptEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertAttempt(row: ReviewAttemptEntity): Long

    @Update(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun updateAttempt(row: ReviewAttemptEntity): Int

    @Query("DELETE FROM review_attempt WHERE sessionId = :sessionId")
    protected abstract suspend fun deleteAttemptBySession(sessionId: Long): Int
}
