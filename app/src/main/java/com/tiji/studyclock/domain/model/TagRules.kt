package com.tiji.studyclock.domain.model

/**
 * 错题关键词的规范化规则。关键词是用户自己的短标签（如“属性规律”），
 * 统一存成 `|` 分隔的规范串，展示与聚合时再拆开，避免每个入口各写一套解析。
 */
object TagRules {
    const val MAX_TAGS = 5
    const val MAX_LENGTH = 12
    const val SEPARATOR = '|'

    private val SPLIT = Regex("[|,，、;；/\\n\\r]+")
    private val WHITESPACE = Regex("\\s+")

    /**
     * 拆分并清洗：去掉成对的【】（用户习惯写法）、压缩空白、去重、限制 5 个。
     * 只用于展示与写入；长度校验交给 [error]。
     */
    fun parse(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(SPLIT).mapNotNull(::clean).distinct().take(MAX_TAGS)
    }

    /** null 表示没有关键词；有内容时返回 `|` 分隔的规范串。 */
    fun normalize(raw: String?): String? = parse(raw).takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR.toString())

    /** 写入前的用户可读校验；返回 null 表示可以保存。 */
    fun error(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(SPLIT).mapNotNull(::clean).distinct()
        if (parts.size > MAX_TAGS) return "最多记 $MAX_TAGS 个关键词。"
        if (parts.any { it.length > MAX_LENGTH }) return "每个关键词最多 $MAX_LENGTH 个字。"
        return null
    }

    fun clean(raw: String): String? {
        var text = raw.filterNot { it.isISOControl() }.trim()
        if (text.length >= 2 && text.first() == '【' && text.last() == '】') {
            text = text.substring(1, text.length - 1)
        }
        text = text.replace(WHITESPACE, " ").trim()
        return text.takeIf { it.isNotEmpty() }
    }

    /** 把一段自由输入追加到已有标签后；超过上限或过长时原样返回并给出原因。 */
    fun append(existing: String?, raw: String): Result {
        val current = parse(existing)
        val additions = parse(raw).filterNot { it in current }
        if (additions.isEmpty()) return Result(current.joinToString(SEPARATOR.toString()), null)
        val merged = (current + additions).distinct()
        if (merged.size > MAX_TAGS) return Result(existing, "最多记 $MAX_TAGS 个关键词。")
        if (merged.any { it.length > MAX_LENGTH }) return Result(existing, "每个关键词最多 $MAX_LENGTH 个字。")
        return Result(merged.joinToString(SEPARATOR.toString()), null)
    }

    fun remove(existing: String?, tag: String): String? =
        parse(existing).filterNot { it == tag }.takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR.toString())

    data class Result(val value: String?, val error: String?)
}
