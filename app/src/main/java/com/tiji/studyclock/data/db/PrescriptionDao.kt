package com.tiji.studyclock.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
abstract class PrescriptionDao {
    @Query("SELECT * FROM prescription ORDER BY createdAt DESC, id DESC")
    abstract fun observeAll(): Flow<List<PrescriptionEntity>>

    @Query("SELECT * FROM prescription_result")
    abstract fun observeResults(): Flow<List<PrescriptionResultEntity>>

    @Query("SELECT * FROM prescription WHERE id = :id")
    abstract suspend fun getById(id: Long): PrescriptionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(row: PrescriptionEntity): Long

    /** 开始：保留第一次的开始时间，重复点击不会把它推后。 */
    @Query(
        "UPDATE prescription SET status = 'STARTED', startedAt = COALESCE(startedAt, :at) " +
            "WHERE id = :id AND status IN ('PENDING', 'STARTED')",
    )
    abstract suspend fun markStarted(id: Long, at: Long): Int

    /** 未开始就直接完成后补记开始时间，验证仍以“完成前/完成后”为界。 */
    @Query(
        "UPDATE prescription SET status = 'DONE', completedAt = :at, startedAt = COALESCE(startedAt, :at) " +
            "WHERE id = :id AND status IN ('PENDING', 'STARTED')",
    )
    abstract suspend fun markDone(id: Long, at: Long): Int

    @Query("UPDATE prescription SET status = 'SKIPPED' WHERE id = :id AND status IN ('PENDING', 'STARTED')")
    abstract suspend fun markSkipped(id: Long): Int

    /** 计时被放弃：把同类别还没结束的处方退回待开始，避免卡在“进行中”。 */
    @Query(
        "UPDATE prescription SET status = 'PENDING', startedAt = NULL " +
            "WHERE categoryId = :categoryId AND status = 'STARTED'",
    )
    abstract suspend fun revertStarted(categoryId: Long): Int

    /** 到点未开始即过期；已开始的不自动结束，交给用户完成或跳过。 */
    @Query("UPDATE prescription SET status = 'EXPIRED' WHERE status = 'PENDING' AND dueAt < :now")
    abstract suspend fun expireOverdue(now: Long): Int

    @Query("SELECT * FROM prescription_result WHERE prescriptionId = :id")
    abstract suspend fun resultFor(id: Long): PrescriptionResultEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertResult(row: PrescriptionResultEntity): Long

    @Query("SELECT * FROM session WHERE categoryId = :categoryId ORDER BY startAt, id")
    abstract suspend fun sessionsFor(categoryId: Long): List<SessionEntity>

    /** D26：关联错题袋被归档时解除关联，不静默失效；详情层会注明原因。 */
    @Query("UPDATE prescription SET batchId = NULL WHERE batchId = :batchId")
    abstract suspend fun detachBatch(batchId: Long): Int
}
