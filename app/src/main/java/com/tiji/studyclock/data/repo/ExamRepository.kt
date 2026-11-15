package com.tiji.studyclock.data.repo

import com.tiji.studyclock.data.db.AppDatabase
import com.tiji.studyclock.data.db.toDomain
import com.tiji.studyclock.data.db.toEntity
import com.tiji.studyclock.domain.model.ExamTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExamRepository @Inject constructor(database: AppDatabase) {
    private val dao = database.examPlanDao()

    /** 所有考试类型各自的计划；没有行时由上层按类型补默认值。 */
    val plans: Flow<List<ExamTarget>> = dao.observePlans()
        .map { rows -> rows.map { it.toDomain() } }.distinctUntilChanged()

    suspend fun save(target: ExamTarget) {
        dao.save(target.toEntity())
    }
}
