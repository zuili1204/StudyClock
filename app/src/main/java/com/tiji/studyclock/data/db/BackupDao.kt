package com.tiji.studyclock.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** 备份与恢复只做整表读写；界面与统计仍然走各自的 DAO。 */
@Dao
interface BackupDao {
    @Query("SELECT * FROM subject ORDER BY id")
    suspend fun subjects(): List<SubjectEntity>

    @Query("SELECT * FROM category ORDER BY id")
    suspend fun categories(): List<CategoryEntity>

    @Query("SELECT * FROM session ORDER BY id")
    suspend fun sessions(): List<SessionEntity>

    @Query("SELECT * FROM prescription ORDER BY id")
    suspend fun prescriptions(): List<PrescriptionEntity>

    @Query("SELECT * FROM prescription_result ORDER BY id")
    suspend fun prescriptionResults(): List<PrescriptionResultEntity>

    @Query("SELECT * FROM exam_plan ORDER BY examType")
    suspend fun examPlans(): List<ExamPlanEntity>

    @Query("SELECT * FROM wrong_batch ORDER BY id")
    suspend fun wrongBatches(): List<WrongBatchEntity>

    @Query("SELECT * FROM review_attempt ORDER BY id")
    suspend fun reviewAttempts(): List<ReviewAttemptEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSubjects(rows: List<SubjectEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCategories(rows: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSessions(rows: List<SessionEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPrescriptions(rows: List<PrescriptionEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPrescriptionResults(rows: List<PrescriptionResultEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertExamPlans(rows: List<ExamPlanEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWrongBatches(rows: List<WrongBatchEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReviewAttempts(rows: List<ReviewAttemptEntity>)

    /** 清表按外键顺序：先事件、再错题袋、再会话。 */
    @Query("DELETE FROM review_attempt")
    suspend fun clearReviewAttempts()

    @Query("DELETE FROM wrong_batch")
    suspend fun clearWrongBatches()

    @Query("DELETE FROM session")
    suspend fun clearSessions()

    @Query("DELETE FROM prescription_result")
    suspend fun clearPrescriptionResults()

    @Query("DELETE FROM prescription")
    suspend fun clearPrescriptions()

    @Query("DELETE FROM category")
    suspend fun clearCategories()

    @Query("DELETE FROM subject")
    suspend fun clearSubjects()

    @Query("DELETE FROM exam_plan")
    suspend fun clearExamPlans()
}
