package com.tiji.studyclock

import com.tiji.studyclock.domain.model.CatalogRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.12 分类管理：改名、排序、归档与删除的纯规则（D34–D38）。 */
class CatalogRulesTest {

    private val siblings = listOf(1L to "常识判断", 2L to "言语理解与判断")

    @Test
    fun presetItemsCannotBeRenamedEvenWhenTheNameIsInvalid() {
        // 预置锁优先于名称规则：只说「不能改」，不拿空名称误导用户。
        assertEquals(
            CatalogRules.PRESET_LOCKED,
            CatalogRules.renameError("", siblings, selfId = 1, isPreset = true),
        )
        assertEquals(
            CatalogRules.PRESET_LOCKED,
            CatalogRules.renameError("新名字", siblings, selfId = 1, isPreset = true),
        )
    }

    @Test
    fun renameFollowsNameRulesAndRejectsDuplicates() {
        assertEquals("名称不能为空。", CatalogRules.renameError("   ", siblings, 3, isPreset = false))
        assertEquals(
            "同一层级已有同名项，换一个名字。",
            CatalogRules.renameError("常识判断", siblings, selfId = 3, isPreset = false),
        )
        // 只和自己同名不算重复；去掉首尾空白后判重。
        assertNull(CatalogRules.renameError("常识判断", siblings, selfId = 1, isPreset = false))
        assertNull(CatalogRules.renameError("  常识判断  ", siblings, selfId = 1, isPreset = false))
        assertNull(CatalogRules.renameError(" 资料分析 ", siblings, selfId = 3, isPreset = false))
    }

    @Test
    fun renameRejectsOverlongNames() {
        val error = CatalogRules.renameError("一".repeat(21), siblings, 3, isPreset = false)
        assertEquals("名称最多 20 个字。", error)
    }

    @Test
    fun movedSwapsNeighboursAndStopsAtEdges() {
        val ids = listOf(10L, 20L, 30L)
        assertEquals(listOf(20L, 10L, 30L), CatalogRules.moved(ids, 20L, -1))
        assertEquals(listOf(20L, 30L, 10L), CatalogRules.moved(ids, 10L, 2))
        assertEquals(listOf(10L, 30L, 20L), CatalogRules.moved(ids, 30L, -1))
        assertNull(CatalogRules.moved(ids, 10L, -1))
        assertNull(CatalogRules.moved(ids, 30L, 1))
        assertNull(CatalogRules.moved(ids, 99L, 1))
        assertNull(CatalogRules.moved(ids, 20L, 0))
        assertNull(CatalogRules.moved(emptyList(), 1L, 1))
    }

    @Test
    fun movedReturnsAFullOrderWithoutMutatingTheInput() {
        val ids = listOf(1L, 2L, 3L)
        val next = CatalogRules.moved(ids, 2L, 1)
        assertEquals(listOf(1L, 3L, 2L), next)
        assertEquals(listOf(1L, 2L, 3L), ids)
        assertEquals(ids.sorted(), next!!.sorted())
    }

    @Test
    fun lastActiveSubjectCannotBeArchived() {
        assertEquals(
            "至少保留一个启用科目；可以先新建一个，再归档这个。",
            CatalogRules.subjectArchiveError(otherActiveSubjects = 0),
        )
        assertNull(CatalogRules.subjectArchiveError(otherActiveSubjects = 1))
    }

    @Test
    fun lastActiveCategoryCannotBeArchivedUnlessTheSubjectIsArchived() {
        assertTrue(
            CatalogRules.categoryArchiveError(otherActiveCategories = 0, subjectArchived = false)!!
                .contains("至少要保留一个启用类别"),
        )
        assertNull(CatalogRules.categoryArchiveError(otherActiveCategories = 0, subjectArchived = true))
        assertNull(CatalogRules.categoryArchiveError(otherActiveCategories = 2, subjectArchived = false))
    }

    @Test
    fun deleteBlockerPutsPresetBeforeReferences() {
        val references = CatalogRules.References(records = 3)
        assertEquals(CatalogRules.PRESET_LOCKED, CatalogRules.deleteError(isPreset = true, references))
        assertNull(CatalogRules.deleteError(isPreset = false, CatalogRules.References()))
    }

    @Test
    fun deleteBlockerListsOnlyTheReferencesThatExist() {
        val recordsOnly = CatalogRules.deleteError(
            isPreset = false,
            CatalogRules.References(records = 12),
        )
        assertEquals("还有 12 条记录引用它，不能删除；可以改为归档。", recordsOnly)

        val mixed = CatalogRules.deleteError(
            isPreset = false,
            CatalogRules.References(records = 12, batches = 2, prescriptions = 0),
        )
        assertEquals("还有 12 条记录、2 个错题袋引用它，不能删除；可以改为归档。", mixed)

        val all = CatalogRules.deleteError(
            isPreset = false,
            CatalogRules.References(records = 1, batches = 1, prescriptions = 1),
        )
        assertEquals("还有 1 条记录、1 个错题袋、1 份处方引用它，不能删除；可以改为归档。", all)
        // 不能出现「0 条记录」这种把零值也念出来的文案。
        assertTrue(recordsOnly!!.contains("12 条记录") && !recordsOnly.contains("0 "))
    }

    @Test
    fun referenceTotalCountsEverySource() {
        assertEquals(0, CatalogRules.References().total)
        assertEquals(15, CatalogRules.References(records = 12, batches = 2, prescriptions = 1).total)
    }
}
