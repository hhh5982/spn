package com.spn.novelmanager.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.spn.novelmanager.model.BookRecord
import com.spn.novelmanager.model.BookRef
import com.spn.novelmanager.model.ImportResult
import com.spn.novelmanager.model.ShelfBook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BookRepository(
    private val context: Context,
    private val settingsRepository: SettingsRepository
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _books = MutableStateFlow<List<ShelfBook>>(emptyList())
    val books: StateFlow<List<ShelfBook>> = _books.asStateFlow()

    data class BackupResult(val success: Boolean, val fileName: String?, val message: String?)

    fun refresh() {
        val records = readRecords()
        val updated = mutableListOf<BookRecord>()
        _books.value = records.map { record ->
            if (record.txtUri != null) {
                // 单文件模式：书目录 = 所选 txt 的父目录。
                val folder = SafStorage.folderForTxtUri(context, record.txtUri)
                val folderName = folder?.name ?: record.folderUri
                val txt = SafStorage.findFile(folder, record.txtFileName.orEmpty())
                updated += record
                ShelfBook(record, folder, folderName, txt, txt?.name)
            } else {
                // 旧文件夹模式：保留原 SAF 目录树与同名/唯一 txt 解析。
                val folder = SafStorage.documentFromTree(context, record.folderUri)
                val folderName = folder?.name ?: record.displayName
                val txt = resolveTxtFor(folder, folderName, record.txtFileName)
                val latest = if (txt?.name != null && txt.name != record.txtFileName) {
                    record.copy(txtFileName = txt.name)
                } else {
                    record
                }
                updated += latest
                ShelfBook(latest, folder, folderName, txt, txt?.name)
            }
        }
        // 旧数据没有记录主文稿文件名时，把本次解析结果回写，避免重复解析。
        if (updated != records) saveRecords(updated)
    }

    /** 兼容旧文件夹模式：优先使用已记录的主文稿文件名，缺失时按同名/唯一 txt 规则重新解析。 */
    private fun resolveTxtFor(
        folder: DocumentFile?,
        folderName: String,
        savedName: String?
    ): DocumentFile? {
        if (folder == null) return null
        if (savedName != null) {
            SafStorage.findFile(folder, savedName)?.let { return it }
        }
        return when (val resolution = SafStorage.resolveMainTxt(folder, folderName)) {
            is SafStorage.MainTxtResolution.Found -> resolution.file
            else -> null
        }
    }

    /** 添加书籍：选择一个 .txt，其所在父目录即这本书的根目录。 */
    fun importTxt(txtUri: Uri): ImportResult {
        return runCatching {
            if (!SafStorage.hasAllFilesAccess()) {
                return ImportResult.Failure("请先授予「所有文件访问」权限（书架首页可授权）")
            }
            val fileName = SafStorage.displayName(context, txtUri)
                ?: return ImportResult.Failure("无法读取所选文件名")
            if (!fileName.lowercase(Locale.US).endsWith(".txt")) {
                return ImportResult.Failure("请选择 .txt 小说文件")
            }
            val file = SafStorage.localFileForTxtUri(context, txtUri.toString())
                ?: return ImportResult.Failure("只能添加本地存储中的 txt 文件")
            val folder = file.parentFile
                ?: return ImportResult.Failure("无法定位 txt 所在目录")
            if (!file.isFile) return ImportResult.Failure("无法读取所选 txt 文件")

            SafStorage.persistPermission(context, txtUri)
            val records = readRecords()
            if (records.any { it.txtUri == txtUri.toString() }) return ImportResult.Duplicate

            val displayName = fileName.substringBeforeLast('.').ifBlank { "未命名书籍" }
            val record = BookRecord(
                folderUri = folder.toURI().toString(),
                displayName = displayName,
                sortIndex = records.size,
                txtFileName = fileName,
                txtUri = txtUri.toString()
            )
            saveRecords(records + record)
            refresh()
            ImportResult.Success(displayName)
        }.getOrElse { ImportResult.Failure(it.message ?: "导入失败") }
    }

    fun isDisplayNameTaken(record: BookRecord, name: String): Boolean =
        _books.value.any {
            it.record.key != record.key && it.displayName.trim() == name.trim()
        }

    fun renameDisplay(record: BookRecord, newName: String): Result<Unit> {
        val name = newName.trim()
        if (name.isEmpty()) return Result.failure(IOException("书籍名称不能为空"))
        if (isDisplayNameTaken(record, name)) return Result.failure(IOException("该名称与其他书籍重复"))
        val records = readRecords().map {
            if (it.key == record.key) it.copy(displayName = name) else it
        }
        saveRecords(records)
        refresh()
        return Result.success(Unit)
    }

    fun setCover(book: ShelfBook, imageUri: Uri): Result<Unit> {
        val folder = book.folder ?: return Result.failure(IOException("书籍目录不可访问"))
        if (!SafStorage.isSupportedImage(context, imageUri)) {
            return Result.failure(IOException("仅支持 png / jpg / webp 图片"))
        }
        return runCatching {
            val img = SafStorage.ensureDirectory(folder, SafStorage.IMG_DIR)
                ?: throw IOException("无法创建 img 目录")
            val ext = SafStorage.extensionFrom(context, imageUri)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val temp = SafStorage.createFileExact(img, "cover_tmp_$stamp.$ext", SafStorage.mimeForExtension(ext))
                ?: throw IOException("无法创建封面文件")
            if (!SafStorage.copyUri(context, imageUri, temp)) {
                temp.delete()
                throw IOException("图片复制失败")
            }
            COVER_NAMES.forEach { name -> SafStorage.findFile(img, name)?.delete() }
            val renamed = runCatching { temp.renameTo("cover.$ext") }.getOrDefault(false)
            if (!renamed) {
                val fallback = img.findFile("cover.$ext")
                if (fallback == null || !fallback.isFile) {
                    temp.delete()
                    throw IOException("封面保存失败")
                }
            }
            refresh()
        }
    }

    fun coverDocument(book: ShelfBook): DocumentFile? {
        val img = SafStorage.findDirectory(book.folder, SafStorage.IMG_DIR) ?: return null
        return SafStorage.findFile(img, "cover.jpg")
            ?: SafStorage.findFile(img, "cover.png")
            ?: SafStorage.findFile(img, "cover.webp")
    }

    fun openBook(book: ShelfBook): BookRef? {
        val folder = book.folder ?: return null
        val txt = book.txtFile
            ?: resolveTxtFor(folder, book.folderName, book.txtFileName ?: book.record.txtFileName)
            ?: return null
        return BookRef(folder, book.displayName, txt, false)
    }

    fun readText(ref: BookRef): String = SafStorage.readText(context, ref.txtFile)

    fun saveText(ref: BookRef, text: String): Result<Unit> = runCatching {
        if (!SafStorage.writeText(context, ref.txtFile, text)) {
            throw IOException("写入主文稿 ${ref.txtFile.name ?: ""} 失败")
        }
    }

    fun ensureImgDir(ref: BookRef): DocumentFile? =
        SafStorage.ensureDirectory(ref.folder, SafStorage.IMG_DIR)

    fun imageDocument(ref: BookRef, fileName: String): DocumentFile? =
        SafStorage.findFile(SafStorage.findDirectory(ref.folder, SafStorage.IMG_DIR), fileName)

    fun insertImage(ref: BookRef, imageUri: Uri): DocumentFile? {
        if (!SafStorage.isSupportedImage(context, imageUri)) return null
        val img = ensureImgDir(ref) ?: return null
        val ext = SafStorage.extensionFrom(context, imageUri)
        // 顺序命名：扫描书目录 img/ 现有最大编号后递增（img/001.png、img/002.png ……）。
        val maxIndex = img.listFiles()
            .mapNotNull { it.name }
            .mapNotNull { IMAGE_NUMBER_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 0
        val name = "%03d.%s".format(maxIndex + 1, ext)
        val target = SafStorage.createFileExact(img, name, SafStorage.mimeForExtension(ext)) ?: return null
        if (!SafStorage.copyUri(context, imageUri, target)) {
            target.delete()
            return null
        }
        return target
    }

    /**
     * `@` 之间是文件名：`@666@` 只在 书目录/引用/666.txt 这一层查找，不递归。
     * 先精确匹配再忽略大小写；找不到直接返回 null。
     */
    fun resolveReference(fromRef: BookRef, name: String): BookRef? {
        val normalized = name.trim()
        if (normalized.isEmpty()) return null
        val base = if (normalized.lowercase(Locale.US).endsWith(".txt")) {
            normalized.substringBeforeLast('.')
        } else {
            normalized
        }
        val refDir = SafStorage.findDirectory(fromRef.folder, SafStorage.REF_DIR) ?: return null
        val target = "$base.txt"
        val files = runCatching { refDir.listFiles() }
            .getOrDefault(emptyArray())
            .filter { it.isFile }
        val hit = files.firstOrNull { it.name == target }
            ?: files.firstOrNull { it.name?.equals(target, ignoreCase = true) == true }
            ?: return null
        val display = hit.name.orEmpty().substringBeforeLast('.').ifBlank { base }
        return BookRef(refDir, display, hit, true)
    }

    /** 备份：把当前正文复制成 书目录/备份/<书名>_<yyyyMMdd_HHmm>.txt。 */
    fun createBackup(ref: BookRef): BackupResult {
        return runCatching {
            val dir = SafStorage.ensureDirectory(ref.folder, SafStorage.BACKUP_DIR)
                ?: throw IOException("无法创建备份目录")
            val text = readText(ref)
            val base = sanitizeName(ref.displayName)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            var name = "${base}_$stamp.txt"
            var suffix = 2
            while (dir.findFile(name) != null) {
                name = "${base}_${stamp}_$suffix.txt"
                suffix++
            }
            val target = SafStorage.createFileExact(dir, name, "text/plain")
                ?: throw IOException("无法创建备份文件")
            if (!SafStorage.writeText(context, target, text)) {
                target.delete()
                throw IOException("写入备份文件失败")
            }
            pruneBackups(dir, settingsRepository.settings.value.maxBackupCount)
            BackupResult(true, name, null)
        }.getOrElse { BackupResult(false, null, it.message ?: "备份失败") }
    }

    /** 只清理本应用生成的 txt 备份，已有 zip 备份文件保持不动。 */
    private fun pruneBackups(dir: DocumentFile, maxCount: Int) {
        runCatching {
            val backups = dir.listFiles()
                .filter { it.isFile && BACKUP_NAME_REGEX.matches(it.name ?: "") }
                .sortedBy { it.name ?: "" }
            val exceed = backups.size - maxCount.coerceIn(1, 20)
            if (exceed > 0) {
                backups.take(exceed).forEach { it.delete() }
            }
        }
    }

    private fun sanitizeName(name: String): String =
        name.trim().replace(NAME_SANITIZE_REGEX, "_").ifBlank { "未命名书籍" }

    private fun readRecords(): List<BookRecord> {
        val raw = prefs.getString(KEY_BOOKS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val uri = obj.optString("folderUri")
                if (uri.isBlank()) null
                else BookRecord(
                    folderUri = uri,
                    displayName = obj.optString("displayName").ifBlank { "未命名书籍" },
                    sortIndex = obj.optInt("sortIndex", index),
                    txtFileName = obj.optString("txtFileName").takeIf { it.isNotBlank() },
                    txtUri = obj.optString("txtUri").takeIf { it.isNotBlank() }
                )
            }.sortedBy { it.sortIndex }
        }.getOrDefault(emptyList())
    }

    private fun saveRecords(records: List<BookRecord>) {
        val array = JSONArray()
        records.forEachIndexed { index, record ->
            array.put(
                JSONObject().apply {
                    put("folderUri", record.folderUri)
                    put("displayName", record.displayName)
                    put("sortIndex", index)
                    record.txtFileName?.let { put("txtFileName", it) }
                    record.txtUri?.let { put("txtUri", it) }
                }
            )
        }
        prefs.edit().putString(KEY_BOOKS, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "novel_bookshelf"
        private const val KEY_BOOKS = "books"
        private val COVER_NAMES = listOf("cover.jpg", "cover.png", "cover.webp")
        private val BACKUP_NAME_REGEX = Regex("^.*_[0-9]{8}_[0-9]{4}(_[0-9]+)?\\.txt$")
        private val IMAGE_NUMBER_REGEX = Regex("^(\\d+)\\.")
        private val NAME_SANITIZE_REGEX = Regex("[\\\\/:*?\"<>|]")
    }
}
