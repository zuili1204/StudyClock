package com.tiji.studyclock

import com.tiji.studyclock.data.export.CsvWriter
import com.tiji.studyclock.data.export.ReportBuilder
import com.tiji.studyclock.data.importer.CsvImport
import com.tiji.studyclock.data.importer.ImportField
import com.tiji.studyclock.data.importer.ImportMapping
import com.tiji.studyclock.domain.model.Category
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.SessionSource
import com.tiji.studyclock.domain.model.Subject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CsvImportTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 10, 29)
    private val subjects = listOf(Subject(2, "言语理解与判断", "#00FF00", 1))
    private val categories = listOf(Category(28, 2, "语句填充", null, 1))

    private fun at(day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun scan(text: String) = CsvImport.parse(text).let { table ->
        CsvImport.scan(table, CsvImport.detect(table.header), today, zone)
    }

    @Test
    fun exportedCsvRoundTripsFourteenColumns() {
        val timed = Session(
            id = 1, categoryId = 28, subjectId = 2,
            startAt = at(28, 17, 32), endAt = at(28, 17, 46), durationSec = 830,
            totalCount = 16, correctCount = 12, answerSequence = "1010101010101010",
            difficulty = 2, energy = 3, wrongTags = "属性规律|增长率", source = SessionSource.TIMER,
        )
        val manual = Session(
            id = 2, categoryId = 28, subjectId = 2,
            startAt = at(29, 0, 0), endAt = null, durationSec = null,
            totalCount = 5, correctCount = 4, source = SessionSource.MANUAL,
        )
        val csv = CsvWriter.write(ReportBuilder.build(listOf(timed, manual), subjects, categories, zone))
        val scan = scan(CsvImport.decode(csv.toByteArray()))

        assertTrue(scan.missingRequired.isEmpty())
        assertTrue(scan.errors.toString(), scan.errors.isEmpty())
        assertEquals(2, scan.candidates.size)
        val first = scan.candidates[0]
        assertEquals(LocalDate.of(2026, 10, 28), first.date)
        assertEquals(timed.startAt, first.startAt)
        assertEquals(timed.endAt, first.endAt)
        assertEquals(830, first.durationSec)
        assertEquals(16, first.total)
        assertEquals(12, first.correct)
        assertEquals("1010101010101010", first.sequence)
        assertEquals(2, first.difficulty)
        assertEquals(3, first.energy)
        assertEquals("属性规律|增长率", first.wrongTags)
        assertEquals("言语理解与判断", first.subjectName)
        assertEquals("语句填充", first.categoryName)

        val second = scan.candidates[1]
        assertEquals(LocalDate.of(2026, 10, 29), second.date)
        assertEquals(manual.startAt, second.startAt)
        assertNull(second.endAt)
        assertNull(second.durationSec)
        assertEquals(5, second.total)
        assertEquals(4, second.correct)
        assertNull(second.wrongTags)
    }

    @Test
    fun keywordColumnAcceptsAliasesAndNormalizesSeparators() {
        val text = "日期,科目,类别,题目,正确,标签\n2026-10-01,判断,语句,10,8,【属性规律】、增长率\n"
        val candidate = scan(text).candidates.single()
        assertEquals("属性规律|增长率", candidate.wrongTags)
    }

    @Test
    fun tooManyOrTooLongKeywordsRejectTheRow() {
        val many = "日期,科目,类别,题目,正确,错题关键词\n2026-10-01,判断,语句,10,8,一|二|三|四|五|六\n"
        val tooMany = scan(many)
        assertEquals(1, tooMany.errors.size)
        assertTrue(tooMany.errors.single().message.contains("最多"))

        val long = "日期,科目,类别,题目,正确,关键词\n2026-10-01,判断,语句,10,8,这是一个明显超过十二个字的关键词\n"
        val tooLong = scan(long)
        assertEquals(1, tooLong.errors.size)
        assertTrue(tooLong.errors.single().message.contains("12"))
        assertTrue(tooLong.candidates.isEmpty())
    }

    @Test
    fun quotedCellsAndDoubledQuotesFollowRfc4180() {
        val text = "日期,科目,类别,题目,正确\n2026-10-01,\"言,语\",\"判断\"\"引号\"\"\",10,8\n"
        val scan = scan(text)
        assertTrue(scan.errors.toString(), scan.errors.isEmpty())
        assertEquals(1, scan.candidates.size)
        assertEquals("言,语", scan.candidates[0].subjectName)
        assertEquals("判断\"引号\"", scan.candidates[0].categoryName)
    }

    @Test
    fun embeddedNewlineRejectsNameAndKeepsFollowingLineNumbers() {
        val text = "日期,科目,类别,题目,正确\n2026-10-01,\"言\n语\",判断,10,8\n2026-10-02,判断,语句,10,8\n"
        val scan = scan(text)
        assertEquals(1, scan.errors.size)
        assertEquals(2, scan.errors[0].line)
        assertTrue(scan.errors[0].message.startsWith("科目"))
        assertEquals(1, scan.candidates.size)
        assertEquals(4, scan.candidates[0].line)
    }

    @Test
    fun bomIsStrippedBeforeHeaderDetection() {
        val csv = "\uFEFF日期,科目,类别,题目,正确\n2026-10-01,判断,语句,10,8\n"
        val table = CsvImport.parse(CsvImport.decode(csv.toByteArray()))
        assertEquals("日期", table.header[0])
        val scan = CsvImport.scan(table, CsvImport.detect(table.header), today, zone)
        assertEquals(1, scan.candidates.size)
    }

    @Test
    fun englishAliasesAndUnderscoreHeadersAreDetected() {
        val text = "date,subject,category,questions,correct,time,duration_seconds,energy\n" +
            "2026-10-01,判断,语句,10,8,09:00-09:30,1500,高\n"
        val scan = scan(text)
        assertTrue(scan.errors.toString(), scan.errors.isEmpty())
        val candidate = scan.candidates.single()
        assertEquals(1500, candidate.durationSec)
        assertEquals(3, candidate.energy)
        assertEquals(at(1, 9, 0), candidate.startAt)
        assertEquals(at(1, 9, 30), candidate.endAt)
    }

    @Test
    fun manualMappingCanOverrideAutoDetection() {
        val table = CsvImport.parse("日期,科目,类别,甲,乙\n2026-10-01,判断,语句,10,8\n")
        val automatic = CsvImport.detect(table.header)
        assertEquals(listOf(ImportField.TOTAL, ImportField.CORRECT), CsvImport.missingRequired(automatic))
        val mapping = ImportMapping(
            automatic.columns + mapOf(
                ImportField.TOTAL to 3,
                ImportField.CORRECT to 4,
            ),
        )
        val scan = CsvImport.scan(table, mapping, today, zone)
        assertTrue(scan.missingRequired.isEmpty())
        val candidate = scan.candidates.single()
        assertEquals(10, candidate.total)
        assertEquals(8, candidate.correct)
    }

    @Test
    fun crossMidnightEndIsPushedToNextDay() {
        val scan = scan("日期,科目,类别,题目,正确,时间\n2026-10-01,判断,语句,10,8,23:30-00:20\n")
        val candidate = scan.candidates.single()
        assertEquals(at(1, 23, 30), candidate.startAt)
        assertEquals(at(2, 0, 20), candidate.endAt)
        assertEquals(3000, candidate.durationSec)
    }

    @Test
    fun explicitTwentyFourHourEndKeepsTheSameDayOffset() {
        val scan = scan("日期,科目,类别,题目,正确,时间\n2026-10-01,判断,语句,10,8,23:30-24:20\n")
        val candidate = scan.candidates.single()
        assertEquals(at(2, 0, 20), candidate.endAt)
        assertEquals(3000, candidate.durationSec)
    }

    @Test
    fun singleTimeOnlySetsStart() {
        val scan = scan("日期,科目,类别,题目,正确,时间\n2026-10-01,判断,语句,10,8,09:15\n")
        val candidate = scan.candidates.single()
        assertEquals(at(1, 9, 15), candidate.startAt)
        assertNull(candidate.endAt)
        assertNull(candidate.durationSec)
    }

    @Test
    fun wrongCountCanReplaceCorrectCount() {
        val scan = scan("日期,科目,类别,题目,错题数\n2026-10-01,判断,语句,20,5\n")
        val candidate = scan.candidates.single()
        assertEquals(20, candidate.total)
        assertEquals(15, candidate.correct)
    }

    @Test
    fun correctAndWrongMustAgree() {
        val scan = scan("日期,科目,类别,题目,正确,错题数\n2026-10-01,判断,语句,10,7,4\n")
        assertEquals(1, scan.errors.size)
        assertTrue(scan.candidates.isEmpty())
    }

    @Test
    fun missingCorrectAndWrongColumnsBlockTheWholePreview() {
        val scan = scan("日期,科目,类别,题目\n2026-10-01,判断,语句,10\n")
        assertEquals(listOf(ImportField.CORRECT), scan.missingRequired)
        assertTrue(scan.candidates.isEmpty())
        assertTrue(scan.errors.isEmpty())
    }

    @Test
    fun badRowsReportTheirPhysicalLineAndAreNotCandidates() {
        val text = buildString {
            appendLine("日期,科目,类别,题目,正确,时间,净时长秒,对错序列,难度")
            appendLine("2026-10-01,判断,语句,10,8,09:00-09:30,1500,1010101010,难")
            appendLine("2026-10-30,判断,语句,10,8,,,,中")          // 未来
            appendLine("2026-10-01,判断,语句,0,0,,,,易")            // 题量为 0
            appendLine("2026-10-01,判断,语句,10,11,,,,易")          // 正确数超题量
            appendLine("2026-10-01,判断,语句,10,8,09:00-09:30,3600,,易") // 净时长超时段
            appendLine("2026-10-01,判断,语句,10,8,,,101,易")        // 序列短
            appendLine("2026-10-01,判断,语句,10,8,,,,超难")         // 难度非法
        }
        val scan = scan(text)
        assertEquals(1, scan.candidates.size)
        assertEquals(listOf(3, 4, 5, 6, 7, 8), scan.errors.map { it.line })
    }

    @Test
    fun slashesAndDotsInDatesAreAccepted() {
        val scan = scan("日期,科目,类别,题目,正确\n2026/10/01,判断,语句,10,8\n2026.10.02,判断,语句,10,8\n")
        assertEquals(listOf(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2)), scan.candidates.map { it.date })
    }

    @Test
    fun formulaGuardIsRemovedButNameStaysReadable() {
        val scan = scan("日期,科目,类别,题目,正确\n2026-10-01,'=判断,语句,10,8\n")
        assertEquals("=判断", scan.candidates.single().subjectName)
    }

    @Test
    fun invalidUtf8IsRejected() {
        try {
            CsvImport.decode(byteArrayOf(0xC3.toByte()))
            fail("应当是非法 UTF-8")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("UTF-8"))
        }
    }

    @Test
    fun tenThousandRowsAreAcceptedAndTheNextOneIsRejected() {
        fun rows(count: Int) = buildString {
            appendLine("日期,科目,类别,题目,正确")
            repeat(count) { appendLine("2026-10-01,判断,语句,10,8") }
        }
        val started = System.nanoTime()
        val accepted = scan(rows(CsvImport.MAX_ROWS))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(CsvImport.MAX_ROWS, accepted.candidates.size)
        assertEquals(CsvImport.MAX_ROWS, accepted.totalDataRows)
        println("CsvImport 10k rows parse+scan: ${elapsedMs}ms")
        assertTrue("1 万行解析耗时 ${elapsedMs}ms", elapsedMs < 2_000)
        try {
            CsvImport.parse(rows(CsvImport.MAX_ROWS + 1))
            fail("超过上限应当拒绝")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains(CsvImport.MAX_ROWS.toString()))
        }
    }

    @Test
    fun fingerprintUsesCategoryStartAndTotal() {
        val a = CsvImport.fingerprint(28, 1_000L, 10)
        assertEquals(a, CsvImport.fingerprint(28, 1_000L, 10))
        assertTrue(a != CsvImport.fingerprint(29, 1_000L, 10))
        assertTrue(a != CsvImport.fingerprint(28, 1_001L, 10))
        assertTrue(a != CsvImport.fingerprint(28, 1_000L, 11))
    }
}
