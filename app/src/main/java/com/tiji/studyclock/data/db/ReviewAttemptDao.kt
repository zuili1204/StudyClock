package com.tiji.studyclock.data.db

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 复做事件只读；写入与删除必须和 REDO 会话在 SessionDao 的同一事务里完成。 */
@Dao
interface ReviewAttemptDao {
    @Query("SELECT * FROM review_attempt ORDER BY createdAt ASC, id ASC")
    fun observeAll(): Flow<List<ReviewAttemptEntity>>

    @Query("SELECT * FROM review_attempt ORDER BY createdAt ASC, id ASC")
    suspend fun all(): List<ReviewAttemptEntity>

    @Query("SELECT * FROM review_attempt WHERE batchId = :batchId ORDER BY createdAt ASC, id ASC")
    suspend fun forBatch(batchId: Long): List<ReviewAttemptEntity>

    @Query("SELECT * FROM review_attempt WHERE sessionId = :sessionId")
    suspend fun forSession(sessionId: Long): ReviewAttemptEntity?
}
