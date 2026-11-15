package com.tiji.studyclock.domain.stats

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 词云的纯布局计算：只做尺寸估计与摆放，不依赖 Android 的字体测量。
 * 先用估算宽度保证“不重叠、不出界”，界面再用真实字体绘制；
 * 估算刻意略宽，宁可留白，也不让两个词叠在一起。
 */
object TagCloudLayout {
    const val PADDING = 4f
    const val LINE_HEIGHT = 1.35f

    data class Item(val text: String, val sizePx: Float)
    data class Placement(val index: Int, val left: Float, val top: Float, val width: Float, val height: Float)

    /** 按词频开方缩放字号：低频不会小到看不见，高频也不会一家独大。 */
    fun scale(weights: List<Int>, minSize: Float, maxSize: Float): List<Float> {
        if (weights.isEmpty()) return emptyList()
        val min = weights.min()
        val max = weights.max()
        if (max == min) {
            val size = if (weights.size == 1) maxSize else (minSize + maxSize) / 2f
            return weights.map { size }
        }
        val span = sqrt((max - min).toFloat())
        return weights.map { weight ->
            val ratio = sqrt((weight - min).toFloat()) / span
            minSize + (maxSize - minSize) * ratio
        }
    }

    /** 中日韩字符按 1em 估宽，其余按 0.62em；两端各留一点呼吸空间。 */
    fun estimateWidth(text: String, sizePx: Float): Float {
        var em = 0f
        text.forEach { char ->
            em += if (char.code >= 0x2E80) 1f else 0.62f
        }
        return em * sizePx + PADDING * 2
    }

    fun place(width: Float, height: Float, items: List<Item>): List<Placement> {
        if (width <= 0f || height <= 0f) return emptyList()
        val placed = mutableListOf<Placement>()
        items.forEachIndexed { index, item ->
            val itemWidth = estimateWidth(item.text, item.sizePx)
            val itemHeight = item.sizePx * LINE_HEIGHT
            if (itemWidth > width || itemHeight > height) return@forEachIndexed
            val spot = findSpot(width, height, itemWidth, itemHeight, placed) ?: return@forEachIndexed
            placed += Placement(index, spot.first, spot.second, itemWidth, itemHeight)
        }
        return placed
    }

    private fun findSpot(
        width: Float,
        height: Float,
        itemWidth: Float,
        itemHeight: Float,
        placed: List<Placement>,
    ): Pair<Float, Float>? {
        val centerX = (width - itemWidth) / 2f
        val centerY = (height - itemHeight) / 2f
        if (fits(centerX, centerY, itemWidth, itemHeight, width, height, placed)) return centerX to centerY
        val radialStep = 6f
        val angleStep = 0.45f
        val maxRadius = hypot(width, height)
        var radius = radialStep
        var angle = 0f
        while (radius <= maxRadius) {
            val left = centerX + cos(angle) * radius
            val top = centerY + sin(angle) * radius * 0.75f
            if (fits(left, top, itemWidth, itemHeight, width, height, placed)) return left to top
            angle += angleStep
            if (angle >= (Math.PI * 2).toFloat()) {
                angle -= (Math.PI * 2).toFloat()
                radius += radialStep
            }
        }
        return null
    }

    private fun fits(
        left: Float,
        top: Float,
        itemWidth: Float,
        itemHeight: Float,
        width: Float,
        height: Float,
        placed: List<Placement>,
    ): Boolean {
        if (left < 0f || top < 0f || left + itemWidth > width || top + itemHeight > height) return false
        return placed.none { other ->
            left < other.left + other.width + PADDING &&
                other.left < left + itemWidth + PADDING &&
                top < other.top + other.height + PADDING &&
                other.top < top + itemHeight + PADDING
        }
    }
}
