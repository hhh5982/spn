package com.spn.novelmanager.domain

/**
 * 章节解析：一对起始/结束标记之间就是一个章节。
 * 遇到起始标记后，**第一个结束标记**即收章；块内再次出现的起始标记、
 * 以及后续孤立的结束标记都按普通文字处理（见嵌套示例）。
 *
 * 块内第一个非空行是标题；若块内还出现多个「第N章」式标题行，则继续按标题行拆分。
 * 任何异常均降级为可读文本，不抛异常。
 */
data class Chapter(
    val index: Int,
    val title: String,
    val content: String,
    /** 跳转用起点：有标记时是起始标记位置，无标记单章为 0。 */
    val startOffset: Int,
    val endOffset: Int,
    /** 复制当前章节时使用的完整文本（含标题行）。 */
    val copyText: String,
    /** 标题行在主文稿中的字符范围（不含换行）。 */
    val titleStartOffset: Int = startOffset,
    val titleEndOffset: Int = startOffset + title.length,
    /** 可编辑内容范围：从标题行起点到正文末尾（不含结束标记）。 */
    val contentStartOffset: Int = titleStartOffset,
    val contentEndOffset: Int = endOffset,
    /** 正文起点（标题行之后）。 */
    val bodyStartOffset: Int = titleEndOffset,
    /** 该章节是否由一对真实标记包围。 */
    val hasBlockMarkers: Boolean = false,
    val blockStartOffset: Int = startOffset,
    val blockEndExclusive: Int = endOffset
)

internal data class TextLine(
    val start: Int,
    val contentEnd: Int,
    val endExclusive: Int,
    val text: String
)

internal fun splitLines(text: String): List<TextLine> {
    if (text.isEmpty()) return listOf(TextLine(0, 0, 0, ""))
    val result = mutableListOf<TextLine>()
    var index = 0
    while (index <= text.length) {
        val newline = text.indexOf('\n', index)
        if (newline == -1) {
            result.add(TextLine(index, text.length, text.length, text.substring(index)))
            break
        }
        result.add(TextLine(index, newline, newline + 1, text.substring(index, newline)))
        index = newline + 1
        if (index >= text.length) break
    }
    return result
}

/** 阅读态渲染文本：结构标记不显示，孤立标记按普通文字保留。 */
data class DisplayDocument(
    val text: String,
    /** 每个章节在显示文本中的起点（与 chapters 一一对应）。 */
    val chapterStarts: List<Int>
)

object ChapterParser {

    /** 块内二次拆分使用的「第N章 / 第N节 / 第N回 / 第N卷」标题行。 */
    private val embeddedTitleRegex =
        Regex("^第\\s*[0-9０-９一二三四五六七八九十百千万零〇两]+\\s*[章节回卷]")

    fun parse(text: String, startMark: String, endMark: String): List<Chapter> {
        val start = startMark.trim()
        val end = endMark.trim()
        if (start.isEmpty() || end.isEmpty() || start == end) return singleChapter(text)
        return runCatching { parseInternal(text, start, end) }
            .getOrElse { singleChapter(text) }
    }

    fun singleChapter(text: String): List<Chapter> = listOf(
        Chapter(
            index = 0,
            title = "（无标题）",
            content = text,
            startOffset = 0,
            endOffset = text.length,
            copyText = text,
            titleStartOffset = 0,
            titleEndOffset = 0,
            contentStartOffset = 0,
            contentEndOffset = text.length,
            bodyStartOffset = 0,
            hasBlockMarkers = false,
            blockStartOffset = 0,
            blockEndExclusive = text.length
        )
    )

    fun chapterAt(chapters: List<Chapter>, offset: Int): Chapter? {
        if (chapters.isEmpty()) return null
        return chapters.lastOrNull { offset >= it.startOffset }
    }

    fun chapterAtDisplay(chapters: List<Chapter>, display: DisplayDocument, displayOffset: Int): Chapter? {
        if (chapters.isEmpty()) return null
        var found: Chapter? = null
        chapters.forEachIndexed { index, chapter ->
            val start = display.chapterStarts.getOrElse(index) { 0 }
            if (displayOffset >= start) found = chapter
        }
        return found
    }

    private fun parseInternal(text: String, start: String, end: String): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        var searchFrom = 0
        while (searchFrom < text.length) {
            val blockStart = text.indexOf(start, searchFrom)
            if (blockStart == -1) break
            val contentStart = blockStart + start.length
            // 遇到第一个结束标记即收章；找不到结束标记说明这个起始标记是孤立符号。
            val endIndex = text.indexOf(end, contentStart)
            if (endIndex == -1) break
            val blockEndExclusive = endIndex + end.length
            appendBlockChapters(
                out = chapters,
                text = text,
                contentStart = contentStart,
                contentEnd = endIndex,
                blockStart = blockStart,
                blockEndExclusive = blockEndExclusive
            )
            searchFrom = blockEndExclusive
        }
        return if (chapters.isEmpty()) singleChapter(text) else chapters
    }

    /** 一个标记块至少产出一个章节；块内额外的「第N章」标题行会继续拆分。 */
    private fun appendBlockChapters(
        out: MutableList<Chapter>,
        text: String,
        contentStart: Int,
        contentEnd: Int,
        blockStart: Int,
        blockEndExclusive: Int
    ) {
        val rawContent = text.substring(contentStart, contentEnd)
        val lines = splitLines(rawContent)
        val firstIndex = lines.indexOfFirst { it.text.isNotBlank() }
        if (firstIndex == -1) return

        val boundaries = mutableListOf(firstIndex)
        for (i in firstIndex + 1 until lines.size) {
            val trimmed = lines[i].text.trim()
            if (trimmed.isNotEmpty() && embeddedTitleRegex.containsMatchIn(trimmed)) {
                boundaries.add(i)
            }
        }
        boundaries.add(lines.size)

        for (segment in 0 until boundaries.size - 1) {
            val titleLineIndex = boundaries[segment]
            val nextLineIndex = boundaries[segment + 1]
            val titleLine = lines[titleLineIndex]
            val title = titleLine.text.trim()
            val titleStart = contentStart + titleLine.start
            val titleEnd = contentStart + titleLine.contentEnd
            val bodyStart = contentStart + titleLine.endExclusive
            val bodyEnd = if (nextLineIndex >= lines.size) contentEnd else contentStart + lines[nextLineIndex].start
            val body = if (bodyStart <= bodyEnd) {
                text.substring(bodyStart, bodyEnd).trim('\n', '\r')
            } else ""
            if (title.isBlank() && body.isBlank()) continue
            val firstSegment = segment == 0
            out.add(
                Chapter(
                    index = out.size,
                    title = title.ifBlank { "（未命名章节）" },
                    content = body,
                    startOffset = if (firstSegment) blockStart else titleStart,
                    endOffset = if (segment == boundaries.size - 2) blockEndExclusive else {
                        contentStart + lines[nextLineIndex].start
                    },
                    copyText = text.substring(titleStart, bodyEnd).trim('\n', '\r').ifBlank { title },
                    titleStartOffset = titleStart,
                    titleEndOffset = titleEnd,
                    contentStartOffset = titleStart,
                    contentEndOffset = bodyEnd,
                    bodyStartOffset = bodyStart,
                    hasBlockMarkers = true,
                    blockStartOffset = blockStart,
                    blockEndExclusive = blockEndExclusive
                )
            )
        }
    }

    /**
     * 构造阅读态显示文本：结构标记被移除，章节之间以空行分隔。
     * 未归入任何章节的文字（首个标记之前、最后一个标记之后、孤立标记）原样保留。
     */
    fun buildDisplay(text: String, chapters: List<Chapter>): DisplayDocument {
        if (chapters.isEmpty()) return DisplayDocument(text, emptyList())
        if (chapters.size == 1 && !chapters[0].hasBlockMarkers) {
            return DisplayDocument(text, listOf(0))
        }
        val sb = StringBuilder(text.length)
        val starts = mutableListOf<Int>()
        var cursor = 0
        fun appendSegment(raw: String) {
            val trimmed = raw.trim('\n', '\r')
            if (trimmed.isBlank()) return
            if (sb.isNotEmpty() && !sb.endsWith("\n\n")) {
                sb.append("\n\n")
            }
            sb.append(trimmed)
        }
        chapters.forEach { chapter ->
            val displayStart = if (chapter.startOffset == chapter.blockStartOffset) {
                chapter.blockStartOffset
            } else {
                chapter.titleStartOffset
            }
            if (displayStart > cursor) {
                appendSegment(text.substring(cursor, displayStart))
            }
            if (sb.isNotEmpty() && !sb.endsWith("\n\n")) sb.append("\n\n")
            starts.add(sb.length)
            appendSegment(text.substring(chapter.titleStartOffset, chapter.contentEndOffset))
            cursor = if (chapter.endOffset == chapter.blockEndExclusive) {
                chapter.blockEndExclusive
            } else {
                chapter.contentEndOffset
            }
        }
        if (cursor < text.length) appendSegment(text.substring(cursor))
        return DisplayDocument(sb.toString(), starts)
    }
}
