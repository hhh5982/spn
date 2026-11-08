package com.spn.novelmanager.domain

/**
 * 正文轻量标记解析：
 * - 引用：@小说名@
 * - 插图：<img src="img/001.png" width="600">
 *   兼容旧标记：![文件名|w:宽度]，缺失宽度时默认 240
 * 格式不完整时不会生成特殊节点，按普通文本原样渲染。
 */
sealed class InlineNode {
    data class Text(val text: String) : InlineNode()
    data class Quote(val name: String) : InlineNode()
    data class Image(
        /** 相对当前小说文件夹的路径，例如 img/001.png。 */
        val path: String,
        /** img 目录内的真实文件名，用于加载图片。 */
        val fileName: String,
        /** 渲染宽度（px）。 */
        val width: Int
    ) : InlineNode()
    data class BrokenMark(val raw: String) : InlineNode()
}

data class InlineToken(val start: Int, val end: Int, val node: InlineNode)

object InlineParser {

    /** 新格式默认显示宽度。 */
    const val DEFAULT_IMAGE_WIDTH = 600

    /** 旧格式 `![文件名|w:宽度]` 的默认显示宽度，保持历史外观。 */
    const val LEGACY_IMAGE_WIDTH = 240

    private val quoteRegex = Regex("@([^@\\n]{1,40})@")

    private val imgTagRegex = Regex(
        "<img\\s+src\\s*=\\s*[\"']([^\"'\\n]+)[\"']\\s*(?:width\\s*=\\s*[\"']?(\\d+)[\"']?)?\\s*/?>",
        RegexOption.IGNORE_CASE
    )

    private val legacyImageRegex = Regex("!\\[([^\\]\\n|]+)(?:\\|w:(\\d+))?\\]")

    fun imageTokens(text: String): List<InlineToken> {
        val tokens = mutableListOf<InlineToken>()
        imgTagRegex.findAll(text).forEach { match ->
            val path = match.groupValues[1].trim()
            val fileName = path.substringAfterLast('/').trim()
            if (path.isEmpty() || fileName.isEmpty()) return@forEach
            val width = match.groupValues.getOrNull(2)?.toIntOrNull()
                ?.takeIf { it > 0 } ?: DEFAULT_IMAGE_WIDTH
            tokens.add(InlineToken(match.range.first, match.range.last + 1, InlineNode.Image(path, fileName, width)))
        }
        legacyImageRegex.findAll(text).forEach { match ->
            val fileName = match.groupValues[1].trim()
            if (fileName.isEmpty()) return@forEach
            val width = match.groupValues.getOrNull(2)?.toIntOrNull()
                ?.takeIf { it > 0 } ?: LEGACY_IMAGE_WIDTH
            // 旧标记只记录 img 目录内的文件名，路径按 img/文件名 处理。
            tokens.add(
                InlineToken(
                    match.range.first,
                    match.range.last + 1,
                    InlineNode.Image("img/$fileName", fileName, width)
                )
            )
        }
        return tokens.sortedBy { it.start }
    }

    fun quoteTokens(text: String, excludeRanges: List<IntRange> = emptyList()): List<InlineToken> =
        quoteRegex.findAll(text).mapNotNull { match ->
            val name = match.groupValues[1].trim()
            if (name.isEmpty()) return@mapNotNull null
            val range = match.range.first until match.range.last + 1
            if (excludeRanges.any { r -> range.first <= r.last && range.last >= r.first }) return@mapNotNull null
            InlineToken(range.first, range.last + 1, InlineNode.Quote(name))
        }.toList()

    fun tokens(text: String): List<InlineToken> {
        val images = imageTokens(text)
        val quotes = quoteTokens(text, images.map { it.start until it.end })
        return (images + quotes).sortedBy { it.start }
    }

    fun quoteAt(text: String, offset: Int): String? {
        val images = imageTokens(text)
        val quotes = quoteTokens(text, images.map { it.start until it.end })
        // 命中判定包含结束位置，避免点在引用文字末尾的像素时落空。
        return quotes.firstOrNull { offset >= it.start && offset <= it.end }
            ?.let { (it.node as InlineNode.Quote).name }
    }

    /** 章节标记出现的所有区间，用于阅读态弱化显示标记本身。 */
    fun markTokens(text: String, startMark: String, endMark: String): List<InlineToken> {
        val marks = listOf(startMark.trim(), endMark.trim()).filter { it.isNotEmpty() }.distinct()
        val result = mutableListOf<InlineToken>()
        for (mark in marks) {
            var index = text.indexOf(mark)
            while (index != -1) {
                result.add(InlineToken(index, index + mark.length, InlineNode.Text(mark)))
                index = text.indexOf(mark, index + mark.length)
            }
        }
        return result.sortedBy { it.start }
    }

    fun paragraphAt(text: String, offset: Int): String {
        if (text.isEmpty()) return ""
        val safe = offset.coerceIn(0, text.length)
        var start = if (safe == 0) 0 else text.lastIndexOf('\n', safe - 1) + 1
        if (start > text.length) start = text.length
        var end = text.indexOf('\n', safe)
        if (end == -1) end = text.length
        return text.substring(start, end).trim()
    }
}
