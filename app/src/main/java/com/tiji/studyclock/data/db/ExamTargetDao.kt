package com.tiji.studyclock.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
abstract class ExamPlanDao {
    @Query("SELECT * FROM exam_plan")
    abstract fun observePlans(): Flow<List<ExamPlanEntity>>

    @Query("SELECT * FROM exam_plan WHERE examType = :examType")
    abstract suspend fun getPlan(examType: String): ExamPlanEntity?

    open suspend fun save(plan: ExamPlanEntity) {
        require(plan.examType.isNotBlank()) { "计划必须绑定考试类型" }
        require(plan.targetScore == null || plan.targetScore > 0) { "目标分数只能为空或正数" }
        plan.examDate?.let { LocalDate.parse(it) }
        upsertRow(plan)
    }

    @Upsert
    protected abstract suspend fun upsertRow(plan: ExamPlanEntity)
}
