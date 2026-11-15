package com.tiji.studyclock.domain.model

/**
 * 已落库的一条训练处方。由 [com.tiji.studyclock.domain.prescribe.PrescriptionPlanner] 生成后写入，
 * 随后只允许按 待开始 → 进行中 → 完成 或 跳过/过期/作废 的方向流转。
 */
data class Prescription(
    val id: Long = 0,
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
    /** v5：复习/攻坚处方指向的错题袋，可一键跳到该袋重做（D26）。 */
    val batchId: Long? = null,
) {
    val isActive: Boolean
        get() = status == PrescriptionStatus.PENDING || status == PrescriptionStatus.STARTED
}

/** 完成后按前后各 3 次、各 32 题给出的验证结果；只在结论不是“样本不足”时落库。 */
data class PrescriptionResult(
    val id: Long = 0,
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
