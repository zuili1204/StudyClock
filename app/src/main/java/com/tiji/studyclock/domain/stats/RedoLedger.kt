package com.tiji.studyclock.domain.stats

import com.tiji.studyclock.domain.model.ReviewAttempt
import com.tiji.studyclock.domain.model.WrongBatch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 错题袋状态完全由事件派生，不落库（D17）。 */
enum class BatchStatus { PENDING, DIGESTED, ARCHIVED }

data class BatchLedger(
    val batch: WrongBatch,
    /** 按 createdAt 排序的轮次，轮号即序号，不存冗余 round 字段。 */
    val attempts: List<ReviewAttempt>,
    val passedTotal: Int,
    val attemptedTotal: Int,
    val remaining: Int,
    val status: BatchStatus,
    val digestionRate: Double,
    /** 首轮通过率；没有首轮时为 null。 */
    val firstPassRate: Double?,
    /** 未归档且未消化时，距原始练习日的天数。 */
    val waitingDays: Long?,
    /** 已消化袋的首次复做距原始练习天数。 */
    val daysToDigest: Long?,
) {
    val rounds: Int get() = attempts.size
    val isStalled: Boolean get() = rounds >= 2 && remaining > 0
}

data class RedoMetrics(
    /** R1：未归档袋的 Σ remaining。 */
    val pendingQuestions: Int,
    /** R2：有 ≥1 轮复做的袋 / 全部袋；无袋返回 NULL。 */
    val coverage: Metric<Double>,
    /** R3：首轮 Σpassed / Σattempted；样本不足 NULL。 */
    val firstPassRate: Metric<Double>,
    /** R4：ΣpassedTotal / ΣwrongCount。 */
    val digestionRate: Metric<Double>,
    /** R5：已消化袋的平均消化间隔，≥3 个否则 NULL。 */
    val averageDigestDays: Metric<Double>,
    val totalBatches: Int,
    val digestedBatches: Int,
    val pendingBatches: Int,
    val stalledBatches: Int,
    val totalRounds: Int,
    val oldestPendingDays: Long?,
    /** 未归档袋按类别聚合的待复做题数，供看板 TOP 3。 */
    val pendingByCategory: Map<Long, Int>,
    /** 未归档袋按关键词聚合的待复做题数。 */
    val pendingByTag: Map<String, Int>,
) {
    val hasData: Boolean get() = totalBatches > 0
}

/** R 轨（错题复做）唯一派生出口，纯 JVM。 */
object RedoLedger {
    const val DIGEST_SAMPLE_MIN = 3
    const val FIRST_PASS_MIN_BATCHES = 3
    const val FIRST_PASS_MIN_ATTEMPTED = 15

    fun ledgers(
        batches: List<WrongBatch>,
        attempts: List<ReviewAttempt>,
        today: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<BatchLedger> {
        val byBatch = attempts.groupBy { it.batchId }
        return batches.map { batch -> ledger(batch, byBatch[batch.id].orEmpty(), today, zoneId) }
    }

    fun ledger(
        batch: WrongBatch,
        attempts: List<ReviewAttempt>,
        today: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): BatchLedger {
        val ordered = attempts.sortedWith(compareBy<ReviewAttempt> { it.createdAt }.thenBy { it.id })
        val passed = ordered.sumOf { it.passedCount }
        val attempted = ordered.sumOf { it.attemptedCount }
        val remaining = maxOf(0, batch.wrongCount - passed)
        val origin = runCatching { LocalDate.parse(batch.originDate) }.getOrNull()
        val first = ordered.firstOrNull()
        val firstDate = first?.let {
            Instant.ofEpochMilli(it.createdAt).atZone(zoneId).toLocalDate()
        }
        val status = when {
            batch.archived -> BatchStatus.ARCHIVED
            remaining == 0 -> BatchStatus.DIGESTED
            else -> BatchStatus.PENDING
        }
        return BatchLedger(
            batch = batch,
            attempts = ordered,
            passedTotal = passed,
            attemptedTotal = attempted,
            remaining = remaining,
            status = status,
            digestionRate = if (batch.wrongCount <= 0) 0.0 else passed.toDouble() / batch.wrongCount,
            firstPassRate = first?.let {
                if (it.attemptedCount <= 0) null else it.passedCount.toDouble() / it.attemptedCount
            },
            waitingDays = if (status == BatchStatus.PENDING && origin != null) {
                ChronoUnit.DAYS.between(origin, today).coerceAtLeast(0)
            } else null,
            daysToDigest = if (status == BatchStatus.DIGESTED && origin != null && firstDate != null) {
                ChronoUnit.DAYS.between(origin, firstDate).coerceAtLeast(0)
            } else null,
        )
    }

    fun metrics(
        batches: List<WrongBatch>,
        attempts: List<ReviewAttempt>,
        today: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): RedoMetrics {
        val rows = ledgers(batches, attempts, today, zoneId)
        val active = rows.filter { it.status != BatchStatus.ARCHIVED }
        val covered = rows.count { it.rounds >= 1 }
        val digested = rows.filter { it.status == BatchStatus.DIGESTED }
        val firstRounds = rows.mapNotNull { row ->
            row.attempts.firstOrNull()?.let { it.passedCount to it.attemptedCount }
        }
        val firstAttempted = firstRounds.sumOf { it.second }
        val firstPassed = firstRounds.sumOf { it.first }
        val totalRounds = rows.sumOf { it.rounds }
        val digestDays = digested.mapNotNull { it.daysToDigest }

        return RedoMetrics(
            pendingQuestions = active.sumOf { it.remaining },
            coverage = metric(
                if (rows.isEmpty()) null else covered.toDouble() / rows.size,
                rows.size, totalRounds, "比例", "还没有错题袋",
            ),
            firstPassRate = metric(
                if (firstRounds.size < FIRST_PASS_MIN_BATCHES || firstAttempted < FIRST_PASS_MIN_ATTEMPTED) null
                else firstPassed.toDouble() / firstAttempted,
                firstRounds.size, firstAttempted, "比例", "一次通过率需要至少3袋且15道首轮题",
            ),
            digestionRate = metric(
                if (rows.isEmpty()) null else {
                    val denominator = rows.sumOf { it.batch.wrongCount }
                    if (denominator == 0) null else rows.sumOf { it.passedTotal }.toDouble() / denominator
                },
                rows.size, totalRounds, "比例", "还没有错题袋",
            ),
            averageDigestDays = metric(
                if (digestDays.size < DIGEST_SAMPLE_MIN) null else digestDays.average(),
                digestDays.size, digestDays.size, "天", "平均消化间隔需要至少3个已消化袋",
            ),
            totalBatches = rows.size,
            digestedBatches = digested.size,
            pendingBatches = active.count { it.status == BatchStatus.PENDING },
            stalledBatches = rows.count { it.isStalled },
            totalRounds = totalRounds,
            oldestPendingDays = active.filter { it.status == BatchStatus.PENDING }
                .mapNotNull { it.waitingDays }.maxOrNull(),
            pendingByCategory = active.filter { it.remaining > 0 }
                .groupBy { it.batch.categoryId }
                .mapValues { (_, list) -> list.sumOf { it.remaining } },
            pendingByTag = active.filter { it.remaining > 0 }
                .flatMap { row -> tags(row.batch.wrongTags).map { tag -> tag to row.remaining } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, values) -> values.sum() },
        )
    }

    /** 写入防线：DAO 与备份校验都调用它，禁止出现「通过数 > 做题数」或累计超过袋题数。 */
    fun requireValid(batch: WrongBatch, attempts: List<ReviewAttempt>) {
        require(batch.wrongCount > 0) { "错题袋题数必须大于 0" }
        attempts.forEach {
            require(it.attemptedCount > 0) { "本轮做题数必须大于 0" }
            require(it.passedCount in 0..it.attemptedCount) { "通过数不能超过本轮做题数" }
        }
        require(attempts.sumOf { it.passedCount } <= batch.wrongCount) {
            "累计通过数超过错题袋题数"
        }
    }

    private fun tags(raw: String?): List<String> = raw.orEmpty()
        .split('|').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun <T> metric(
        value: T?,
        sampleCount: Int,
        rounds: Int,
        unit: String,
        reason: String,
    ): Metric<T> = Metric(
        value = value,
        // REDO 轨的可信度按「袋数 + 轮数」判定，不借用 COUNT 轨的题目数。
        level = if (value == null) ConfidenceLevel.INSUFFICIENT else when {
            sampleCount >= Thresholds.TRUSTED_SESSIONS && rounds >= Thresholds.TRUSTED_SESSIONS -> ConfidenceLevel.TRUSTED
            sampleCount >= Thresholds.REFERENCE_SESSIONS && rounds >= Thresholds.REFERENCE_SESSIONS -> ConfidenceLevel.REFERENCE
            else -> ConfidenceLevel.INSUFFICIENT
        },
        questionCount = sampleCount,
        sessionCount = rounds,
        unit = unit,
        unavailableReason = if (value == null) reason else null,
    )
}
