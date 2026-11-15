package com.tiji.studyclock.domain.model

/**
 * v5 起一条记录由两个**正交**维度描述：
 * [RecordMode] 决定表现量纲，[SessionKind] 决定这条记录是不是「已见过的题」。
 * 正交是三轨联动成立的前提：计分题也能复做，客观题复做也能顺手记分。
 */
enum class RecordMode {
    /** 客观题：totalCount / correctCount，进主正确率。 */
    COUNT,

    /** 计分题：scoreTenths / maxScoreTenths，只进得分轨。 */
    SCORE,

    /**
     * 专注：只记一段学习时长（D27）。
     * 题量与答对数固定为 0，无得分、无序列、无关键词、无错题袋；进投入面，不进任何表现轨。
     */
    FOCUS,
    ;

    companion object {
        fun fromKey(raw: String?): RecordMode = entries.firstOrNull { it.name == raw } ?: COUNT
    }
}

enum class SessionKind {
    /** 普通练习。 */
    NORMAL,

    /** 错题袋的一轮重做；计入题量但不计入主正确率（D19）。 */
    REDO,
    ;

    companion object {
        fun fromKey(raw: String?): SessionKind = entries.firstOrNull { it.name == raw } ?: NORMAL
    }
}

/**
 * 批次错题袋：一次 COUNT + NORMAL 且有错题的练习生成一个袋。
 * 项目没有题库，袋里不存题号或题干，note 只用于写「第 3 套 · 资料分析」这类定位信息。
 */
data class WrongBatch(
    val id: Long = 0,
    /** 来源会话；唯一约束保证一次练习不会生成两个袋。 */
    val originSessionId: Long,
    val categoryId: Long,
    val subjectId: Long,
    val examType: ExamType = ExamType.CIVIL_SERVANT,
    val wrongCount: Int,
    val wrongTags: String? = null,
    val note: String? = null,
    /** 按 DateAttribution 折算后的 ISO 本地日期。 */
    val originDate: String,
    val archived: Boolean = false,
    /** 显式回填生成的袋标记为 true，列表里注明「来自历史补建」，不按逾期处理。 */
    val backfilled: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

/** 一轮重做 = 一条 REDO 会话 + 一条事件，二者同事务写入/删除/恢复（D17）。 */
data class ReviewAttempt(
    val id: Long = 0,
    val batchId: Long,
    val sessionId: Long,
    val attemptedCount: Int,
    val passedCount: Int,
    val createdAt: Long = System.currentTimeMillis(),
)
