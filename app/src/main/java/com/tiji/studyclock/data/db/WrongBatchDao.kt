package com.tiji.studyclock.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** 错题袋的读取与显式维护；自动生成/同步走 SessionDao 的同一事务。 */
@Dao
interface WrongBatchDao {
    @Query("SELECT * FROM wrong_batch ORDER BY originDate ASC, id ASC")
    fun observeAll(): Flow<List<WrongBatchEntity>>

    @Query("SELECT * FROM wrong_batch WHERE examType = :examType ORDER BY originDate ASC, id ASC")
    fun observeForExam(examType: String): Flow<List<WrongBatchEntity>>

    @Query("SELECT * FROM wrong_batch ORDER BY originDate ASC, id ASC")
    suspend fun all(): List<WrongBatchEntity>

    @Query("SELECT * FROM wrong_batch WHERE id = :id")
    suspend fun getById(id: Long): WrongBatchEntity?

    @Query("SELECT * FROM wrong_batch WHERE originSessionId = :sessionId")
    suspend fun byOriginSession(sessionId: Long): WrongBatchEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: WrongBatchEntity): Long

    /** 历史回填：已有袋的记录由唯一索引静默跳过，不产生第二个袋。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(rows: List<WrongBatchEntity>): List<Long>

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(row: WrongBatchEntity): Int

    @Query("UPDATE wrong_batch SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean): Int

    @Query("UPDATE wrong_batch SET note = :note WHERE id = :id")
    suspend fun setNote(id: Long, note: String?): Int

    @Query("DELETE FROM wrong_batch WHERE id = :id")
    suspend fun delete(id: Long): Int
}
