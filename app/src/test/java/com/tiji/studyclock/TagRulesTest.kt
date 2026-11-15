package com.tiji.studyclock

import com.tiji.studyclock.domain.model.TagRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TagRulesTest {
    @Test
    fun parsesEverySupportedSeparatorAndStripsBrackets() {
        assertEquals(
            listOf("属性规律", "增长率", "基期", "比重"),
            TagRules.parse("【属性规律】、增长率, 基期；比重"),
        )
    }

    @Test
    fun collapsesWhitespaceDeduplicatesAndCapsAtFive() {
        assertEquals(listOf("数量 关系", "资料 分析"), TagRules.parse("  数量   关系 | 资料 分析 | 数量 关系 "))
        assertEquals(5, TagRules.parse("一,二,三,四,五,六").size)
    }

    @Test
    fun normalizeReturnsNullForBlankAndCanonicalStringOtherwise() {
        assertNull(TagRules.normalize(null))
        assertNull(TagRules.normalize("  【】  "))
        assertEquals("属性规律|增长率", TagRules.normalize("属性规律，增长率"))
    }

    @Test
    fun errorRejectsMoreThanFiveOrLongerThanTwelveCharacters() {
        assertEquals("最多记 5 个关键词。", TagRules.error("一|二|三|四|五|六"))
        assertNull(TagRules.error("一|一|二|三|四|五"))
        assertEquals("每个关键词最多 12 个字。", TagRules.error("这是一个超过十二个字的关键词"))
        assertNull(TagRules.error("属性规律|增长率-基期"))
        assertNull(TagRules.error(null))
    }

    @Test
    fun appendKeepsOrderAndRejectsOverflowWithoutLosingExisting() {
        val existing = TagRules.normalize("属性规律")
        val added = TagRules.append(existing, "增长率、属性规律")
        assertNull(added.error)
        assertEquals("属性规律|增长率", added.value)

        val full = TagRules.normalize("一|二|三|四|五")
        val overflow = TagRules.append(full, "六")
        assertEquals("最多记 5 个关键词。", overflow.error)
        assertEquals(full, overflow.value)
    }

    @Test
    fun removeAndControlCharacterCleaning() {
        assertEquals("增长率", TagRules.remove("属性规律|增长率", "属性规律"))
        assertNull(TagRules.remove("属性规律", "属性规律"))
        assertEquals("属性规律", TagRules.clean("【属性\u0007规律】"))
    }
}
