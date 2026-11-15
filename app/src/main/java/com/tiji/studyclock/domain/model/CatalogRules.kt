package com.tiji.studyclock.domain.model

/**
 * 分类管理（H2-6）的纯规则。改名、排序、归档与删除的判据都在这里，
 * 数据层只负责按结论落库，界面只负责展示原因。这样不依赖数据库也能覆盖边界。
 */
object CatalogRules {
    /** 预置基线锁定的统一文案；改名与删除共用（D34）。 */
    const val PRESET_LOCKED = "预置科目与类别是统计和导出的公共基准，不能改名或删除；需要别的叫法可以归档后自建。"

    /** 一处引用计数：记录、错题袋、处方。任一为正即不可删除（D37）。 */
    data class References(
        val records: Int = 0,
        val batches: Int = 0,
        val prescriptions: Int = 0,
    ) {
        val total: Int get() = records + batches + prescriptions
    }

    /**
     * 改名校验。[siblings] 是同一作用域内的 `(id, name)`：科目按考试、类别按科目。
     * 返回可直接展示的错误，null 表示通过。
     */
    fun renameError(
        rawName: String,
        siblings: List<Pair<Long, String>>,
        selfId: Long,
        isPreset: Boolean,
    ): String? {
        if (isPreset) return PRESET_LOCKED
        NameRules.error(rawName)?.let { return it }
        val name = NameRules.normalize(rawName)
        return if (siblings.any { it.first != selfId && it.second == name }) {
            "同一层级已有同名项，换一个名字。"
        } else {
            null
        }
    }

    /**
     * 上移/下移：返回新的完整顺序；[id] 不存在、[delta] 为 0 或已到边界时返回 null。
     * 返回的顺序由调用方在一个事务里写成 0..n-1（D36）。
     */
    fun moved(ids: List<Long>, id: Long, delta: Int): List<Long>? {
        if (delta == 0) return null
        val index = ids.indexOf(id)
        if (index < 0) return null
        val target = index + delta
        if (target !in ids.indices) return null
        return ids.toMutableList().apply { add(target, removeAt(index)) }
    }

    /** 科目归档守卫：[otherActiveSubjects] 为其它启用科目数，为 0 表示这是最后一个（D38）。 */
    fun subjectArchiveError(otherActiveSubjects: Int): String? =
        if (otherActiveSubjects > 0) null
        else "至少保留一个启用科目；可以先新建一个，再归档这个。"

    /**
     * 类别归档守卫：科目已归档时不再设限（整个科目都不可选了）；
     * 否则要求同科目内还有其它启用类别（D38）。
     */
    fun categoryArchiveError(otherActiveCategories: Int, subjectArchived: Boolean): String? = when {
        subjectArchived -> null
        otherActiveCategories > 0 -> null
        else -> "这个科目至少要保留一个启用类别；不想用这个科目就归档整个科目。"
    }

    /** 删除守卫：返回 null 表示可以删除；预置优先于引用计数，文案更贴近原因。 */
    fun deleteError(isPreset: Boolean, references: References): String? {
        if (isPreset) return PRESET_LOCKED
        if (references.total == 0) return null
        val parts = buildList {
            if (references.records > 0) add("${references.records} 条记录")
            if (references.batches > 0) add("${references.batches} 个错题袋")
            if (references.prescriptions > 0) add("${references.prescriptions} 份处方")
        }
        return "还有 ${parts.joinToString("、")}引用它，不能删除；可以改为归档。"
    }
}
