package com.tiji.studyclock.data.repo

import androidx.room.withTransaction
import com.tiji.studyclock.data.db.AppDatabase
import com.tiji.studyclock.data.db.toDomain
import com.tiji.studyclock.data.db.toEntity
import com.tiji.studyclock.domain.model.DateAttribution
import com.tiji.studyclock.domain.model.ReviewAttempt
import com.tiji.studyclock.domain.model.ScoreRules
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.SessionScopes
import com.tiji.studyclock.domain.model.WrongBatch
import com.tiji.studyclock.domain.stats.RedoLedger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class BackfillPreview(
    val batchCount: Int,
    val wrongQuestions: Int,
    val earliest: LocalDate?,
    val latest: LocalDate?,
)

/**
 * 错题袋的读取与显式维护。自动生成/同步在 SessionDao 的写入事务里完成；
 * 这里是 UI 唯一需要调用的入口：归档、备注、历史回填、手动建袋与剩余查询。
 */
@Singleton
class WrongBatchRepository @Inject constructor(private val database: AppDatabase) {
    private val batchDao = database.wrongBatchDao()
    private val attemptDao = database.reviewAttemptDao()
    private val prescriptionDao = database.prescriptionDao()

    val batches: Flow<List<WrongBatch>> = batchDao.observeAll()
        .map { rows -> rows.map { it.toDomain() } }.distinctUntilChanged()

    val attempts: Flow<List<ReviewAttempt>> = attemptDao.observeAll()
        .map { rows -> rows.map { it.toDomain() } }.distinctUntilChanged()

    suspend fun batch(id: Long): WrongBatch? = batchDao.getById(id)?.toDomain()

    suspend fun remaining(id: Long): Int {
        val row = batchDao.getById(id) ?: return 0
        return maxOf(0, row.wrongCount - attemptDao.forBatch(id).sumOf { it.passedCount })
    }

    /** 归档同时解除处方对该袋的引用；恢复归档不自动重新挂接，避免把旧处方悄悄改回去。 */
    suspend fun setArchived(id: Long, archived: Boolean) {
        database.withTransaction {
            batchDao.setArchived(id, archived)
            if (archived) prescriptionDao.detachBatch(id)
        }
    }

    suspend fun setNote(id: Long, note: String?) {
        require(note == null || note.length <= ScoreRules.MAX_NOTE_LENGTH) { "备注不能超过 ${ScoreRules.MAX_NOTE_LENGTH} 个字" }
        batchDao.setNote(id, note?.takeIf { it.isNotBlank() })
    }

    /** 计分记录没有可定义的「错题数」，只能由用户显式建袋（D16）。 */
    suspend fun createManual(
        originSessionId: Long,
        wrongCount: Int,
        note: String?,
        attribution: DateAttribution,
        session: Session,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Long {
        require(wrongCount > 0) { "错题数必须大于 0" }
        require(batchDao.byOriginSession(originSessionId) == null) { "这条记录已经有错题袋了" }
        return batchDao.insert(
            WrongBatch(
                originSessionId = originSessionId,
                categoryId = session.categoryId,
                subjectId = session.subjectId,
                examType = session.examType,
                wrongCount = wrongCount,
                wrongTags = session.wrongTags,
                note = note,
                originDate = originDate(session, attribution, zoneId),
            ).toEntity(),
        )
    }

    /** 历史回填预览（D18）：只挑 COUNT+NORMAL、有错题、且尚无袋的记录，不改动任何数据。 */
    suspend fun previewBackfill(
        sessions: List<Session>,
        from: LocalDate?,
        to: LocalDate?,
        categoryIds: Set<Long>?,
        attribution: DateAttribution,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): BackfillPreview {
        val rows = eligible(sessions, from, to, categoryIds, attribution, zoneId)
        val dates = rows.map { LocalDate.parse(originDate(it, attribution, zoneId)) }
        return BackfillPreview(
            batchCount = rows.size,
            wrongQuestions = rows.sumOf { it.totalCount - it.correctCount },
            earliest = dates.minOrNull(),
            latest = dates.maxOrNull(),
        )
    }

    /** 确认后批量建袋，唯一索引保证每条来源记录只产生一个袋；回填袋单独标注，不按逾期处理。 */
    suspend fun applyBackfill(
        sessions: List<Session>,
        from: LocalDate?,
        to: LocalDate?,
        categoryIds: Set<Long>?,
        attribution: DateAttribution,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Int {
        val rows = eligible(sessions, from, to, categoryIds, attribution, zoneId)
        if (rows.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val entities = rows.map { session ->
            WrongBatch(
                originSessionId = session.id,
                categoryId = session.categoryId,
                subjectId = session.subjectId,
                examType = session.examType,
                wrongCount = session.totalCount - session.correctCount,
                wrongTags = session.wrongTags,
                originDate = originDate(session, attribution, zoneId),
                backfilled = true,
                createdAt = now,
            ).toEntity()
        }
        // OnConflictStrategy.IGNORE 会让被忽略的行返回 -1；只统计真正插入的 rowId。
        return database.withTransaction { batchDao.insertIgnoring(entities).count { it > 0 } }
    }

    /** 袋明细：轮次时间线由 RedoLedger 派生，不落冗余状态。 */
    suspend fun ledger(id: Long, today: LocalDate, zoneId: ZoneId = ZoneId.systemDefault()) =
        batchDao.getById(id)?.let { row ->
            RedoLedger.ledger(row.toDomain(), attemptDao.forBatch(id).map { it.toDomain() }, today, zoneId)
        }

    private suspend fun eligible(
        sessions: List<Session>,
        from: LocalDate?,
        to: LocalDate?,
        categoryIds: Set<Long>?,
        attribution: DateAttribution,
        zoneId: ZoneId,
    ): List<Session> {
        val existing = batchDao.all().map { it.originSessionId }.toHashSet()
        return SessionScopes.performance(sessions)
            .filter { it.id > 0 && it.totalCount > it.correctCount && it.id !in existing }
            .filter { categoryIds == null || it.categoryId in categoryIds }
            .filter { session ->
                val date = LocalDate.parse(originDate(session, attribution, zoneId))
                (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to))
            }
    }

    private fun originDate(session: Session, attribution: DateAttribution, zoneId: ZoneId): String {
        val millis = if (attribution == DateAttribution.END) session.endAt ?: session.startAt else session.startAt
        return Instant.ofEpochMilli(millis).atZone(zoneId).toLocalDate().toString()
    }
}
