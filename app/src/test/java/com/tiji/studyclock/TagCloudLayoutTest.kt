package com.tiji.studyclock

import com.tiji.studyclock.domain.stats.TagCloudLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagCloudLayoutTest {
    @Test
    fun singleWeightUsesMaxSizeAndEqualWeightsUseMiddleSize() {
        assertEquals(listOf(30f), TagCloudLayout.scale(listOf(4), 12f, 30f))
        assertEquals(listOf(21f, 21f), TagCloudLayout.scale(listOf(4, 4), 12f, 30f))
    }

    @Test
    fun higherWeightAlwaysGetsLargerOrEqualFont() {
        val sizes = TagCloudLayout.scale(listOf(1, 4, 9, 16), 12f, 30f)
        assertEquals(4, sizes.size)
        assertEquals(30f, sizes.last(), 1e-6f)
        assertEquals(12f, sizes.first(), 1e-6f)
        assertTrue(sizes.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun widthEstimateTreatsCjkAsFullEmAndLatinAsNarrower() {
        val cjk = TagCloudLayout.estimateWidth("属性规律", 20f)
        val latin = TagCloudLayout.estimateWidth("abcd", 20f)
        assertTrue(cjk > latin)
        assertEquals(4 * 20f + TagCloudLayout.PADDING * 2, cjk, 1e-6f)
    }

    @Test
    fun placementsStayInsideTheBoxAndDoNotOverlap() {
        val items = (1..8).map { TagCloudLayout.Item("关键词$it", 14f + it) }
        val placements = TagCloudLayout.place(320f, 190f, items)
        assertTrue(placements.isNotEmpty())
        placements.forEach { placement ->
            assertTrue(placement.left >= 0f)
            assertTrue(placement.top >= 0f)
            assertTrue(placement.left + placement.width <= 320f + 0.01f)
            assertTrue(placement.top + placement.height <= 190f + 0.01f)
        }
        placements.indices.forEach { i ->
            (i + 1 until placements.size).forEach { j ->
                val a = placements[i]
                val b = placements[j]
                val overlap = a.left < b.left + b.width + TagCloudLayout.PADDING &&
                    b.left < a.left + a.width + TagCloudLayout.PADDING &&
                    a.top < b.top + b.height + TagCloudLayout.PADDING &&
                    b.top < a.top + a.height + TagCloudLayout.PADDING
                assertFalse("第 $i 与第 $j 个词重叠", overlap)
            }
        }
    }

    @Test
    fun itemsThatCannotFitAreSkippedInsteadOfClipped() {
        val placements = TagCloudLayout.place(50f, 30f, listOf(TagCloudLayout.Item("属性规律与增长率", 24f)))
        assertTrue(placements.isEmpty())
        assertTrue(TagCloudLayout.place(0f, 100f, listOf(TagCloudLayout.Item("a", 12f))).isEmpty())
    }
}
