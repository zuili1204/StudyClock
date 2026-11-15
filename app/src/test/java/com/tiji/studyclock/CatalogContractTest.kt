package com.tiji.studyclock

import com.tiji.studyclock.data.seed.CategorySeed
import com.tiji.studyclock.domain.diagnose.DiagnosisGenerator
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.stats.StatsEngine
import com.tiji.studyclock.domain.stats.Thresholds
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class CatalogContractTest {
    @Test fun categoryNamesMatchDesignAndPresetCoverageIds() {
        val expected = listOf(
            listOf("政治常识", "宪法常识", "行政法常识", "民法与民事诉讼法", "刑法与刑事诉讼法", "商法、经济法与社会法", "其他法律法规", "经济常识", "管理常识", "公文常识", "物理常识", "化学常识", "生物常识", "科技理论与成就", "历史常识", "文学常识", "文化常识", "自然环境", "国情社情", "生活常识"),
            listOf("逻辑填空", "主旨概括", "意图判断", "细节判断", "标题填入", "词句理解", "语句排序", "语句填充", "病句辨析"),
            listOf("数学运算", "数字推理"),
            listOf("图形推理", "定义判断", "类比推理", "逻辑判断"),
            listOf("增长与比重", "平均数与倍数", "综合分析"),
            listOf("归纳概括", "综合分析", "提出对策", "贯彻执行", "文章写作"),
        )
        assertEquals(listOf("常识判断", "言语理解与判断", "数量关系", "判断推理", "资料分析", "申论"), CategorySeed.subjects.map { it.name })
        assertEquals(listOf(20, 9, 2, 4, 3, 5), expected.map { it.size })
        expected.forEachIndexed { index, names ->
            assertEquals(names, CategorySeed.categories.filter { it.subjectId == index + 1L }.map { it.name })
        }
        assertEquals(43, CategorySeed.categories.size)
        assertEquals(Thresholds.PRESET_CATEGORY_IDS, CategorySeed.categories.map { it.id }.toSet())
    }

    @Test fun fullCatalogDoesNotHideRecordedCategoryBehindUnpracticedOnes() {
        val today = LocalDate.of(2026, 10, 28)
        val session = Session(id = 91, categoryId = 15, subjectId = 1,
            startAt = today.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), totalCount = 16, correctCount = 10)
        val snapshot = StatsEngine.calculate(listOf(session), CategorySeed.categories, today, ZoneOffset.UTC)
        val findings = DiagnosisGenerator.generate(snapshot, CategorySeed.categories)
        assertEquals(1, findings.size)
        assertEquals(15L, findings.single().categoryId)
        assertEquals(listOf(91L), findings.single().evidenceIds)
        assertTrue(findings.single().detail.contains("历史常识"))
    }
}
