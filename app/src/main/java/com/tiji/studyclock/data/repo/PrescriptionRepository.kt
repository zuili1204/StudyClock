package com.tiji.studyclock.data.repo

import com.tiji.studyclock.data.db.AppDatabase
import com.tiji.studyclock.data.db.toDomain
import com.tiji.studyclock.data.db.toEntity
import com.tiji.studyclock.domain.model.Prescription
import com.tiji.studyclock.domain.model.PrescriptionResult
import com.tiji.studyclock.domain.model.PrescriptionStatus
import com.tiji.studyclock.domain.model.PrescriptionType
import com.tiji.studyclock.domain.model.Verdict
import com.tiji.studyclock.domain.prescribe.PlannedPrescription
import com.tiji.studyclock.domain.prescribe.PrescriptionEvaluation
import com.tiji.studyclock.domain.prescribe.PrescriptionEvaluator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 处方状态机与验证落库。生成层只给计划，这里负责写入、开始、完成、跳过、过期，
 * 并在完成后样本足够时冻结一次验证结果（唯一约束保证只写一次）。
 */
@Singleton
class PrescriptionRepository @Inject constructor(database: AppDatabase) {
    private val dao = database.prescriptionDao()
    private val batchDao = database.wrongBatchDao()
    private val attemptDao = database.reviewAttemptDao()

    val prescriptions: Flow<List<Prescription>> = dao.observeAll()
        .map { rows -> rows.map { it.toDomain() } }
        .distinctUntilChanged()

    val results: Flow<List<PrescriptionResult>> = dao.observeResults()
        .map { rows -> rows.map { it.toDomain() } }
        .distinctUntilChanged()

    suspend fun create(plans: List<PlannedPrescription>): List<Long> = plans.map { plan ->
        require(plan.targetCount > 0) { "处方题量必须大于 0" }
        // D26：复习/攻坚处方优先指向该类别「有未消化错题」的最老一袋，可一键跳过去重做。
        val batchId = if (plan.type == PrescriptionType.REVIEW || plan.type == PrescriptionType.KNOWLEDGE) {
            pendingBatchForCategory(plan.categoryId)
        } else null
        dao.insert(
            Prescription(
                categoryId = plan.categoryId,
                type = plan.type,
                targetCount = plan.targetCount,
                targetMinutes = plan.targetMinutes,
                targetAccuracy = plan.targetAccuracy,
                attribution = plan.attribution,
                reason = plan.reason,
                dueAt = plan.dueAt,
                batchId = batchId,
            ).toEntity(),
        )
    }

    /** remaining 由事件派生，不读冗余状态；all() 已按原始日期升序，最老未消化袋优先。 */
    private suspend fun pendingBatchForCategory(categoryId: Long): Long? {
        val attempts = attemptDao.all()
        return batchDao.all().firstOrNull { batch ->
            !batch.archived && batch.categoryId == categoryId &&
                batch.wrongCount - attempts.filter { it.batchId == batch.id }.sumOf { it.passedCount } > 0
        }?.id
    }

    suspend fun start(id: Long, at: Long = System.currentTimeMillis()): Boolean =
        id > 0 && dao.markStarted(id, at) > 0

    suspend fun complete(id: Long, at: Long = System.currentTimeMillis()): Boolean =
        id > 0 && dao.markDone(id, at) > 0

    suspend fun skip(id: Long): Boolean = id > 0 && dao.markSkipped(id) > 0

    suspend fun revertStarted(categoryId: Long): Boolean = categoryId > 0 && dao.revertStarted(categoryId) > 0

    suspend fun expireOverdue(now: Long = System.currentTimeMillis()): Int = dao.expireOverdue(now)

    /**
     * 只对已完成的处方计算；样本不足时返回说明但不落库，避免把半成品结论写进备份。
     */
    suspend fun evaluateAndRecord(id: Long): PrescriptionEvaluation? {
        val row = dao.getById(id) ?: return null
        if (row.status != PrescriptionStatus.DONE) return null
        // 已冻结的结果不再重算；详情页的即时验证走纯函数，不会改动这条记录。
        if (dao.resultFor(id) != null) return null
        val startedAt = row.startedAt ?: row.completedAt ?: return null
        val completedAt = row.completedAt ?: return null
        val sessions = dao.sessionsFor(row.categoryId).map { it.toDomain() }
        val evaluation = PrescriptionEvaluator.evaluate(sessions, row.categoryId, startedAt, completedAt)
        if (evaluation.verdict != Verdict.INSUFFICIENT) {
            dao.insertResult(
                PrescriptionResult(
                    prescriptionId = id,
                    beforeAccuracy = evaluation.beforeAccuracy,
                    afterAccuracy = evaluation.afterAccuracy,
                    beforeSecPerQ = evaluation.beforeSecPerQ,
                    afterSecPerQ = evaluation.afterSecPerQ,
                    beforeCount = evaluation.beforeQuestions,
                    afterCount = evaluation.afterQuestions,
                    beforeSessionIds = evaluation.beforeSessionIds,
                    afterSessionIds = evaluation.afterSessionIds,
                    verdict = evaluation.verdict,
                ).toEntity(),
            )
        }
        return evaluation
    }
}
