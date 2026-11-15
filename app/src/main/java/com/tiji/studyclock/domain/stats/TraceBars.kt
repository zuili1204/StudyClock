package com.tiji.studyclock.domain.stats

import java.time.LocalDate

/**
 * 月历「痕迹」视图里的一根条：一条轨、一天。
 *
 * [ratio] 是条长（0..1），[present] 表示当天是否有这类记录，[quality] 是该轨的质量样本
 * （题条=正确率、复条=复做通过率，其余为 null）。三者互不合成，UI 只按它们画形状。
 */
data class TraceBar(
    val track: DayTrack,
    val ratio: Double,
    val present: Boolean,
    val quality: Double?,
)

/** 本月各投入轨的峰值，只用于把条长归一到 0..1；表现轨直接用 0..100% 当长度。 */
data class TraceScale(
    val questions: Int,
    val redoQuestions: Int,
    val focusSeconds: Long,
) {
    companion object {
        val EMPTY = TraceScale(0, 0, 0)
    }
}

/**
 * 月历痕迹条的唯一取数口径（纯 JVM）。
 *
 * 四轨各画各的，不做加权、不做「综合强度」——这是 D39 在痕迹视图上的延续：
 * 一根条只回答「这条轨当天有多少」，绝不回答「这天整体怎么样」。
 *
 * 长度规则：
 * - 题 / 复 / 专：与本月的峰值等比，所以同一条轨在整月里可以横向比较；
 * - 分：直接按得分率（0–100%）画，因为「得分率」本身就是比率，再按峰值归一会让
 *   90% 与 60% 看起来一样长。
 */
object TraceBars {
    fun scale(days: Map<LocalDate, DaySummary>): TraceScale = TraceScale(
        questions = days.values.maxOfOrNull { it.performanceQuestions } ?: 0,
        redoQuestions = days.values.maxOfOrNull { it.redoQuestions } ?: 0,
        focusSeconds = days.values.maxOfOrNull { it.focusSeconds } ?: 0L,
    )

    fun bars(summary: DaySummary?, scale: TraceScale): List<TraceBar> = DayTrack.entries.map { track ->
        if (summary == null) {
            TraceBar(track, 0.0, false, null)
        } else {
            when (track) {
                DayTrack.OBJECTIVE -> TraceBar(
                    track = track,
                    // 只用客观题题量：复做题量属于「复」条，两条轨不能重复计同一批题。
                    ratio = ratio(summary.performanceQuestions.toDouble(), scale.questions.toDouble()),
                    present = summary.objectiveSessions > 0,
                    quality = summary.accuracy,
                )
                DayTrack.SCORED -> TraceBar(
                    track = track,
                    ratio = (summary.scoreRate ?: 0.0).coerceIn(0.0, 1.0),
                    present = summary.scoredSessions > 0,
                    quality = null,
                )
                DayTrack.REDO -> TraceBar(
                    track = track,
                    ratio = ratio(summary.redoQuestions.toDouble(), scale.redoQuestions.toDouble()),
                    present = summary.redoSessions > 0,
                    quality = summary.redoRate,
                )
                DayTrack.FOCUS -> TraceBar(
                    track = track,
                    ratio = ratio(summary.focusSeconds.toDouble(), scale.focusSeconds.toDouble()),
                    present = summary.focusSessions > 0,
                    quality = null,
                )
            }
        }
    }

    private fun ratio(value: Double, max: Double): Double =
        if (max <= 0.0 || value <= 0.0) 0.0 else (value / max).coerceIn(0.0, 1.0)
}
