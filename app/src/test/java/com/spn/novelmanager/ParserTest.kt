package com.spn.novelmanager

import com.spn.novelmanager.data.UndoStackCodec
import com.spn.novelmanager.domain.AutoChapter
import com.spn.novelmanager.domain.ChapterOps
import com.spn.novelmanager.domain.ChapterParser
import com.spn.novelmanager.domain.InlineNode
import com.spn.novelmanager.domain.InlineParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParserTest {

    @Test
    fun `成对标记切分章节并保留偏移`() {
        val text = """
            /*
            第一章 启程
            正文一
            正文二
            */
            /*
            第二章 出发
            正文二
            */
        """.trimIndent() + "\n"

        val chapters = ChapterParser.parse(text, "/*", "*/")
        assertEquals(2, chapters.size)
        assertEquals("第一章 启程", chapters[0].title)
        assertEquals("正文一\n正文二", chapters[0].content)
        assertEquals("第二章 出发", chapters[1].title)
        assertEquals("正文二", chapters[1].content)
        assertTrue(text.substring(chapters[1].startOffset).startsWith("/*"))
        assertTrue(chapters[1].copyText.contains("第二章 出发"))
    }

    @Test
    fun `不成对的起始标记按普通文字保留`() {
        val text = "前言\n/*\n第一章\nabc\n/*\n第二章\ndef\n"
        val chapters = ChapterParser.parse(text, "/*", "*/")
        assertEquals(1, chapters.size)
        assertEquals(text, chapters[0].copyText)
        assertEquals(text, ChapterParser.buildDisplay(text, chapters).text)
    }

    @Test
    fun `嵌套标记只认第一个结束标记`() {
        val text = "/*内容内容内容　　/*内容内容*/内容内容*/"
        val chapters = ChapterParser.parse(text, "/*", "*/")
        assertEquals(1, chapters.size)
        assertEquals("内容内容内容　　/*内容内容", chapters[0].title)
        val display = ChapterParser.buildDisplay(text, chapters)
        assertEquals("内容内容内容　　/*内容内容\n\n内容内容*/", display.text)
    }

    @Test
    fun `孤立结束标记按普通文字显示`() {
        val text = "正文\n*/\n更多正文"
        val chapters = ChapterParser.parse(text, "/*", "*/")
        val display = ChapterParser.buildDisplay(text, chapters)
        assertEquals(text, display.text)
    }

    @Test
    fun `右侧插入章节后编号顺延`() {
        val text = "/*\n第50章 当前\n正文50\n*/\n/*\n第51章 后一\n正文51\n*/\n/*\n第52章 后二\n正文52\n*/\n"
        val chapters = ChapterParser.parse(text, "/*", "*/")
        val inserted = ChapterOps.insertAfter(text, chapters, 0, "/*", "*/", "第51章")
        val parsed = ChapterParser.parse(inserted, "/*", "*/")
        assertEquals(4, parsed.size)
        assertEquals(
            listOf("第50章 当前", "第51章", "第52章 后一", "第53章 后二"),
            parsed.map { it.title }
        )
        assertTrue(parsed.all { it.hasBlockMarkers })
    }

    @Test
    fun `第50章之后新增章节沿用当前编号递增`() {
        val text = "/*\n第50章 当前\n正文50\n*/\n/*\n第51章 后一\n正文51\n*/\n"
        val chapters = ChapterParser.parse(text, "/*", "*/")
        assertEquals("第51章", ChapterOps.nextChapterTitle(chapters[0].title, 2))
        val inserted = ChapterOps.insertAfter(
            text,
            chapters,
            0,
            "/*",
            "*/",
            ChapterOps.nextChapterTitle(chapters[0].title, 2)
        )
        val parsed = ChapterParser.parse(inserted, "/*", "*/")
        assertEquals(listOf("第50章 当前", "第51章", "第52章 后一"), parsed.map { it.title })
    }

    @Test
    fun `删除章节后编号顺延且不留孤儿标记`() {
        val text = "/*\n第50章 当前\n正文50\n*/\n/*\n第51章 后一\n正文51\n*/\n/*\n第52章 后二\n正文52\n*/\n"
        val chapters = ChapterParser.parse(text, "/*", "*/")
        val deleted = ChapterOps.deleteChapter(text, chapters, 0, "/*", "*/")
        assertEquals(true, deleted != null)
        val parsed = ChapterParser.parse(deleted!!, "/*", "*/")
        assertEquals(2, parsed.size)
        assertEquals(listOf("第50章 后一", "第51章 后二"), parsed.map { it.title })
        val reopened = ChapterParser.buildDisplay(deleted, parsed)
        assertTrue(reopened.text.contains("正文50").not())
    }

    @Test
    fun `撤销栈可持久化并裁剪到设置步数`() {
        val stack = ArrayDeque<String>()
        UndoStackCodec.push(stack, "第一版", 3)
        UndoStackCodec.push(stack, "第二版", 3)
        UndoStackCodec.push(stack, "第三版", 3)
        UndoStackCodec.push(stack, "第四版", 3)
        assertEquals(listOf("第二版", "第三版", "第四版"), stack.toList())
        val encoded = UndoStackCodec.encode(stack)
        val decoded = UndoStackCodec.decode(encoded)
        assertEquals(stack.toList(), decoded.toList())
    }

    @Test
    fun `无标记时视为单章`() {
        val text = "只有正文的内容。\n第二行。"
        val chapters = ChapterParser.parse(text, "/*", "*/")
        assertEquals(1, chapters.size)
        assertEquals("（无标题）", chapters[0].title)
        assertEquals(text, chapters[0].copyText)
    }

    @Test
    fun `孤立结束标记被忽略`() {
        val text = "正文\n*/\n更多正文"
        val chapters = ChapterParser.parse(text, "/*", "*/")
        assertEquals(1, chapters.size)
    }

    @Test
    fun `自动分章仅插入标记不改原文`() {
        val text = "第1章 开局\n故事正文...\n第2章 出发\n故事正文2...\n"
        val titles = AutoChapter.findTitleLines(text, "第", "章", 1, null)
        assertEquals(2, titles.size)
        val result = AutoChapter.insertMarks(text, titles.map { it.startOffset }, "/*", "*/")
        assertEquals(
            "/*\n第1章 开局\n故事正文...\n*/\n/*\n第2章 出发\n故事正文2...\n*/\n",
            result
        )
        val stripped = result.lines()
            .filterNot { it.trim() == "/*" || it.trim() == "*/" }
            .joinToString("\n")
            .trimEnd('\n')
        assertEquals(text.trimEnd('\n'), stripped)
    }

    @Test
    fun `自动分章长度约束生效`() {
        val text = "第1章 开局\n第123456章 太长\n第2章 正常\n"
        val titles = AutoChapter.findTitleLines(text, "第", "章", 1, 4)
        assertEquals(2, titles.size)
        assertTrue(titles.none { it.title.contains("123456") })
    }

    @Test
    fun `块首行即章节名并支持内联标记`() {
        val text = "/*第一章 启程\n正文一\n*/\n/*第二章 出发\n正文二*/\n"
        val chapters = ChapterParser.parse(text, "/*", "*/")
        assertEquals(2, chapters.size)
        assertEquals("第一章 启程", chapters[0].title)
        assertEquals("正文一", chapters[0].content)
        assertEquals("第二章 出发", chapters[1].title)
        assertEquals("正文二", chapters[1].content)
        assertTrue(text.substring(chapters[0].startOffset).startsWith("/*"))
    }

    @Test
    fun `块内多个第N章标题继续拆分`() {
        val text = "/*\n第一章 甲\n正文甲\n第二章 乙\n正文乙\n*/\n"
        val chapters = ChapterParser.parse(text, "/*", "*/")
        assertEquals(2, chapters.size)
        assertEquals("第一章 甲", chapters[0].title)
        assertEquals("正文甲", chapters[0].content)
        assertEquals("第二章 乙", chapters[1].title)
        assertEquals("正文乙", chapters[1].content)
    }

    @Test
    fun `首个标记之前的内容不属于任何章节`() {
        val text = "前言\n/*第一章 甲\n正文\n*/"
        val chapters = ChapterParser.parse(text, "/*", "*/")
        assertEquals(1, chapters.size)
        assertNull(ChapterParser.chapterAt(chapters, 0))
    }

    @Test
    fun `引用与插图标记解析`() {
        val text = "看 @番外@ 和 ![img_1.png|w:240] 还有 ![bad.png] 结束"
        val tokens = InlineParser.tokens(text)
        assertEquals(3, tokens.size)

        val quotes = tokens.filter { it.node is InlineNode.Quote }
        assertEquals("番外", (quotes.first().node as InlineNode.Quote).name)

        val images = tokens.filter { it.node is InlineNode.Image }
        assertEquals(240, (images[0].node as InlineNode.Image).width)
        assertEquals(240, (images[1].node as InlineNode.Image).width)
    }

    @Test
    fun `新图片标记解析相对路径与默认宽度`() {
        val text = "前 <img src=\"img/001.png\" width=\"600\"> 中 <img src=\"img/002.png\"> 后"
        val images = InlineParser.imageTokens(text)
        assertEquals(2, images.size)
        val first = images[0].node as InlineNode.Image
        assertEquals("img/001.png", first.path)
        assertEquals("001.png", first.fileName)
        assertEquals(600, first.width)
        assertEquals(600, (images[1].node as InlineNode.Image).width)
    }

    @Test
    fun `段落与引用定位`() {
        val text = "第一段\n第二段含@引用@内容\n第三段"
        assertEquals("第二段含@引用@内容", InlineParser.paragraphAt(text, 8))
        assertEquals("引用", InlineParser.quoteAt(text, 9))
        assertNull(InlineParser.quoteAt(text, 3))
    }
}
