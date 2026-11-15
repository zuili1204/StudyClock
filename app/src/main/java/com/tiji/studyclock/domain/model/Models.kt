package com.tiji.studyclock.domain.model

import java.time.LocalDate

data class Subject(
    val id: Long,
    val name: String,
    val colorHex: String,
    val sortOrder: Int,
    val archived: Boolean = false,
    val examType: ExamType = ExamType.CIVIL_SERVANT,
    /**
     * v0.12：是否预置基线。预置项只归档、不改名、不删除（D34）。
     * 由数据层按种子 ID 集合填充，不依赖名称或 ID 区间猜测。
     */
    val isPreset: Boolean = false,
)

data class Category(
    val id: Long,
    val subjectId: Long,
    val name: String,
    val groupName: String? = null,
    val sortOrder: Int = 0,
    val archived: Boolean = false,
    /** v5：该类别在结算页的默认记录模式，只是预填建议，用户可随时改。 */
    val defaultMode: RecordMode = RecordMode.COUNT,
    /** v5：首次录入计分后按类别记忆的常用满分（0.1 分单位），null 表示未设置、不猜。 */
    val defaultMaxScoreTenths: Int? = null,
    /** v0.12：是否预置基线；语义同 [Subject.isPreset]。 */
    val isPreset: Boolean = false,
)

enum class SessionSource { TIMER, MANUAL, IMPORT }

data class Session(
    val id: Long = 0,
    val categoryId: Long,
    val subjectId: Long,
    val startAt: Long,
    val endAt: Long? = null,
    val durationSec: Int? = null,
    val pausedSec: Int = 0,
    val pauseCount: Int = 0,
    val totalCount: Int,
    val correctCount: Int,
    val answerSequence: String? = null,
    /** 错题关键词，`|` 分隔的规范串；null 表示未填写。 */
    val wrongTags: String? = null,
    val difficulty: Int = 0,
    val energy: Int = 0,
    val source: SessionSource = SessionSource.MANUAL,
    val prescriptionId: Long? = null,
    /** 归属考试类型，由科目推导；用于切换考试时的隔离。 */
    val examType: ExamType = ExamType.CIVIL_SERVANT,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    // ---- v5：模式、性质、计分与复做关联。全部带默认值，旧记录含义不变。 ----
    val mode: RecordMode = RecordMode.COUNT,
    val kind: SessionKind = SessionKind.NORMAL,
    /** 0.1 分单位；非计分记录为 null。 */
    val scoreTenths: Int? = null,
    val maxScoreTenths: Int? = null,
    val scoreLabel: String? = null,
    /** REDO 会话指向的错题袋；仅 JSON 备份保留该关联，CSV 不表达。 */
    val batchId: Long? = null,
) {
    /** SCORE 记录的 correctCount = 0 表示「不适用」，不是「全错」；展示与导出都要按这里判断。 */
    val isScore: Boolean get() = mode == RecordMode.SCORE
    /** FOCUS 记录 totalCount=correctCount=0 同样表示「不适用」；它只贡献净时长。 */
    val isFocus: Boolean get() = mode == RecordMode.FOCUS
    val isRedo: Boolean get() = kind == SessionKind.REDO
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class DateAttribution { START, END }

data class UserSettings(
    val dailyQuestionGoal: Int? = null,
    val dailyMinuteGoal: Int? = null,
    val selfAssessment: Boolean = false,
    val dateAttribution: DateAttribution = DateAttribution.START,
    val theme: ThemeMode = ThemeMode.SYSTEM,
)

/**
 * 常见考试类型。名称仍由用户决定，类型只决定默认叫法与将来的考频/科目模板。
 * 新增类型时只追加，不修改已有 key，避免旧记录被改名。
 */
enum class ExamType(val key: String, val label: String, val defaultName: String) {
    CIVIL_SERVANT("CIVIL_SERVANT", "考公", "公务员考试"),
    POSTGRAD("POSTGRAD", "考研", "硕士研究生考试"),
    TEACHER_CERT("TEACHER_CERT", "教资", "教师资格考试"),
    COMPUTER("COMPUTER", "计算机等级", "计算机等级考试"),
    CET("CET", "四六级", "大学英语四六级"),
    IELTS("IELTS", "雅思", "雅思"),
    OTHER("OTHER", "自定义", "我的考试"),
    ;

    companion object {
        fun fromKey(key: String?): ExamType = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

/**
 * 考试目标。name 为空表示“没起名”，展示时回退到类型标签，这样倒计时会显示“考公”而不是“我的考试”。
 */
data class ExamTarget(
    val type: ExamType = ExamType.OTHER,
    val name: String = "",
    val examDate: LocalDate? = null,
    val targetScore: Int? = null,
    /** v5：目标分的满分口径（0.1 分单位）。只有它与记录满分一致时才显示「距目标分」。 */
    val targetMaxScoreTenths: Int? = null,
) {
    val displayName: String
        get() = name.ifBlank {
            if (type == ExamType.OTHER) type.defaultName else type.label
        }
}

/** 处方结构与组数来自设计书 §6.3：摸底一组、攻坚两组且单组减半、提速/审题各两组、复习一组。 */
enum class PrescriptionType(val sets: Int, val perSet: Int, val label: String) {
    BASELINE(1, 10, "摸底"),
    KNOWLEDGE(2, 5, "攻坚"),
    SPEED(2, 10, "提速"),
    CAREFUL_READING(2, 10, "审题"),
    REVIEW(1, 10, "复习"),
    ;

    val targetCount: Int get() = sets * perSet
}
enum class PrescriptionStatus { PENDING, STARTED, DONE, EXPIRED, SKIPPED, INVALIDATED }
enum class Verdict { EFFECTIVE, NEUTRAL, INEFFECTIVE, INSUFFICIENT }
