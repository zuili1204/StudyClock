package com.tiji.studyclock.data.repo

import com.tiji.studyclock.data.db.AppDatabase
import com.tiji.studyclock.data.db.DeletedSessionLinks
import com.tiji.studyclock.data.db.toDomain
import com.tiji.studyclock.data.db.toEntity
import com.tiji.studyclock.data.requireValidSessionFields
import com.tiji.studyclock.domain.model.DateAttribution
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.TagRules
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepository @Inject constructor(database: AppDatabase) {
    private val dao = database.sessionDao()
    val sessions: Flow<List<Session>> = dao.observeAll()
        .map { rows -> rows.map { it.toDomain() } }.distinctUntilChanged()

    /** 写入口唯一：袋的 originDate 按用户选择的日期归属折算，DAO 在同一事务里同步袋与事件。 */
    suspend fun save(session: Session, attribution: DateAttribution = DateAttribution.START): Long {
        val normalized = session.copy(wrongTags = TagRules.normalize(session.wrongTags))
        requireValidSessionFields(normalized)
        val originDate = originDateOf(normalized, attribution)
        return if (normalized.id == 0L) {
            dao.insert(normalized.toEntity(), originDate)
        } else {
            dao.update(normalized.toEntity(), originDate)
            normalized.id
        }
    }

    /** 返回连带删除的关联，交给 UI 暂存，撤销时原样恢复。 */
    suspend fun delete(session: Session): DeletedSessionLinks = dao.delete(session.id)

    // The UI owns the undo window; the DAO preserves ID and validates again.
    suspend fun restore(session: Session, links: DeletedSessionLinks = DeletedSessionLinks()): Long {
        val normalized = session.copy(wrongTags = TagRules.normalize(session.wrongTags))
        requireValidSessionFields(normalized)
        return dao.restore(normalized.toEntity(), links)
    }

    private fun originDateOf(session: Session, attribution: DateAttribution): String {
        val millis = if (attribution == DateAttribution.END) session.endAt ?: session.startAt else session.startAt
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    }
}
