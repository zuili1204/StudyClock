package com.tiji.studyclock.data.export

/**
 * CSV 写出：只做格式化与转义，不重算任何指标。
 * v5 起固定 20 列，1–14 列顺序、列名完全不变；UTF-8 BOM、RFC4180 转义、公式注入防护延续。
 */
object CsvWriter {
    const val BOM = "\uFEFF"
    private const val CRLF = "\r\n"
    private val HEADER = listOf(
        "日期", "科目", "类别", "题目", "正确", "正确率",
        "时间", "净时长", "净时长秒", "单题秒数", "对错序列", "难度", "精力", "错题关键词",
        "记录模式", "记录性质", "计分项目", "得分", "满分", "得分率",
    )

    /** 设置页等入口用它写文案，避免列数改动后界面还在说旧数字。 */
    val columnCount: Int get() = HEADER.size

    fun write(model: ReportModel): String = buildString {
        append(BOM)
        append(HEADER.joinToString(",")).append(CRLF)
        model.rows.forEach { row ->
            append(
                listOf(
                    row.date, row.subject, row.category,
                    row.questionsDisplay, row.correctDisplay, row.accuracy,
                    row.timeRange, row.durationText, row.durationSeconds, row.secondsPerQuestion,
                    row.answerSequence, row.difficulty, row.energy, row.wrongTags,
                    row.mode, row.kind, row.scoreLabel, row.score, row.maxScore, row.scoreRate,
                ).joinToString(",") { escape(it) },
            ).append(CRLF)
        }
    }

    private fun escape(raw: String): String {
        // 以 = + - @ 开头的文本会被表格软件当公式执行，前缀单引号阻断。
        val value = if (raw.isNotEmpty() && raw.first() in "=+-@") "'$raw" else raw
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }
}
