package com.tiji.studyclock.domain.model

/**
 * 用户自填科目/类别/考试名称的规则。领域层只做纯校验，便于单测与复用。
 * 名称必须可读、可比较，且不能长到撑破列表。
 */
object NameRules {
    const val MAX_LENGTH = 20

    fun normalize(raw: String): String = raw.trim().replace(Regex("[ \\t]+"), " ")

    /** 返回可直接展示的错误文案；合法时返回 null。 */
    fun error(raw: String): String? {
        val name = normalize(raw)
        return when {
            name.isEmpty() -> "名称不能为空。"
            raw.any { it == '\n' || it == '\r' } -> "名称里不能有换行。"
            name.length > MAX_LENGTH -> "名称最多 $MAX_LENGTH 个字。"
            name.any { it.isISOControl() } -> "名称里不能有控制字符。"
            else -> null
        }
    }
}
