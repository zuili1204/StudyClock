package com.tiji.studyclock.domain.model

import java.time.LocalTime

/** 解析后的时刻；[dayOffset] 为 1 表示用户写的是 24:xx，属于次日。 */
data class ParsedClock(val time: LocalTime, val dayOffset: Int)

/**
 * 补记/导入时的时刻输入。接受 `21:16`、`9:5`、`21：16`（全角冒号），以及 `24:03`（次日零点三分）。
 * 只做纯解析与校验，不读时钟、不碰数据库。
 */
object ClockInput {
    fun parse(raw: String): ParsedClock? {
        val text = raw.trim().replace('：', ':').replace(" ", "")
        if (text.isEmpty()) return null
        val parts = text.split(':')
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (minute !in 0..59) return null
        return when (hour) {
            in 0..23 -> ParsedClock(LocalTime.of(hour, minute), 0)
            24 -> ParsedClock(LocalTime.of(0, minute), 1)
            else -> null
        }
    }

    /** 起止时刻的净秒数；结束早于开始且未写次日时返回 null，由调用方给文案。 */
    fun durationSeconds(start: ParsedClock, end: ParsedClock): Int? {
        if (start.dayOffset != 0) return null
        val seconds = (end.dayOffset * 24 * 60 * 60 + end.time.toSecondOfDay()) - start.time.toSecondOfDay()
        return if (seconds > 0) seconds else null
    }

    fun format(clock: ParsedClock): String =
        if (clock.dayOffset == 1) String.format("%02d:%02d", clock.time.hour + 24, clock.time.minute)
        else String.format("%02d:%02d", clock.time.hour, clock.time.minute)
}
