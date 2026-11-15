package com.tiji.studyclock.domain.model

/**
 * 题量、错题数、分钟、秒这类整数输入框的统一过滤口径：只保留数字并限长，
 * 粘贴进来的中文/字母/标点直接丢弃，避免脏值留在框里再被校验拦下。
 * 全角数字（中文输入法常见）转成半角，其余字符一律舍弃。
 */
object CountInput {
    const val MAX_DIGITS = 4

    fun sanitize(raw: String, maxDigits: Int = MAX_DIGITS): String {
        val limit = maxDigits.coerceAtLeast(1)
        val text = StringBuilder(limit)
        for (char in raw) {
            val digit = when (char) {
                in '0'..'9' -> char
                in '\uFF10'..'\uFF19' -> '0' + (char - '\uFF10')
                else -> null
            } ?: continue
            text.append(digit)
            if (text.length == limit) break
        }
        return text.toString()
    }
}
