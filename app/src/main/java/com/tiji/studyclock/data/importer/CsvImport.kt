package com.tiji.studyclock.data.importer

import com.tiji.studyclock.domain.model.NameRules
import com.tiji.studyclock.domain.model.RecordMode
import com.tiji.studyclock.domain.model.ScoreRules
import com.tiji.studyclock.domain.model.SessionKind
import com.tiji.studyclock.domain.model.TagRules
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/** 与导出 CSV 的 14 列对应；导入允许别名与缺列，必填项由 [CsvImport.missingRequired] 判定。 */
enum class ImportField(val label: String, val aliases: Set<String>) {
    DATE("日期", setOf("日期", "date")),
    SUBJECT("科目", setOf("科目", "subject")),
    CATEGORY("类别", setOf("类别", "category", "分类")),
    TOTAL("题量", setOf("题目", "题量", "total", "questions", "count", "数量", "题数")),
    CORRECT("正确数", setOf("正确", "答对", "correct", "正确数")),
    WRONG("错题数", setOf("错题", "错题数", "wrong", "错误数")),
    TIME("时间", setOf("时间", "时段", "time", "时间范围")),
    DURATION_SECONDS("净时长秒", setOf("净时长秒", "时长秒", "durationseconds", "seconds")),
    DURATION_TEXT("净时长", setOf("净时长", "用时", "duration")),
    SEQUENCE("对错序列", setOf("对错序列", "序列", "answersequence", "sequence")),
    DIFFICULTY("难度", setOf("难度", "difficulty")),
    ENERGY("精力", setOf("精力", "energy")),
    TAGS("错题关键词", setOf("错题关键词", "关键词", "标签", "tags", "keywords")),
    // ---- v5 追加 6 列；旧 14 列文件缺这些列时按「客观题 / 普通」导入 ----
    MODE("记录模式", setOf("记录模式", "模式", "mode", "recordmode")),
    KIND("记录性质", setOf("记录性质", "性质", "kind", "sessionkind")),
    SCORE_LABEL("计分项目", setOf("计分项目", "项目", "scorelabel", "item")),
    SCORE("得分", setOf("得分", "score", "得分值")),
    MAX_SCORE("满分", setOf("满分", "maxscore", "总分")),
    SCORE_RATE("得分率", setOf("得分率", "scorate", "得分比例")),
}

data class ImportMapping(val columns: Map<ImportField, Int> = emptyMap())

data class SourceRow(val line: Int, val cells: List<String>)

data class CsvTable(val header: List<String>, val rows: List<SourceRow>) {
    val headerLine: Int get() = 1
}

data class ImportCandidate(
    val line: Int,
    val date: LocalDate,
    val startAt: Long,
    val endAt: Long?,
    val durationSec: Int?,
    val subjectName: String,
    val categoryName: String,
    val total: Int,
    val correct: Int,
    val difficulty: Int,
    val energy: Int,
    val sequence: String?,
    val wrongTags: String? = null,
    val mode: RecordMode = RecordMode.COUNT,
    val kind: SessionKind = SessionKind.NORMAL,
    val scoreTenths: Int? = null,
    val maxScoreTenths: Int? = null,
    val scoreLabel: String? = null,
)

data class ImportRowError(val line: Int, val message: String)

data class ImportScan(
    val headers: List<String>,
    val mapping: ImportMapping,
    val missingRequired: List<ImportField>,
    val candidates: List<ImportCandidate>,
    val errors: List<ImportRowError>,
    val totalDataRows: Int,
)

/**
 * CSV/文本导入的解析与逐行校验。纯 Kotlin：不读文件、不碰数据库，便于在 JVM 上覆盖
 * 引号/换行/别名/跨零点/坏行等情形。真正的落库与自动备份在 ImportRepository。
 */
object CsvImport {
    const val MAX_BYTES = 20 * 1024 * 1024
    const val MAX_ROWS = 10_000
    const val PREVIEW_ROWS = 5

    private val DATE_PATTERN = DateTimeFormatter.ofPattern("uuuu-M-d", Locale.CHINA)
        .withResolverStyle(ResolverStyle.STRICT)
    private val CLOCK = Regex("^(\\d{1,2}):(\\d{2})$")
    private val PLAIN_SECONDS = Regex("^\\d{1,6}$")
    private val COLON_DURATION = Regex("^(\\d{1,2}):(\\d{1,2})(?::(\\d{1,2}))?$")
    private val RANGE_SEPARATORS = charArrayOf('-', '–', '—', '~', '～')

    /** 严格 UTF-8（允许 BOM）；乱码或非 UTF-8 直接拒绝，避免把坏字符写进记录。 */
    fun decode(bytes: ByteArray): String {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException("文件不是有效的 UTF-8 文本，请另存为 UTF-8 后重试。")
        }
        return text.removePrefix("\uFEFF")
    }

    fun parse(text: String): CsvTable {
        val rows = mutableListOf<SourceRow>()
        var cells = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var line = 1
        var recordLine = 1

        fun endRow() {
            cells.add(cell.toString())
            cell.setLength(0)
            if (cells.any { it.isNotBlank() }) rows.add(SourceRow(recordLine, cells.toList()))
            cells = mutableListOf()
            recordLine = line
        }

        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (inQuotes) {
                when (char) {
                    '"' -> if (index + 1 < text.length && text[index + 1] == '"') {
                        cell.append('"')
                        index++
                    } else {
                        inQuotes = false
                    }
                    '\n' -> {
                        cell.append('\n')
                        line++
                    }
                    else -> cell.append(char)
                }
            } else {
                when (char) {
                    '"' -> inQuotes = true
                    ',' -> {
                        cells.add(cell.toString())
                        cell.setLength(0)
                    }
                    '\r' -> {
                        if (index + 1 < text.length && text[index + 1] == '\n') index++
                        line++
                        endRow()
                    }
                    '\n' -> {
                        line++
                        endRow()
                    }
                    else -> cell.append(char)
                }
            }
            index++
        }
        if (inQuotes) throw IllegalArgumentException("文件中有未闭合的引号，请检查后重试。")
        if (cell.isNotEmpty() || cells.isNotEmpty()) endRow()

        val headerIndex = rows.indexOfFirst { it.cells.any { cell -> cell.isNotBlank() } }
        require(headerIndex >= 0) { "文件中没有可导入的记录。" }
        val header = rows[headerIndex].cells.map { it.trim() }
        val dataRows = rows.drop(headerIndex + 1)
        require(dataRows.size <= MAX_ROWS) { "单次最多导入 $MAX_ROWS 行，请拆分文件。" }
        return CsvTable(header = header, rows = dataRows)
    }

    fun detect(header: List<String>): ImportMapping {
        val columns = mutableMapOf<ImportField, Int>()
        header.forEachIndexed { index, raw ->
            val name = normalizeHeader(raw)
            val field = ImportField.entries.firstOrNull { field ->
                field !in columns && field.aliases.any { normalizeHeader(it) == name }
            } ?: return@forEachIndexed
            columns[field] = index
        }
        return ImportMapping(columns)
    }

    fun missingRequired(mapping: ImportMapping): List<ImportField> = buildList {
        listOf(ImportField.DATE, ImportField.SUBJECT, ImportField.CATEGORY, ImportField.TOTAL)
            .filterTo(this) { it !in mapping.columns }
        if (ImportField.CORRECT !in mapping.columns && ImportField.WRONG !in mapping.columns) {
            add(ImportField.CORRECT)
        }
    }

    fun scan(table: CsvTable, mapping: ImportMapping, today: LocalDate, zoneId: ZoneId = ZoneId.systemDefault()): ImportScan {
        val missing = missingRequired(mapping)
        if (missing.isNotEmpty()) {
            return ImportScan(table.header, mapping, missing, emptyList(), emptyList(), table.rows.size)
        }
        val candidates = mutableListOf<ImportCandidate>()
        val errors = mutableListOf<ImportRowError>()
        table.rows.forEach { row ->
            when (val result = parseRow(row, mapping, today, zoneId)) {
                is RowResult.Ok -> candidates.add(result.candidate)
                is RowResult.Bad -> errors.add(ImportRowError(row.line, result.message))
            }
        }
        return ImportScan(table.header, mapping, emptyList(), candidates, errors, table.rows.size)
    }

    private fun parseRow(row: SourceRow, mapping: ImportMapping, today: LocalDate, zoneId: ZoneId): RowResult {
        fun cell(field: ImportField): String? = mapping.columns[field]?.let { row.cells.getOrNull(it) }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::unescapeFormulaGuard)

        val date = parseDate(cell(ImportField.DATE))
            ?: return RowResult.Bad("日期为空或格式不对，应写成 2026-10-29。")
        if (date.isAfter(today)) return RowResult.Bad("练习日期晚于今天：$date。")

        val subjectName = cell(ImportField.SUBJECT)?.let(NameRules::normalize)
            ?: return RowResult.Bad("科目为空。")
        NameRules.error(subjectName)?.let { return RowResult.Bad("科目$it") }
        val categoryName = cell(ImportField.CATEGORY)?.let(NameRules::normalize)
            ?: return RowResult.Bad("类别为空。")
        NameRules.error(categoryName)?.let { return RowResult.Bad("类别$it") }

        // ---- v5/v0.11：模式与性质；缺列时按旧 14 列文件的「客观题 / 普通」导入 ----
        val mode = parseMode(cell(ImportField.MODE))
            ?: return RowResult.Bad("记录模式只能是 客观题/计分/专注。")
        val kind = parseKind(cell(ImportField.KIND)) ?: return RowResult.Bad("记录性质只能是 普通/复做。")
        if (mode == RecordMode.FOCUS && kind != SessionKind.NORMAL) {
            return RowResult.Bad("专注行不能填「复做」。")
        }

        val total = if (mode == RecordMode.FOCUS) {
            val raw = cell(ImportField.TOTAL)
            if (raw != null && raw != "0") return RowResult.Bad("专注行不能填题量。")
            0
        } else {
            val parsed = cell(ImportField.TOTAL)?.toIntOrNull()
                ?: return RowResult.Bad("题量必须是大于 0 的整数。")
            if (parsed <= 0) return RowResult.Bad("题量必须是大于 0 的整数。")
            parsed
        }
        val scoreLabel = cell(ImportField.SCORE_LABEL)
        if (scoreLabel != null && scoreLabel.length > ScoreRules.MAX_LABEL_LENGTH) {
            return RowResult.Bad("计分项目不能超过 ${ScoreRules.MAX_LABEL_LENGTH} 个字。")
        }
        var scoreTenths: Int? = null
        var maxScoreTenths: Int? = null
        if (mode == RecordMode.SCORE) {
            scoreTenths = ScoreRules.parseTenths(cell(ImportField.SCORE))
                ?: return RowResult.Bad("计分行缺少合法得分（最多 1 位小数）。")
            maxScoreTenths = ScoreRules.parseTenths(cell(ImportField.MAX_SCORE))
                ?: return RowResult.Bad("计分行缺少合法满分（最多 1 位小数）。")
            ScoreRules.error(scoreTenths, maxScoreTenths, scoreLabel)
                ?.let { return RowResult.Bad(it) }
            // 第 20 列「得分率」是展示值，导入时重新计算并忽略，不做校验。
        } else if (cell(ImportField.SCORE) != null || cell(ImportField.MAX_SCORE) != null) {
            return RowResult.Bad("非计分行不能填得分或满分，请检查记录模式列。")
        }

        val correctRaw = cell(ImportField.CORRECT)
        val wrongRaw = cell(ImportField.WRONG)
        val correct = when (mode) {
            RecordMode.SCORE -> {
                if (correctRaw != null && correctRaw != "0") {
                    return RowResult.Bad("计分行的正确数应留空（不适用）。")
                }
                0
            }
            RecordMode.COUNT -> when {
                kind == SessionKind.REDO && correctRaw == null && wrongRaw == null ->
                    return RowResult.Bad("复做行必须填写本轮通过数。")
                correctRaw != null -> correctRaw.toIntOrNull()
                    ?: return RowResult.Bad("正确数不是整数。")
                wrongRaw != null -> {
                    val wrong = wrongRaw.toIntOrNull() ?: return RowResult.Bad("错题数不是整数。")
                    if (wrong !in 0..total) return RowResult.Bad("错题数需要在 0 与题量之间。")
                    total - wrong
                }
                else -> return RowResult.Bad("缺少正确数或错题数。")
            }
            RecordMode.FOCUS -> {
                if (correctRaw != null && correctRaw != "0") {
                    return RowResult.Bad("专注行的正确数应留空（不适用）。")
                }
                0
            }
        }
        if (correct !in 0..total) return RowResult.Bad("正确数需要在 0 与题量之间。")
        if (correctRaw != null && wrongRaw != null && mode == RecordMode.COUNT) {
            val wrong = wrongRaw.toIntOrNull() ?: return RowResult.Bad("错题数不是整数。")
            if (total - correct != wrong) return RowResult.Bad("正确数与错题数对不上。")
        }

        val clock = when (val parsed = parseTimeRange(cell(ImportField.TIME))) {
            null -> null
            is TimeResult.Ok -> parsed
            is TimeResult.Bad -> return RowResult.Bad(parsed.message)
        }
        val endAt = clock?.takeIf { it.hasEnd }?.let {
            date.plusDays(it.endDayOffset.toLong()).atTime(it.end).atZone(zoneId).toInstant().toEpochMilli()
        }
        val startAt = clock?.let {
            date.atTime(it.start).atZone(zoneId).toInstant().toEpochMilli()
        } ?: date.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val spanSeconds = clock?.takeIf { it.hasEnd }?.let {
            Duration.between(it.start, it.end).seconds + it.endDayOffset * 86_400L
        }

        val duration = when {
            cell(ImportField.DURATION_SECONDS) != null -> {
                val raw = cell(ImportField.DURATION_SECONDS)!!
                val seconds = raw.toIntOrNull() ?: return RowResult.Bad("净时长秒必须是整数。")
                if (seconds <= 0) return RowResult.Bad("净时长必须大于 0 秒。")
                seconds
            }
            cell(ImportField.DURATION_TEXT) != null -> {
                val seconds = parseDurationText(cell(ImportField.DURATION_TEXT)!!)
                    ?: return RowResult.Bad("净时长应为 分:秒 或 时:分:秒。")
                if (seconds <= 0) return RowResult.Bad("净时长必须大于 0 秒。")
                seconds
            }
            spanSeconds != null -> spanSeconds.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
            else -> null
        }
        if (mode == RecordMode.FOCUS && duration == null) {
            return RowResult.Bad("专注行必须填写净时长。")
        }
        if (spanSeconds != null && duration != null && duration > spanSeconds) {
            return RowResult.Bad("净时长超过了起止时段。")
        }

        val difficulty = parseRating(cell(ImportField.DIFFICULTY), DIFFICULTY)
            ?: return RowResult.Bad("难度只能写 易/中/难。")
        val energy = parseRating(cell(ImportField.ENERGY), ENERGY)
            ?: return RowResult.Bad("精力只能写 低/中/高。")

        if (mode == RecordMode.SCORE && cell(ImportField.SEQUENCE) != null) {
            return RowResult.Bad("计分行不能填对错序列。")
        }
        if (mode == RecordMode.FOCUS && cell(ImportField.SEQUENCE) != null) {
            return RowResult.Bad("专注行不能填对错序列。")
        }
        val sequence = cell(ImportField.SEQUENCE)?.let { raw ->
            if (raw.length != total || raw.any { it != '0' && it != '1' }) {
                return RowResult.Bad("对错序列应为 $total 位 0/1。")
            }
            raw
        }

        val tagsRaw = cell(ImportField.TAGS)
        if (mode == RecordMode.FOCUS && tagsRaw != null) {
            return RowResult.Bad("专注行不能填错题关键词。")
        }
        TagRules.error(tagsRaw)?.let { return RowResult.Bad(it) }
        val tags = TagRules.normalize(tagsRaw)

        return RowResult.Ok(
            ImportCandidate(
                line = row.line,
                date = date,
                startAt = startAt,
                endAt = endAt,
                durationSec = duration,
                subjectName = subjectName,
                categoryName = categoryName,
                total = total,
                correct = correct,
                difficulty = difficulty,
                energy = energy,
                sequence = sequence,
                wrongTags = tags,
                mode = mode,
                kind = kind,
                scoreTenths = scoreTenths,
                maxScoreTenths = maxScoreTenths,
                scoreLabel = scoreLabel,
            ),
        )
    }

    /** 疑似重复的判定键：类别 + 开始时刻 + 题量。 */
    fun fingerprint(categoryId: Long, startAt: Long, total: Int): String = "$categoryId|$startAt|$total"

    private sealed interface TimeResult {
        class Ok(
            val start: LocalTime,
            val end: LocalTime,
            val endDayOffset: Int,
            val hasEnd: Boolean,
        ) : TimeResult
        class Bad(val message: String) : TimeResult
    }

    private fun parseTimeRange(raw: String?): TimeResult? {
        if (raw == null) return null
        val parts = raw.split(*RANGE_SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > 2) return TimeResult.Bad("时间应写成 09:00-09:25。")
        val startClock = parseClock(parts[0]) ?: return TimeResult.Bad("开始时间格式应为 09:00。")
        val startTime = LocalTime.of(startClock.first % 24, startClock.second)
        if (parts.size == 1) return TimeResult.Ok(startTime, startTime, 0, hasEnd = false)
        val endClock = parseClock(parts[1]) ?: return TimeResult.Bad("结束时间格式应为 09:25，跨零点可写 24:05。")
        var dayOffset = endClock.first / 24
        val endTime = LocalTime.of(endClock.first % 24, endClock.second)
        if (dayOffset == 0 && !endTime.isAfter(startTime)) dayOffset = 1
        return TimeResult.Ok(startTime, endTime, dayOffset, hasEnd = true)
    }

    private fun parseClock(raw: String): Pair<Int, Int>? {
        val match = CLOCK.matchEntire(raw) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..47 || minute !in 0..59) return null
        return hour to minute
    }

    private fun parseDurationText(raw: String): Int? {
        if (PLAIN_SECONDS.matches(raw)) return raw.toIntOrNull()
        val match = COLON_DURATION.matchEntire(raw) ?: return null
        val first = match.groupValues[1].toIntOrNull() ?: return null
        val second = match.groupValues[2].toIntOrNull() ?: return null
        return if (match.groupValues[3].isEmpty()) {
            if (second !in 0..59) null else first * 60 + second
        } else {
            val third = match.groupValues[3].toIntOrNull() ?: return null
            if (second !in 0..59 || third !in 0..59) null else first * 3600 + second * 60 + third
        }
    }

    private fun parseDate(raw: String?): LocalDate? {
        if (raw == null) return null
        val normalized = raw.trim().replace('/', '-').replace('.', '-')
        return runCatching { LocalDate.parse(normalized, DATE_PATTERN) }.getOrNull()
    }

    /** 中文展示值与英文枚举值都可导入；缺列按客观题。 */
    private fun parseMode(raw: String?): RecordMode? = when (raw?.trim()?.uppercase(Locale.ROOT)) {
        null, "", "客观题", "COUNT" -> RecordMode.COUNT
        "计分", "得分", "SCORE" -> RecordMode.SCORE
        "专注", "FOCUS" -> RecordMode.FOCUS
        else -> null
    }

    private fun parseKind(raw: String?): SessionKind? = when (raw?.trim()?.uppercase(Locale.ROOT)) {
        null, "", "普通", "NORMAL" -> SessionKind.NORMAL
        "复做", "REDO" -> SessionKind.REDO
        else -> null
    }

    private fun parseRating(raw: String?, choices: Map<String, Int>): Int? {
        if (raw == null) return 0
        return choices[raw] ?: return null
    }

    private fun normalizeHeader(raw: String): String =
        raw.trim().lowercase(Locale.ROOT).replace(" ", "").replace("_", "")

    /** 导出时给 = + - @ 开头的文本加了单引号，导回来要去掉，避免科目/类别名变形。 */
    private fun unescapeFormulaGuard(value: String): String =
        if (value.length > 1 && value[0] == '\'' && value[1] in "=+-@") value.substring(1) else value

    private sealed interface RowResult {
        data class Ok(val candidate: ImportCandidate) : RowResult
        data class Bad(val message: String) : RowResult
    }

    private val DIFFICULTY = mapOf("易" to 1, "中" to 2, "难" to 3)
    private val ENERGY = mapOf("低" to 1, "中" to 2, "高" to 3)
}
