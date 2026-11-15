package com.tiji.studyclock.data.seed

import com.tiji.studyclock.domain.model.Category
import com.tiji.studyclock.domain.model.ExamType
import com.tiji.studyclock.domain.model.Subject

/** Stable v1 IDs, independent of display ordering. No practice data is seeded. */
object CategorySeed {
    val subjects: List<Subject> = listOf(
        Subject(1, "常识判断", "#C18448", 0, isPreset = true),
        Subject(2, "言语理解与判断", "#59836B", 1, isPreset = true),
        Subject(3, "数量关系", "#547C9C", 2, isPreset = true),
        Subject(4, "判断推理", "#8B70A4", 3, isPreset = true),
        Subject(5, "资料分析", "#B76864", 4, isPreset = true),
        Subject(6, "申论", "#7D838C", 5, isPreset = true),
    )

    val categories: List<Category> = listOf(
        Category(1, 1, "政治常识", sortOrder = 0, isPreset = true),
        Category(2, 1, "宪法常识", "法律", 1, isPreset = true),
        Category(3, 1, "行政法常识", "法律", 2, isPreset = true),
        Category(4, 1, "民法与民事诉讼法", "法律", 3, isPreset = true),
        Category(5, 1, "刑法与刑事诉讼法", "法律", 4, isPreset = true),
        Category(6, 1, "商法、经济法与社会法", "法律", 5, isPreset = true),
        Category(7, 1, "其他法律法规", "法律", 6, isPreset = true),
        Category(8, 1, "经济常识", sortOrder = 7, isPreset = true),
        Category(9, 1, "管理常识", sortOrder = 8, isPreset = true),
        Category(10, 1, "公文常识", sortOrder = 9, isPreset = true),
        Category(11, 1, "物理常识", "科技", 10, isPreset = true),
        Category(12, 1, "化学常识", "科技", 11, isPreset = true),
        Category(13, 1, "生物常识", "科技", 12, isPreset = true),
        Category(14, 1, "科技理论与成就", "科技", 13, isPreset = true),
        Category(15, 1, "历史常识", "人文", 14, isPreset = true),
        Category(16, 1, "文学常识", "人文", 15, isPreset = true),
        Category(17, 1, "文化常识", "人文", 16, isPreset = true),
        Category(18, 1, "自然环境", "地理", 17, isPreset = true),
        Category(19, 1, "国情社情", "地理", 18, isPreset = true),
        Category(20, 1, "生活常识", sortOrder = 19, isPreset = true),
        Category(21, 2, "逻辑填空", sortOrder = 0, isPreset = true),
        Category(22, 2, "主旨概括", "片段阅读", 1, isPreset = true),
        Category(23, 2, "意图判断", "片段阅读", 2, isPreset = true),
        Category(24, 2, "细节判断", "片段阅读", 3, isPreset = true),
        Category(25, 2, "标题填入", "片段阅读", 4, isPreset = true),
        Category(26, 2, "词句理解", "片段阅读", 5, isPreset = true),
        Category(27, 2, "语句排序", "语句表达", 6, isPreset = true),
        Category(28, 2, "语句填充", "语句表达", 7, isPreset = true),
        Category(29, 2, "病句辨析", "语句表达", 8, isPreset = true),
        Category(30, 3, "数学运算", sortOrder = 0, isPreset = true),
        Category(31, 3, "数字推理", sortOrder = 1, isPreset = true),
        Category(32, 4, "图形推理", sortOrder = 0, isPreset = true),
        Category(33, 4, "定义判断", sortOrder = 1, isPreset = true),
        Category(34, 4, "类比推理", sortOrder = 2, isPreset = true),
        Category(35, 4, "逻辑判断", sortOrder = 3, isPreset = true),
        Category(36, 5, "增长与比重", sortOrder = 0, isPreset = true),
        Category(37, 5, "平均数与倍数", sortOrder = 1, isPreset = true),
        Category(38, 5, "综合分析", sortOrder = 2, isPreset = true),
        Category(39, 6, "归纳概括", sortOrder = 0, isPreset = true),
        Category(40, 6, "综合分析", sortOrder = 1, isPreset = true),
        Category(41, 6, "提出对策", sortOrder = 2, isPreset = true),
        Category(42, 6, "贯彻执行", sortOrder = 3, isPreset = true),
        Category(43, 6, "文章写作", sortOrder = 4, isPreset = true),
    )

    /**
     * 其他考试的预置骨架：科目 ID 从 101 起、类别 ID 从 1001 起，与考公的 1–43 完全隔离。
     * 只给到“能直接开练”的粒度，更细的分类留给用户自建。
     */
    val extraSubjects: List<Subject> = listOf(
        Subject(101, "政治", "#A65A4A", 0, examType = ExamType.POSTGRAD, isPreset = true),
        Subject(102, "英语", "#4F6E8C", 1, examType = ExamType.POSTGRAD, isPreset = true),
        Subject(103, "数学", "#5F7A55", 2, examType = ExamType.POSTGRAD, isPreset = true),
        Subject(104, "专业课", "#7C6A93", 3, examType = ExamType.POSTGRAD, isPreset = true),
        Subject(111, "听力", "#4F6E8C", 0, examType = ExamType.CET, isPreset = true),
        Subject(112, "阅读", "#59836B", 1, examType = ExamType.CET, isPreset = true),
        Subject(113, "写作", "#B4772F", 2, examType = ExamType.CET, isPreset = true),
        Subject(114, "翻译", "#8B70A4", 3, examType = ExamType.CET, isPreset = true),
        Subject(121, "综合素质", "#C18448", 0, examType = ExamType.TEACHER_CERT, isPreset = true),
        Subject(122, "教育知识与能力", "#547C9C", 1, examType = ExamType.TEACHER_CERT, isPreset = true),
        Subject(123, "学科知识", "#7D838C", 2, examType = ExamType.TEACHER_CERT, isPreset = true),
        Subject(131, "公共基础", "#5F7A55", 0, examType = ExamType.COMPUTER, isPreset = true),
        Subject(132, "程序设计", "#4F6E8C", 1, examType = ExamType.COMPUTER, isPreset = true),
        Subject(133, "办公软件", "#B4772F", 2, examType = ExamType.COMPUTER, isPreset = true),
        Subject(141, "听力", "#4F6E8C", 0, examType = ExamType.IELTS, isPreset = true),
        Subject(142, "阅读", "#59836B", 1, examType = ExamType.IELTS, isPreset = true),
        Subject(143, "写作", "#B4772F", 2, examType = ExamType.IELTS, isPreset = true),
        Subject(144, "口语", "#8B70A4", 3, examType = ExamType.IELTS, isPreset = true),
    )

    val extraCategories: List<Category> = listOf(
        Category(1001, 101, "马原", "政治", 0, isPreset = true),
        Category(1002, 101, "毛中特", "政治", 1, isPreset = true),
        Category(1003, 101, "史纲", "政治", 2, isPreset = true),
        Category(1004, 101, "思修法基", "政治", 3, isPreset = true),
        Category(1005, 101, "时政", "政治", 4, isPreset = true),
        Category(1011, 102, "完形填空", sortOrder = 0, isPreset = true),
        Category(1012, 102, "阅读理解", sortOrder = 1, isPreset = true),
        Category(1013, 102, "新题型", sortOrder = 2, isPreset = true),
        Category(1014, 102, "翻译", sortOrder = 3, isPreset = true),
        Category(1015, 102, "写作", sortOrder = 4, isPreset = true),
        Category(1021, 103, "高等数学", sortOrder = 0, isPreset = true),
        Category(1022, 103, "线性代数", sortOrder = 1, isPreset = true),
        Category(1023, 103, "概率统计", sortOrder = 2, isPreset = true),
        Category(1031, 104, "专业课", sortOrder = 0, isPreset = true),
        Category(1111, 111, "短篇新闻", sortOrder = 0, isPreset = true),
        Category(1112, 111, "长对话", sortOrder = 1, isPreset = true),
        Category(1113, 111, "听力篇章", sortOrder = 2, isPreset = true),
        Category(1121, 112, "选词填空", sortOrder = 0, isPreset = true),
        Category(1122, 112, "长篇阅读", sortOrder = 1, isPreset = true),
        Category(1123, 112, "仔细阅读", sortOrder = 2, isPreset = true),
        Category(1131, 113, "写作", sortOrder = 0, isPreset = true),
        Category(1141, 114, "翻译", sortOrder = 0, isPreset = true),
        Category(1211, 121, "职业理念", sortOrder = 0, isPreset = true),
        Category(1212, 121, "法律法规", sortOrder = 1, isPreset = true),
        Category(1213, 121, "文化素养", sortOrder = 2, isPreset = true),
        Category(1214, 121, "写作", sortOrder = 3, isPreset = true),
        Category(1221, 122, "教育学", sortOrder = 0, isPreset = true),
        Category(1222, 122, "心理学", sortOrder = 1, isPreset = true),
        Category(1223, 122, "教学实施", sortOrder = 2, isPreset = true),
        Category(1231, 123, "学科知识", sortOrder = 0, isPreset = true),
        Category(1311, 131, "数据结构", sortOrder = 0, isPreset = true),
        Category(1312, 131, "操作系统", sortOrder = 1, isPreset = true),
        Category(1313, 131, "数据库", sortOrder = 2, isPreset = true),
        Category(1321, 132, "语言基础", sortOrder = 0, isPreset = true),
        Category(1322, 132, "编程题", sortOrder = 1, isPreset = true),
        Category(1331, 133, "Word", sortOrder = 0, isPreset = true),
        Category(1332, 133, "Excel", sortOrder = 1, isPreset = true),
        Category(1333, 133, "PPT", sortOrder = 2, isPreset = true),
        Category(1411, 141, "Section 1–2", sortOrder = 0, isPreset = true),
        Category(1412, 141, "Section 3–4", sortOrder = 1, isPreset = true),
        Category(1421, 142, "判断题", sortOrder = 0, isPreset = true),
        Category(1422, 142, "匹配题", sortOrder = 1, isPreset = true),
        Category(1423, 142, "填空题", sortOrder = 2, isPreset = true),
        Category(1431, 143, "小作文", sortOrder = 0, isPreset = true),
        Category(1432, 143, "大作文", sortOrder = 1, isPreset = true),
        Category(1441, 144, "Part 1", sortOrder = 0, isPreset = true),
        Category(1442, 144, "Part 2", sortOrder = 1, isPreset = true),
        Category(1443, 144, "Part 3", sortOrder = 2, isPreset = true),
    )

    /**
     * 预置基线 ID（D34）：分类管理据此锁定改名与删除。
     * 自定义项由自增主键排在所有预置 ID 之后，不会落入这两个集合。
     */
    val presetSubjectIds: Set<Long> = (subjects + extraSubjects).map { it.id }.toSet()
    val presetCategoryIds: Set<Long> = (categories + extraCategories).map { it.id }.toSet()
}
