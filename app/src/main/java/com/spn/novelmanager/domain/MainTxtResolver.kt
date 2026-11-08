package com.spn.novelmanager.domain

/**
 * 主文稿文件名选择规则（纯逻辑，便于单测）：
 * 1. 优先匹配与书籍文件夹同名的 .txt（忽略大小写与首尾空格）；
 * 2. 没有同名文件时，若只有一个 .txt 直接使用；
 * 3. 多个 .txt 且无同名匹配时交由调用方让用户选择。
 */
object MainTxtResolver {

    sealed interface Pick {
        data class Found(val fileName: String) : Pick
        data class Multiple(val fileNames: List<String>) : Pick
        data object None : Pick
    }

    fun pick(folderName: String?, fileNames: List<String>): Pick {
        val txts = fileNames.filter { isTxt(it) }
        if (txts.isEmpty()) return Pick.None
        val target = folderName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val matched = target?.let { name ->
            txts.firstOrNull { baseName(it) == name }
        }
        if (matched != null) return Pick.Found(matched)
        if (txts.size == 1) return Pick.Found(txts.first())
        return Pick.Multiple(txts.sortedBy { it.trim().lowercase() })
    }

    fun isTxt(fileName: String): Boolean =
        fileName.trim().lowercase().endsWith(".txt")

    fun baseName(fileName: String): String =
        fileName.trim().substringBeforeLast('.', fileName.trim()).trim().lowercase()
}
