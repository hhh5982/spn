package com.spn.novelmanager.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.spn.novelmanager.domain.MainTxtResolver
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/** SAF / 本地文件访问的薄封装，所有方法均可能在主线程之外执行。 */
object SafStorage {

    const val IMG_DIR = "img"
    const val REF_DIR = "引用"
    const val BACKUP_DIR = "备份"

    fun persistPermission(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    fun documentFromTree(context: Context, uriString: String?): DocumentFile? {
        if (uriString.isNullOrBlank()) return null
        return runCatching {
            val folder = DocumentFile.fromTreeUri(context, Uri.parse(uriString))
            if (folder != null && folder.exists()) folder else null
        }.getOrNull()
    }

    fun ensureDirectory(parent: DocumentFile, name: String): DocumentFile? {
        val existing = parent.findFile(name)
        if (existing != null && existing.isDirectory) return existing
        return runCatching { parent.createDirectory(name) }.getOrNull()
    }

    fun findFile(parent: DocumentFile?, name: String): DocumentFile? =
        parent?.findFile(name)?.takeIf { it.isFile }

    fun findDirectory(parent: DocumentFile?, name: String): DocumentFile? =
        parent?.findFile(name)?.takeIf { it.isDirectory }

    sealed interface MainTxtResolution {
        data class Found(val file: DocumentFile) : MainTxtResolution
        data class Multiple(val files: List<DocumentFile>) : MainTxtResolution
        data object None : MainTxtResolution
    }

    /** 列出书籍文件夹第一层的 .txt 文件（忽略大小写）。 */
    fun txtFiles(folder: DocumentFile?): List<DocumentFile> =
        runCatching {
            folder?.listFiles()?.filter { it.isFile && MainTxtResolver.isTxt(it.name.orEmpty()) }
                .orEmpty()
                .sortedBy { it.name?.trim()?.lowercase() ?: "" }
        }.getOrDefault(emptyList())

    /** 按「同名优先，其次唯一 txt，多文件则需用户选择」的规则解析主文稿（仅旧文件夹数据兼容用）。 */
    fun resolveMainTxt(folder: DocumentFile?, folderName: String? = null): MainTxtResolution {
        if (folder == null) return MainTxtResolution.None
        val files = txtFiles(folder)
        if (files.isEmpty()) return MainTxtResolution.None
        val pick = MainTxtResolver.pick(folderName ?: folder.name, files.map { it.name.orEmpty() })
        return when (pick) {
            is MainTxtResolver.Pick.Found -> {
                val file = files.firstOrNull { it.name == pick.fileName }
                    ?: return MainTxtResolution.None
                MainTxtResolution.Found(file)
            }
            is MainTxtResolver.Pick.Multiple ->
                MainTxtResolution.Multiple(files.filter { it.name in pick.fileNames })
            MainTxtResolver.Pick.None -> MainTxtResolution.None
        }
    }

    fun createFileExact(parent: DocumentFile, name: String, mimeType: String): DocumentFile? {
        val created = runCatching { parent.createFile(mimeType, name) }.getOrNull() ?: return null
        if (created.name == name) return created
        val renamed = runCatching { created.renameTo(name) }.getOrDefault(false)
        return if (renamed) (parent.findFile(name) ?: created) else created
    }

    fun readText(context: Context, file: DocumentFile): String {
        val input = context.contentResolver.openInputStream(file.uri)
            ?: throw IOException("无法打开文件：${file.name}")
        return InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
            val sb = StringBuilder()
            val buffer = CharArray(8192)
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                sb.append(buffer, 0, read)
            }
            sb.toString()
        }
    }

    fun writeText(context: Context, file: DocumentFile, text: String): Boolean {
        val modes = listOf("rwt", "wt", "w")
        for (mode in modes) {
            try {
                val stream = context.contentResolver.openOutputStream(file.uri, mode) ?: continue
                OutputStreamWriter(stream, StandardCharsets.UTF_8).use { writer -> writer.write(text) }
                return true
            } catch (_: Exception) {
                // 尝试下一个写入模式
            }
        }
        return false
    }

    fun copyUri(context: Context, source: Uri, target: DocumentFile): Boolean {
        return try {
            val input = context.contentResolver.openInputStream(source)
                ?: throw IOException("无法读取所选图片")
            input.use { src ->
                val output = context.contentResolver.openOutputStream(target.uri, "w")
                    ?: throw IOException("无法写入目标文件")
                output.use { dst -> src.copyTo(dst, 8192) }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()

    fun extensionFrom(context: Context, uri: Uri): String {
        val name = displayName(context, uri)
        val ext = name?.substringAfterLast('.', "")?.lowercase()
        if (!ext.isNullOrBlank() && ext.length <= 5) return ext
        return when (context.contentResolver.getType(uri)?.lowercase()) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/jpeg", "image/jpg" -> "jpg"
            else -> "jpg"
        }
    }

    fun isSupportedImage(context: Context, uri: Uri): Boolean {
        val mime = context.contentResolver.getType(uri)?.lowercase()
        return when (mime) {
            "image/png", "image/jpeg", "image/jpg", "image/webp" -> true
            null -> extensionFrom(context, uri) in setOf("png", "jpg", "jpeg", "webp")
            else -> false
        }
    }

    fun mimeForExtension(ext: String): String = when (ext.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    /** 「所有文件访问」权限：单文件模式需要在 txt 所在目录创建 img/引用/备份。 */
    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

    /** 从所选 txt 的文档 URI 推导书目录（其父目录）。无法定位时返回 null。 */
    fun folderForTxtUri(context: Context, txtUri: String?): DocumentFile? {
        val file = documentUriToFile(context, txtUri) ?: return null
        val parent = file.parentFile ?: return null
        return DocumentFile.fromFile(parent)
    }

    /** 所选 txt 对应的本地文件；云盘等无法映射到本地路径时返回 null。 */
    fun localFileForTxtUri(context: Context, txtUri: String?): File? =
        documentUriToFile(context, txtUri)

    /**
     * 文档 URI → 本地 File：优先按 ExternalStorageProvider 的 docId 解析，
     * 其次查询 MediaStore 的 DATA 列（从「最近使用 / 下载」入口选择的文件走这里）。
     */
    private fun documentUriToFile(context: Context, uriString: String?): File? {
        if (uriString.isNullOrBlank()) return null
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") return uri.path?.let { File(it) }

        runCatching {
            val docId = DocumentsContract.getDocumentId(uri)
            val separator = docId.indexOf(':')
            if (separator > 0) {
                val volume = docId.substring(0, separator)
                val relative = docId.substring(separator + 1)
                when {
                    volume == "raw" -> return File(relative)
                    volume == "primary" || volume == "home" ->
                        return File(Environment.getExternalStorageDirectory(), relative)
                    VOLUME_ID_REGEX.matches(volume) -> return File("/storage/$volume", relative)
                }
            }
        }

        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val path = cursor.getString(0)
                    if (!path.isNullOrBlank()) return File(path)
                }
            }
        }
        return null
    }

    private val VOLUME_ID_REGEX = Regex("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$")
}
