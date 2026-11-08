package com.spn.novelmanager.model

import androidx.documentfile.provider.DocumentFile

enum class ReadingTheme { LIGHT, NIGHT, PARCHMENT, EYE_CARE, BLUE }

enum class OrientationMode { PORTRAIT, LANDSCAPE }

enum class FlipMode { SCROLL, PAGE }

data class AppSettings(
    val fontFamily: String = "sans-serif",
    val fontSizeSp: Float = 17f,
    val textColor: Int? = null,
    val bold: Boolean = false,
    val underline: Boolean = false,
    val lineSpacing: Float = 1.35f,
    val theme: ReadingTheme = ReadingTheme.LIGHT,
    val orientation: OrientationMode = OrientationMode.PORTRAIT,
    val flipMode: FlipMode = FlipMode.SCROLL,
    val undoSteps: Int = 20,
    val chapterStartMark: String = "/*",
    val chapterEndMark: String = "*/",
    val autoChapterPrefix: String = "第",
    val autoChapterSuffix: String = "章",
    val autoChapterInnerMinLen: Int = 1,
    val autoChapterInnerMaxLen: Int? = null,
    val autoChapterPreview: Boolean = true,
    val autoBackupOnOpen: Boolean = true,
    val maxBackupCount: Int = 5,
    val doubleTapCopyParagraph: Boolean = true,
    val longPressQuoteJump: Boolean = true,
    val autosaveDebounceMs: Long = 1500L
)

data class BookRecord(
    /** 旧文件夹模式存 SAF 目录树 URI；单文件模式存 txt 所在目录的 file:// URI。 */
    val folderUri: String,
    val displayName: String,
    val sortIndex: Int,
    /** 主文稿实际文件名（随书持久化）。 */
    val txtFileName: String? = null,
    /** 单文件模式：所选 txt 的文档 URI。为空表示旧文件夹模式。 */
    val txtUri: String? = null
) {
    val key: String get() = txtUri ?: folderUri
}

data class ShelfBook(
    val record: BookRecord,
    val folder: DocumentFile?,
    val folderName: String,
    val txtFile: DocumentFile?,
    val txtFileName: String?
) {
    val key: String get() = record.key
    val uriString: String get() = key
    val displayName: String get() = record.displayName
    val hasMainTxt: Boolean get() = txtFile != null
}

/** 已解析到 DocumentFile 的一本书（书架书籍或引用小说）。 */
class BookRef(
    val folder: DocumentFile,
    var displayName: String,
    val txtFile: DocumentFile,
    val isReference: Boolean
) {
    val folderName: String get() = folder.name ?: "未命名"
}

sealed class ImportResult {
    data class Success(val displayName: String) : ImportResult()
    data object Duplicate : ImportResult()
    data class Failure(val message: String) : ImportResult()
}
