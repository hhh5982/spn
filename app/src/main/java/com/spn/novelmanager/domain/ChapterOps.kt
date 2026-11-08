package com.spn.novelmanager.domain

/**
 * 章节结构的写操作：
 * - [normalize] 把每章归一化为独立的起始/结束标记块，避免共享块导致孤儿标记；
 * - [insertAfter] 在当前章之后新增章节，并把后续标题编号顺延 +1；
 * - [deleteChapter] 删除当前章，并把后续标题编号顺延 -1。
 */
object ChapterOps {

    private val numberTitleRegex = Regex(
        "^(第\\s*)([0-9０-９]+|[零〇一二三四五六七八九十百千万两]+)(\\s*[章节回卷])(.*)$"
    )

    fun normalize(text: String, chapters: List<Chapter>, startMark: String, endMark: String): String {
        if (chapters.isEmpty()) return text
        return rebuild(text, chapters, startMark, endMark, insertAfter = null, deleteAt = null, newTitle = null)
    }

    fun insertAfter(
        text: String,
        chapters: List<Chapter>,
        index: Int,
        startMark: String,
        endMark: String,
        newTitle: String
    ): String {
        if (chapters.isEmpty()) return text
        val normalized = normalize(text, chapters, startMark, endMark)
        val parsed = ChapterParser.parse(normalized, startMark, endMark)
        if (parsed.isEmpty() || index !in parsed.indices) return normalized
        return rebuild(
            normalized,
            parsed,
            startMark,
            endMark,
            insertAfter = index,
            deleteAt = null,
            newTitle = newTitle
        )
    }

    fun deleteChapter(
        text: String,
        chapters: List<Chapter>,
        index: Int,
        startMark: String,
        endMark: String
    ): String? {
        if (chapters.size <= 1) return null
        if (index !in chapters.indices) return null
        val normalized = normalize(text, chapters, startMark, endMark)
        val parsed = ChapterParser.parse(normalized, startMark, endMark)
        if (parsed.size <= 1 || index !in parsed.indices) return null
        return rebuild(
            normalized,
            parsed,
            startMark,
            endMark,
            insertAfter = null,
            deleteAt = index,
            newTitle = null
        )
    }

    /** 自动分章：无视已有标记，按全文「第*章」重新切分并重新补上标记。 */
    fun autoChapter(
        text: String,
        startMark: String,
        endMark: String,
        prefix: String,
        suffix: String,
        minInnerLength: Int,
        maxInnerLength: Int?
    ): Pair<String, Int>? {
        val stripped = stripMarks(text, startMark.trim(), endMark.trim())
        val titles = AutoChapter.findTitleLines(stripped, prefix, suffix, minInnerLength, maxInnerLength)
        if (titles.isEmpty()) return null
        val wrapped = AutoChapter.insertMarks(
            stripped,
            titles.map { it.startOffset },
            startMark.trim(),
            endMark.trim()
        )
        return wrapped to titles.size
    }

    /** 删除所有标记（自动分章前清理旧结构）。 */
    fun stripMarks(text: String, startMark: String, endMark: String): String {
        var result = text
        if (startMark.isNotEmpty()) result = result.replace(startMark, "")
        if (endMark.isNotEmpty()) result = result.replace(endMark, "")
        return result
    }

    /**
     * 新增章节的标题：沿用当前章节的编号 +1（如第 50 章之后新增得到「第51章」）；
     * 当前标题无法识别编号时，按位置 [fallbackNumber] 生成「第N章」。
     */
    fun nextChapterTitle(currentTitle: String, fallbackNumber: Int): String {
        val match = numberTitleRegex.find(currentTitle.trim())
            ?: return "第${fallbackNumber}章"
        val numberText = match.groupValues[2]
        val value = parseNumber(numberText) ?: return "第${fallbackNumber}章"
        val next = value + 1
        val rendered = if (isAsciiNumber(numberText)) next.toString() else intToChinese(next)
        return "第${rendered}章"
    }

    /** 标题编号顺延：识别「第N章/节/回/卷」，非编号标题保持不变。 */
    fun renumberTitle(title: String, delta: Int): String {
        val match = numberTitleRegex.find(title.trim()) ?: return title
        val head = match.groupValues[1]
        val numberText = match.groupValues[2]
        val tail = match.groupValues[3] + match.groupValues[4]
        val value = parseNumber(numberText) ?: return title
        val next = (value + delta).coerceAtLeast(1)
        val rendered = if (isAsciiNumber(numberText)) next.toString() else intToChinese(next)
        return head + rendered + tail
    }

    private fun isAsciiNumber(text: String): Boolean =
        text.all { it.isDigit() || it in '０'..'９' }

    private fun parseNumber(text: String): Int? = if (isAsciiNumber(text)) {
        text.map { if (it in '０'..'９') it - '０' + '0'.code else it - '0' }.joinToString("").toIntOrNull()
    } else {
        chineseToInt(text)
    }

    private fun rebuild(
        text: String,
        chapters: List<Chapter>,
        startMark: String,
        endMark: String,
        insertAfter: Int?,
        deleteAt: Int?,
        newTitle: String?
    ): String {
        val sb = StringBuilder(text.length + chapters.size * 8)
        val first = chapters.first()
        sb.append(text, 0, first.blockStartOffset.coerceIn(0, text.length))

        fun ensureNewline() {
            if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
        }

        fun emit(raw: String) {
            val body = raw.trim('\n', '\r')
            ensureNewline()
            sb.append(startMark).append('\n')
            sb.append(body)
            sb.append('\n').append(endMark).append('\n')
        }

        chapters.forEachIndexed { index, chapter ->
            if (deleteAt != null && index == deleteAt) return@forEachIndexed
            val raw = text.substring(
                chapter.titleStartOffset.coerceIn(0, text.length),
                chapter.contentEndOffset.coerceIn(0, text.length)
            )
            var rendered = raw.trim('\n', '\r')
            val transformed = when {
                newTitle != null && insertAfter != null && index > insertAfter ->
                    withTitle(rendered, renumberTitle(chapter.title, 1))
                deleteAt != null && index > deleteAt ->
                    withTitle(rendered, renumberTitle(chapter.title, -1))
                else -> rendered
            }
            emit(transformed)
            if (insertAfter != null && index == insertAfter) {
                ensureNewline()
                sb.append(startMark).append('\n')
                sb.append(newTitle ?: "第${index + 2}章")
                sb.append('\n').append(endMark).append('\n')
            }
        }

        val suffixStart = chapters.last().blockEndExclusive.coerceIn(0, text.length)
        if (suffixStart < text.length) {
            ensureNewline()
            sb.append(text, suffixStart, text.length)
        }
        return sb.toString()
    }

    private fun withTitle(raw: String, title: String): String {
        val newline = raw.indexOf('\n')
        return if (newline < 0) title else title + raw.substring(newline)
    }

    private fun chineseToInt(source: String): Int? {
        if (source.isEmpty()) return null
        var total = 0
        var section = 0
        var number = 0
        fun digit(c: Char): Int? = when (c) {
            '零', '〇' -> 0
            '一' -> 1
            '二', '两' -> 2
            '三' -> 3
            '四' -> 4
            '五' -> 5
            '六' -> 6
            '七' -> 7
            '八' -> 8
            '九' -> 9
            else -> null
        }
        for (c in source) {
            when (c) {
                '万' -> {
                    section += number
                    total += section * 10000
                    section = 0
                    number = 0
                }
                '千' -> {
                    section += (number.takeIf { it != 0 } ?: 1) * 1000
                    number = 0
                }
                '百' -> {
                    section += (number.takeIf { it != 0 } ?: 1) * 100
                    number = 0
                }
                '十' -> {
                    section += (number.takeIf { it != 0 } ?: 1) * 10
                    number = 0
                }
                else -> {
                    val d = digit(c) ?: return null
                    number = d
                }
            }
        }
        return total + section + number
    }

    private fun intToChinese(value: Int): String {
        if (value <= 0) return "一"
        val digits = "零一二三四五六七八九"
        fun under10000(v: Int): String {
            val units = listOf(1000 to "千", 100 to "百", 10 to "十", 1 to "")
            var n = v
            val sb = StringBuilder()
            var zeroPending = false
            for ((unit, label) in units) {
                val digit = n / unit
                n %= unit
                if (digit == 0) {
                    if (sb.isNotEmpty() && n > 0) zeroPending = true
                    continue
                }
                if (zeroPending) {
                    sb.append('零')
                    zeroPending = false
                }
                if (!(unit == 10 && digit == 1 && sb.isEmpty())) {
                    sb.append(digits[digit])
                }
                sb.append(label)
            }
            return sb.toString().ifEmpty { "零" }
        }
        return if (value < 10000) {
            under10000(value)
        } else {
            val high = value / 10000
            val low = value % 10000
            val head = under10000(high) + "万"
            if (low == 0) head else head + "零" + under10000(low)
        }
    }
}
