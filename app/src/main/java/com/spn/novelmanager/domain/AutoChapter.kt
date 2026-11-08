package com.spn.novelmanager.domain

/**
 * 自动分章：按「前置文字 + 指定长度内容 + 后置文字」整行匹配标题行，仅插入标记，不改动任何原文。
 */
object AutoChapter {

    data class TitleLine(val startOffset: Int, val title: String)

    fun findTitleLines(
        text: String,
        prefix: String,
        suffix: String,
        minInnerLength: Int,
        maxInnerLength: Int?
    ): List<TitleLine> {
        if (text.isEmpty()) return emptyList()
        val result = mutableListOf<TitleLine>()
        for (line in splitLines(text)) {
            val trimmed = line.text.trim()
            if (trimmed.isEmpty()) continue
            if (!trimmed.startsWith(prefix)) continue
            // 后置文字允许后面还有标题备注（示例「第1章 开局」应被识别）
            val suffixIndex = if (suffix.isEmpty()) {
                trimmed.length
            } else {
                trimmed.indexOf(suffix, prefix.length)
            }
            if (suffixIndex < 0) continue
            val inner = trimmed.substring(prefix.length, suffixIndex)
            if (inner.length < minInnerLength) continue
            if (maxInnerLength != null && inner.length > maxInnerLength) continue
            result.add(TitleLine(line.start, trimmed))
        }
        return result
    }

    /**
     * 在每个标题行前插入起始标记，在下一标题前插入上一章的结束标记，文末补齐最后一个结束标记。
     * 每个标记独占一行，原有文字不做任何删改。
     */
    fun insertMarks(text: String, titleOffsets: List<Int>, startMark: String, endMark: String): String {
        val marks = titleOffsets.sorted()
        if (marks.isEmpty()) return text
        val sb = StringBuilder(text.length + marks.size * (startMark.length + endMark.length + 4))
        var cursor = 0
        var markIndex = 0
        var chapterOpen = false

        while (cursor < text.length) {
            if (markIndex < marks.size && marks[markIndex] == cursor) {
                if (chapterOpen) sb.append(endMark).append('\n')
                sb.append(startMark).append('\n')
                chapterOpen = true
                markIndex++
            }
            val newline = text.indexOf('\n', cursor)
            if (newline == -1) {
                sb.append(text, cursor, text.length)
                cursor = text.length
            } else {
                sb.append(text, cursor, newline + 1)
                cursor = newline + 1
            }
        }
        if (chapterOpen) {
            if (sb.isNotEmpty() && sb.sbLastIsNotNewline()) sb.append('\n')
            sb.append(endMark).append('\n')
        }
        return sb.toString()
    }

    private fun StringBuilder.sbLastIsNotNewline(): Boolean = this[this.length - 1] != '\n'
}
