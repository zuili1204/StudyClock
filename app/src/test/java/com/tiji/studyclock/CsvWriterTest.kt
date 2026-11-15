package com.tiji.studyclock

import com.tiji.studyclock.data.export.CsvWriter
import com.tiji.studyclock.data.export.ReportBuilder
import com.tiji.studyclock.domain.model.Category
import com.tiji.studyclock.domain.model.RecordMode
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.SessionKind
import com.tiji.studyclock.domain.model.SessionSource
import com.tiji.studyclock.domain.model.Subject
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvWriterTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val subjects = listOf(Subject(2, "言语理解与判断", "#00FF00", 1))
    private val categories = listOf(Category(28, 2, "语句填充", null, 1))

    private fun at(hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, 10, 28, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private val timed = Session(
        id = 1, categoryId = 28, subjectId = 2,
        startAt = at(17, 32), endAt = at(17, 46), durationSec = 830,
        totalCount = 16, correctCount = 12, source = SessionSource.TIMER,
    )

    private val legacyHeader =
        "日期,科目,类别,题目,正确,正确率,时间,净时长,净时长秒,单题秒数,对错序列,难度,精力,错题关键词"

    /** 引号感知的单元格切分：带逗号的类别名不能被拆成两列。 */
    private fun cells(line: String): List<String> {
        val result = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            if (quoted) {
                when (char) {
                    '"' -> if (index + 1 < line.length && line[index + 1] == '"') {
                        cell.append('"')
                        index++
                    } else {
                        quoted = false
                    }
                    else -> cell.append(char)
                }
            } else {
                when (char) {
                    '"' -> quoted = true
                    ',' -> {
                        result.add(cell.toString())
                        cell.setLength(0)
                    }
                    else -> cell.append(char)
                }
            }
            index++
        }
        result.add(cell.toString())
        return result
    }

    private fun header(csv: String) = csv.removePrefix(CsvWriter.BOM).substringBefore("\r\n")

    private fun dataCells(csv: String) = cells(csv.removePrefix(CsvWriter.BOM).trim().lines()[1])

    /** v5 固定 20 列；前 14 列仍是 v4 的物理契约，逐字不变。 */
    private fun legacyRow(csv: String) = dataCells(csv).take(14).joinToString(",")

    @Test
    fun headerIsTheFourteenContractColumnsInOrderPlusSixV5Columns() {
        val csv = CsvWriter.write(ReportBuilder.build(listOf(timed), subjects, categories, zone))
        assertEquals(
            "$legacyHeader,记录模式,记录性质,计分项目,得分,满分,得分率",
            header(csv),
        )
        assertTrue(header(csv).startsWith(legacyHeader))
        assertEquals(20, CsvWriter.columnCount)
        assertEquals(20, cells(header(csv)).size)
        assertTrue(csv.startsWith(CsvWriter.BOM))
    }

    @Test
    fun v5ColumnsAreAppendedWithoutChangingLegacyCells() {
        val csv = CsvWriter.write(ReportBuilder.build(listOf(timed), subjects, categories, zone))
        val row = dataCells(csv)
        assertEquals(20, row.size)
        assertEquals(listOf("客观题", "普通", "", "", "", ""), row.drop(14))
    }

    @Test
    fun timedRowCarriesRealClockAndNetDuration() {
        val csv = CsvWriter.write(ReportBuilder.build(listOf(timed), subjects, categories, zone))
        assertEquals(
            "2026-10-28,言语理解与判断,语句填充,16,12,75.0%,17:32-17:46,13:50,830,51.9,,,,",
            legacyRow(csv),
        )
    }

    @Test
    fun tagsAreWrittenInTheFourteenthColumn() {
        val tagged = timed.copy(wrongTags = "属性规律|增长率")
        val csv = CsvWriter.write(ReportBuilder.build(listOf(tagged), subjects, categories, zone))
        val row = dataCells(csv)
        assertEquals("属性规律|增长率", row[13])
        assertTrue(row.drop(14).all { it.isEmpty() || it == "客观题" || it == "普通" })
    }

    @Test
    fun manualRowDoesNotFakeATime() {
        val manual = Session(
            id = 2, categoryId = 28, subjectId = 2,
            startAt = at(0, 0), endAt = null, durationSec = null,
            totalCount = 5, correctCount = 4, source = SessionSource.MANUAL,
        )
        val csv = CsvWriter.write(ReportBuilder.build(listOf(manual), subjects, categories, zone))
        // 时间、净时长、秒数、单题秒、序列、难度、精力、关键词 共 8 列为空。
        assertEquals("2026-10-28,言语理解与判断,语句填充,5,4,80.0%,,,,,,,,", legacyRow(csv))
    }

    @Test
    fun manualRowWithExplicitClockKeepsTheRange() {
        // 补记填了起止时刻，就应该和计时记录一样写出真实时段。
        val manual = Session(
            id = 3, categoryId = 28, subjectId = 2,
            startAt = at(21, 16), endAt = at(21, 26), durationSec = 600,
            totalCount = 16, correctCount = 10, source = SessionSource.MANUAL,
        )
        val csv = CsvWriter.write(ReportBuilder.build(listOf(manual), subjects, categories, zone))
        assertEquals("2026-10-28,言语理解与判断,语句填充,16,10,62.5%,21:16-21:26,10:00,600,37.5,,,,", legacyRow(csv))
    }

    @Test
    fun commasQuotesAndNewlinesAreEscaped() {
        val weird = categories.map { it.copy(name = "语句, 填充 \"专项\"") }
        val row = CsvWriter.write(ReportBuilder.build(listOf(timed), subjects, weird, zone)).trim().lines()[1]
        assertTrue(row.contains("\"语句, 填充 \"\"专项\"\"\""))
        assertEquals("语句, 填充 \"专项\"", dataCells(CsvWriter.write(ReportBuilder.build(listOf(timed), subjects, weird, zone)))[2])
    }

    @Test
    fun formulaInjectionIsNeutralized() {
        val row = CsvWriter.write(
            ReportBuilder.build(listOf(timed), subjects.map { it.copy(name = "=cmd|' /C calc'!A0") }, categories, zone),
        ).trim().lines()[1]
        assertFalse(row.startsWith("2026-10-28,="))
        assertTrue(row.contains("'=cmd|"))
    }

    @Test
    fun crossMidnightEndIsWrittenAsTwentyFourHourNotation() {
        val midnight = timed.copy(
            startAt = LocalDateTime.of(2026, 10, 28, 23, 38).atZone(zone).toInstant().toEpochMilli(),
            endAt = LocalDateTime.of(2026, 10, 29, 0, 3).atZone(zone).toInstant().toEpochMilli(),
            durationSec = 1500,
        )
        val row = CsvWriter.write(ReportBuilder.build(listOf(midnight), subjects, categories, zone)).trim().lines()[1]
        assertTrue(row, row.contains("23:38-24:03"))
    }

    @Test
    fun scoreRowLeavesAccuracyAndCorrectBlankButWritesScoreColumns() {
        val scored = Session(
            id = 4, categoryId = 28, subjectId = 2,
            startAt = at(9, 0), endAt = null, durationSec = null,
            totalCount = 1, correctCount = 0, source = SessionSource.MANUAL,
            mode = RecordMode.SCORE, scoreTenths = 75, maxScoreTenths = 90,
            scoreLabel = "大作文",
        )
        val row = dataCells(CsvWriter.write(ReportBuilder.build(listOf(scored), subjects, categories, zone)))
        assertEquals("", row[4]) // correctCount=0 是「不适用」，不能写成 0
        assertEquals("", row[5]) // 得分率走第 20 列，正确率列留空
        assertEquals(listOf("计分", "普通", "大作文", "7.5", "9.0", "83.3%"), row.drop(14))
    }

    @Test
    fun redoRowWritesKindButKeepsAccuracyBlank() {
        val redo = timed.copy(kind = SessionKind.REDO, batchId = 9)
        val row = dataCells(CsvWriter.write(ReportBuilder.build(listOf(redo), subjects, categories, zone)))
        assertEquals("12", row[4])
        assertEquals("", row[5]) // 复做通过率不进主正确率
        assertEquals("客观题", row[14])
        assertEquals("复做", row[15])
        assertEquals(listOf("", "", "", ""), row.drop(16))
    }

    /** v0.11：专注行只写日期、科目、类别、时段、净时长与「专注」，其余一律留空。 */
    @Test
    fun focusRowOnlyCarriesDateSubjectTimeAndDuration() {
        val focus = Session(
            id = 5, categoryId = 28, subjectId = 2,
            startAt = at(19, 0), endAt = at(19, 42), durationSec = 2520,
            totalCount = 0, correctCount = 0, mode = RecordMode.FOCUS, source = SessionSource.TIMER,
        )
        val row = dataCells(CsvWriter.write(ReportBuilder.build(listOf(focus), subjects, categories, zone)))
        assertEquals(20, row.size)
        assertEquals(
            listOf("2026-10-28", "言语理解与判断", "语句填充"),
            row.take(3),
        )
        assertEquals("", row[3]) // 题量不适用
        assertEquals("", row[4]) // 正确数不适用
        assertEquals("", row[5]) // 正确率不适用
        assertEquals("19:00-19:42", row[6])
        assertEquals("42:00", row[7])
        assertEquals("2520", row[8])
        assertEquals("", row[9]) // 单题秒数不适用
        assertEquals(listOf("", "", "", ""), row.subList(10, 14))
        assertEquals(listOf("专注", "普通", "", "", "", ""), row.drop(14))
    }
}
