package com.spn.novelmanager

import com.spn.novelmanager.domain.MainTxtResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MainTxtResolverTest {

    @Test
    fun `优先匹配与文件夹同名的 txt`() {
        val pick = MainTxtResolver.pick("某某小说", listOf("序章.txt", "某某小说.txt", "设定.txt"))
        assertEquals(MainTxtResolver.Pick.Found("某某小说.txt"), pick)
    }

    @Test
    fun `同名匹配忽略大小写与首尾空格`() {
        val pick = MainTxtResolver.pick("My Novel", listOf("my novel.TXT", "notes.txt"))
        assertEquals(MainTxtResolver.Pick.Found("my novel.TXT"), pick)
    }

    @Test
    fun `没有同名文件且只有一个 txt 时直接使用`() {
        val pick = MainTxtResolver.pick("某某小说", listOf("正文.txt"))
        assertEquals(MainTxtResolver.Pick.Found("正文.txt"), pick)
    }

    @Test
    fun `多个 txt 且无同名匹配时返回候选列表`() {
        val pick = MainTxtResolver.pick("某某小说", listOf("b.txt", "a.txt", "备注.md"))
        assertTrue(pick is MainTxtResolver.Pick.Multiple)
        assertEquals(listOf("a.txt", "b.txt"), (pick as MainTxtResolver.Pick.Multiple).fileNames)
    }

    @Test
    fun `没有 txt 时返回空`() {
        assertEquals(MainTxtResolver.Pick.None, MainTxtResolver.pick("某某小说", listOf("cover.png", "备注.md")))
    }
}
