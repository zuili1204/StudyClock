package com.tiji.studyclock.domain.model

/**
 * 计分口径的唯一规则源（纯 JVM，D21）。
 * 分数一律以 **0.1 分为单位的整数** 存储与运算，UI 只允许 1 位小数，
 * 避免 7.5 + 0.5 这类浮点累加误差，也覆盖雅思 9.0 / 申论 100 分等不同量级。
 */
object ScoreRules {
    /** 1000.0 分；超过视为录入错误，不是产品能力上限。 */
    const val MAX_SCORE_TENTHS = 10_000
    const val MAX_LABEL_LENGTH = 20
    const val MAX_NOTE_LENGTH = 40

    private val ONE_DECIMAL = Regex("^\\d{1,4}(?:\\.\\d)?$")

    /** 把「7.5」「32」「9.0」解析成 tenths；非法输入返回 null，不抛异常。 */
    fun parseTenths(raw: String?): Int? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!ONE_DECIMAL.matches(text)) return null
        val parts = text.split('.')
        val whole = parts[0].toIntOrNull() ?: return null
        val fraction = if (parts.size > 1) parts[1].toIntOrNull() ?: return null else 0
        val value = whole * 10 + fraction
        return value.takeIf { it in 0..MAX_SCORE_TENTHS }
    }

    /** 展示用：75 → "7.5"，90 → "9.0"，1000 → "100.0"。 */
    fun format(tenths: Int): String = String.format(java.util.Locale.CHINA, "%d.%d", tenths / 10, kotlin.math.abs(tenths % 10))

    /** 得分率，只在展示与 G 轨计算，不入库；无满分或满分非法时返回 null。 */
    fun rate(scoreTenths: Int?, maxScoreTenths: Int?): Double? {
        val score = scoreTenths ?: return null
        val max = maxScoreTenths ?: return null
        if (max <= 0 || score < 0 || score > max) return null
        return score.toDouble() / max
    }

    /** 校验一条计分记录的分数段；返回 null 表示通过。 */
    fun error(scoreTenths: Int?, maxScoreTenths: Int?, label: String?): String? = when {
        maxScoreTenths == null -> "请选择或填写满分"
        maxScoreTenths <= 0 || maxScoreTenths > MAX_SCORE_TENTHS -> "满分超出允许范围"
        scoreTenths == null -> "请填写得分"
        scoreTenths < 0 || scoreTenths > maxScoreTenths -> "得分必须在 0 到满分之间"
        label != null && label.length > MAX_LABEL_LENGTH -> "计分项目不能超过 $MAX_LABEL_LENGTH 个字"
        else -> null
    }

    /** 常用满分档（单位：0.1 分），只作快捷选择，不预置到任何类别。 */
    val COMMON_MAX_SCORES: List<Int> = listOf(90, 150, 200, 250, 400, 1000)

    fun labelFor(tenths: Int): String = format(tenths)
}
