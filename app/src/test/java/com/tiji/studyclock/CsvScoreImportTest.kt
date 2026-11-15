package com.tiji.studyclock

import com.tiji.studyclock.data.export.CsvWriter
import com.tiji.studyclock.data.export.ReportBuilder
import com.tiji.studyclock.data.importer.CsvImport
import com.tiji.studyclock.data.importer.ImportField
import com.tiji.studyclock.domain.model.Category
import com.tiji.studyclock.domain.model.RecordMode
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.SessionKind
import com.tiji.studyclock.domain.model.Subject
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CSV 20 列：1–14 列不变、旧文件可导入、SCORE/REDO 分支、坏行与公式注入。 */
class CsvScoreImportTest {
    private val today = LocalDate.of(2026, 11, 15)
    private val zone = ZoneId.systemDefault()
    private val header14 =
        "日期,科目,类别,题目,正确,正确率,时间,净时长,净时长秒,单题秒数,对错序列,难度,精力,错题关键词"
    private val header20 = header14 + ",记录模式,记录性质,计分项目,得分,满分,得分率"

    private fun scan(text: String) = CsvImport.parse(text).let { table ->
        CsvImport.scan(table, CsvImport.detect(table.header), today, zone)
    }

    @Test
    fun exportHeaderHasTwentyColumnsWithFirstFourteenUnchanged() {
        assertEquals(20, CsvWriter.columnCount)
        val written = CsvWriter.write(ReportBuilder.build(emptyList(), emptyList(), emptyList(), zone))
        val headerLine = written.removePrefix(CsvWriter.BOM).lines().first()
        assertEquals(header20, headerLine)
        assertTrue(headerLine.startsWith(header14))
    }

    @Test
    fun legacyFourteenColumnFileStillImports() {
        val text = header14 + "\n" +
            "2026-11-10,行测,主旨概括,10,7,70.0%,,10:00,600,60.0,,,,增长率|审题"
        val result = scan(text)
        assertTrue(result.errors.isEmpty())
        assertEquals(1, result.candidates.size)
        val row = result.candidates.single()
        assertEquals(RecordMode.COUNT, row.mode)
        assertEquals(SessionKind.NORMAL, row.kind)
        assertEquals(10, row.total)
        assertEquals(7, row.correct)
        assertEquals("增长率|审题", row.wrongTags)
        assertNull(row.scoreTenths)
    }

    @Test
    fun scoreRowsRequireScoreAndMaxButNotCorrectCount() {
        val text = header20 + "\n" +
            "2026-11-10,申论,文章写作,1,,,,10:00,600,600.0,,,,论据不足,计分,普通,大作文,7.5,9.0,83.3%"
        val result = scan(text)
        assertTrue(result.errors.isEmpty())
        val row = result.candidates.single()
        assertEquals(RecordMode.SCORE, row.mode)
        assertEquals(75, row.scoreTenths)
        assertEquals(90, row.maxScoreTenths)
        assertEquals("大作文", row.scoreLabel)
        assertEquals(0, row.correct)
        assertNull(row.sequence)
    }

    @Test
    fun scoreRowWithoutScoreIsBadWithLineNumber() {
        val text = header20 + "\n" + "2026-11-10,申论,文章写作,1,,,,,,,,,,,论据不足,计分,普通,大作文,,9.0,"
        val result = scan(text)
        assertEquals(1, result.errors.size)
        assertEquals(2, result.errors.single().line)
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun redoRowNeedsPassedCountAndDoesNotComputeAccuracy() {
        // 第 7 列「时间」留空，第 8/9/10 列才是 净时长 / 净时长秒 / 单题秒数。
        val text = header20 + "\n" +
            "2026-11-10,行测,主旨概括,5,3,,,10:00,600,120.0,,,,审题,客观题,复做,,,"
        val result = scan(text)
        assertTrue("复做行不应有坏行：${result.errors}", result.errors.isEmpty())
        assertEquals(SessionKind.REDO, result.candidates.single().kind)
        assertEquals(3, result.candidates.single().correct)

        val missing = header20 + "\n" +
            "2026-11-10,行测,主旨概括,5,,,,10:00,600,120.0,,,,审题,客观题,复做,,,"
        val bad = scan(missing)
        assertEquals(1, bad.errors.size)
        assertTrue(bad.errors.single().message.contains("通过数"))
    }

    @Test
    fun illegalModeOrMixedFieldsAreRejected() {
        val badMode = header20 + "\n" + "2026-11-10,申论,文章写作,1,,,,,,,,,,,计分,奇怪,,,"
        assertTrue(scan(badMode).errors.isNotEmpty())
        val mixed = header20 + "\n" + "2026-11-10,行测,主旨概括,10,7,,,,,,,,,,客观题,普通,,7.5,9.0,"
        assertTrue(scan(mixed).errors.isNotEmpty())
    }

    @Test
    fun roundTripKeepsModeScoreKindAndPipeSeparatedTags() {
        val subject = Subject(1, "=行测", "#4C6FFF", 0)
        val category = Category(1, 1, "文章写作")
        val at = today.minusDays(2).atStartOfDay(zone).toInstant().toEpochMilli() + 3_600_000L
        val rows = listOf(
            Session(1, 1, 1, at, totalCount = 1, correctCount = 0, durationSec = 600,
                mode = RecordMode.SCORE, scoreTenths = 75, maxScoreTenths = 90,
                scoreLabel = "大作文", wrongTags = "论据不足|结构"),
            Session(2, 1, 1, at + 1000, totalCount = 5, correctCount = 3, durationSec = 300,
                kind = SessionKind.REDO, batchId = 9, wrongTags = "审题"),
        )
        val csv = CsvWriter.write(ReportBuilder.build(rows, listOf(subject), listOf(category), zone))
        val result = scan(csv.removePrefix(CsvWriter.BOM))
        assertTrue("往返不应有坏行：${result.errors}", result.errors.isEmpty())
        assertEquals(2, result.candidates.size)
        val scored = result.candidates.first { it.mode == RecordMode.SCORE }
        assertEquals(75, scored.scoreTenths)
        assertEquals(90, scored.maxScoreTenths)
        assertEquals("=行测", scored.subjectName) // 导出加的前缀单引号已还原
        assertEquals("论据不足|结构", scored.wrongTags)
        val redo = result.candidates.first { it.kind == SessionKind.REDO }
        assertEquals(3, redo.correct)
        // 公式注入防护：文本列仍被转义，写出的科目不以裸 = 开头。
        assertTrue(csv.contains("'=行测"))
    }

    @Test
    fun missingRequiredColumnsAreReportedByField() {
        val table = CsvImport.parse("日期,科目,题目\n2026-11-10,行测,10")
        val missing = CsvImport.missingRequired(CsvImport.detect(table.header))
        assertTrue(missing.contains(ImportField.CATEGORY))
        assertTrue(missing.contains(ImportField.CORRECT))
    }

    /** v0.11：专注行只填日期、科目、类别、时段、净时长与模式。 */
    private fun focusRow(
        total: String = "",
        correct: String = "",
        seconds: String = "",
        kind: String = "普通",
        sequence: String = "",
        time: String = "19:00-19:42",
    ): String = listOf(
        "2026-11-10", "行测", "主旨概括", total, correct, "", time, if (time.isBlank()) "" else "42:00", seconds,
        "", sequence, "", "", "", "专注", kind, "", "", "", "",
    ).joinToString(",")

    @Test
    fun focusRowImportsWithZeroQuestionsAndRealDuration() {
        val result = scan(header20 + "\n" + focusRow(seconds = "2520"))
        assertTrue("专注行不应有坏行：${result.errors}", result.errors.isEmpty())
        val row = result.candidates.single()
        assertEquals(RecordMode.FOCUS, row.mode)
        assertEquals(SessionKind.NORMAL, row.kind)
        assertEquals(0, row.total)
        assertEquals(0, row.correct)
        assertEquals(2520, row.durationSec)
        assertNull(row.sequence)
        assertNull(row.scoreTenths)
        assertNull(row.wrongTags)
    }

    @Test
    fun focusRowRejectsPracticeFieldsAndMissingDuration() {
        assertTrue(scan(header20 + "\n" + focusRow(total = "5", seconds = "2520"))
            .errors.single().message.contains("不能填题量"))
        assertTrue(scan(header20 + "\n" + focusRow(correct = "3", seconds = "2520"))
            .errors.single().message.contains("正确数应留空"))
        // 时段与净时长都留空，才真正缺时长。
        assertTrue(scan(header20 + "\n" + focusRow(seconds = "", time = ""))
            .errors.single().message.contains("必须填写净时长"))
        assertTrue(scan(header20 + "\n" + focusRow(seconds = "2520", kind = "复做"))
            .errors.single().message.contains("不能填「复做」"))
        assertTrue(scan(header20 + "\n" + focusRow(seconds = "2520", sequence = "1"))
            .errors.single().message.contains("不能填对错序列"))
    }

    @Test
    fun exportedFocusRowRoundTripsBackToFocus() {
        val at = today.minusDays(2).atStartOfDay(zone).toInstant().toEpochMilli() + 3_600_000L
        val rows = listOf(
            Session(7, 1, 1, at, endAt = at + 2_520_000L, totalCount = 0, correctCount = 0,
                durationSec = 2520, mode = RecordMode.FOCUS),
        )
        val csv = CsvWriter.write(
            ReportBuilder.build(rows, listOf(Subject(1, "行测", "#4C6FFF", 0)), listOf(Category(1, 1, "主旨概括")), zone),
        )
        val result = scan(csv.removePrefix(CsvWriter.BOM))
        assertTrue("专注往返不应有坏行：${result.errors}", result.errors.isEmpty())
        val row = result.candidates.single()
        assertEquals(RecordMode.FOCUS, row.mode)
        assertEquals(2520, row.durationSec)
        assertEquals(0, row.total)
    }
}
