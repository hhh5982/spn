package com.spn.novelmanager.viewmodel

import android.content.Context
import android.graphics.Bitmap
import androidx.documentfile.provider.DocumentFile
import com.spn.novelmanager.data.BookRepository
import com.spn.novelmanager.data.BookStateStore
import com.spn.novelmanager.data.ImageLoader
import com.spn.novelmanager.data.SafStorage
import com.spn.novelmanager.data.SettingsRepository
import com.spn.novelmanager.domain.Chapter
import com.spn.novelmanager.domain.ChapterOps
import com.spn.novelmanager.domain.ChapterParser
import com.spn.novelmanager.domain.DisplayDocument
import com.spn.novelmanager.domain.InlineNode
import com.spn.novelmanager.domain.InlineParser
import com.spn.novelmanager.model.BookRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class SaveStatus { IDLE, SAVING, SAVED, ERROR }

/**
 * 一次小说阅读/编辑会话。普通页面与引用页面各持有独立会话，
 * 图片、保存、备份、撤销全部作用于自己的书籍目录。
 */
class ReaderSession(
    private val context: Context,
    private val bookRepository: BookRepository,
    private val settingsRepository: SettingsRepository,
    val book: BookRef,
    private val scope: CoroutineScope,
    private val onMessage: (String) -> Unit,
    /** 引用文跟随来源：来源是编辑模式时，加载完成后直接进入编辑态。 */
    startInEdit: Boolean = false
) {

    private val stateStore = BookStateStore(context)

    /** 引用小说与主书可能共用同一个 引用/ 文件夹，按 txt 文件区分状态避免串号。 */
    private val stateKey: String = book.txtFile.uri.toString()

    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    private val _chapters = MutableStateFlow<List<Chapter>>(emptyList())
    val chapters: StateFlow<List<Chapter>> = _chapters.asStateFlow()

    /** 阅读态显示文本：结构标记被隐藏。 */
    private val _display = MutableStateFlow(DisplayDocument("", emptyList()))
    val display: StateFlow<DisplayDocument> = _display.asStateFlow()

    private val _imageBitmaps = MutableStateFlow<Map<String, Bitmap?>>(emptyMap())
    val imageBitmaps: StateFlow<Map<String, Bitmap?>> = _imageBitmaps.asStateFlow()

    private val _saveStatus = MutableStateFlow(SaveStatus.IDLE)
    val saveStatus: StateFlow<SaveStatus> = _saveStatus.asStateFlow()

    private val _editable = MutableStateFlow(false)
    val editable: StateFlow<Boolean> = _editable.asStateFlow()

    private val _loadFailed = MutableStateFlow(false)
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()

    private val _currentOffset = MutableStateFlow(0)
    val currentOffset: StateFlow<Int> = _currentOffset.asStateFlow()

    private val _jumpTarget = MutableStateFlow<Int?>(null)
    val jumpTarget: StateFlow<Int?> = _jumpTarget.asStateFlow()

    private val _caretTarget = MutableStateFlow<Int?>(null)
    val caretTarget: StateFlow<Int?> = _caretTarget.asStateFlow()

    private val _editing = MutableStateFlow(false)
    val editing: StateFlow<Boolean> = _editing.asStateFlow()

    private val _editChapterIndex = MutableStateFlow(0)
    val editChapterIndex: StateFlow<Int> = _editChapterIndex.asStateFlow()

    private val _editTitle = MutableStateFlow("")
    val editTitle: StateFlow<String> = _editTitle.asStateFlow()

    private val _editBody = MutableStateFlow("")
    val editBody: StateFlow<String> = _editBody.asStateFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _lastReadTime = MutableStateFlow<Long?>(null)
    val lastReadTime: StateFlow<Long?> = _lastReadTime.asStateFlow()

    private var editRangeStart = 0
    private var editRangeEnd = 0
    private var editBodyStart = 0

    private var lastSavedText: String? = null
    private var saveJob: Job? = null
    private var parseJob: Job? = null
    private var undoJob: Job? = null
    private var lastOffsetJob: Job? = null
    private val saveMutex = Mutex()
    private var closed = false
    private var pendingEdit = startInEdit

    private val undoStack: ArrayDeque<String> = stateStore.loadUndo(stateKey, undoSteps())
    private var undoBaseline: String = ""

    init {
        _canUndo.value = undoStack.isNotEmpty()
        load()
    }

    private fun undoSteps(): Int = settingsRepository.settings.value.undoSteps

    private fun load() {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { bookRepository.readText(book) }
            }
            result.onSuccess { raw ->
                _text.value = raw
                lastSavedText = raw
                undoBaseline = raw
                _editable.value = true
                applyParse(raw, immediate = true)
                loadMissingImages(raw)
                restoreLastRead(raw)
                if (pendingEdit) {
                    pendingEdit = false
                    startEditing()
                }
            }.onFailure {
                _loadFailed.value = true
                onMessage("读取失败，请检查文件夹权限")
            }

            if (settingsRepository.settings.value.autoBackupOnOpen) {
                val backup = withContext(Dispatchers.IO) { bookRepository.createBackup(book) }
                if (!backup.success) {
                    onMessage("自动备份失败：${backup.message ?: "未知错误"}")
                }
            }
        }
    }

    private fun restoreLastRead(raw: String) {
        val last = stateStore.lastRead(stateKey) ?: return
        _lastReadTime.value = last.timestamp
        val display = _display.value
        if (display.text.isEmpty()) return
        val safe = last.offset.coerceIn(0, display.text.length)
        _currentOffset.value = safe
        _jumpTarget.value = safe
    }

    private fun marks(): Pair<String, String> {
        val s = settingsRepository.settings.value
        return s.chapterStartMark.trim() to s.chapterEndMark.trim()
    }

    private fun applyParse(raw: String, immediate: Boolean) {
        val (start, end) = marks()
        val parsed = ChapterParser.parse(raw, start, end)
        _chapters.value = parsed
        _display.value = ChapterParser.buildDisplay(raw, parsed)
        if (immediate && raw.isNotEmpty() && _currentOffset.value == 0) {
            // 默认从当前位置继续；无历史位置时从文首开始。
        }
    }

    fun updateText(newText: String) {
        if (!_editable.value || closed) return
        if (newText == _text.value) return
        _text.value = newText
        reparse(newText, immediate = false)
        loadMissingImages(newText)
        scheduleSave(newText)
        scheduleUndoPush()
    }

    fun flushSave() {
        if (!_editable.value) return
        saveJob?.cancel()
        pushUndoIfNeeded()
        persistLastRead(_currentOffset.value)
        val snapshot = _text.value
        if (snapshot == lastSavedText) return
        scope.launch { persist(snapshot) }
    }

    fun close() {
        flushSave()
        saveJob?.cancel()
        parseJob?.cancel()
        undoJob?.cancel()
        lastOffsetJob?.cancel()
        closed = true
    }

    fun jumpTo(offset: Int) {
        flushSave()
        val safe = offset.coerceIn(0, _display.value.text.length)
        _currentOffset.value = safe
        _jumpTarget.value = safe
        persistLastRead(safe)
    }

    fun jumpToChapter(index: Int) {
        if (_editing.value) {
            switchEditChapter(index)
            return
        }
        val start = _display.value.chapterStarts.getOrNull(index) ?: 0
        jumpTo(start)
    }

    fun consumeJump() {
        _jumpTarget.value = null
    }

    fun consumeCaret() {
        _caretTarget.value = null
    }

    fun requestCaret(offset: Int) {
        _caretTarget.value = offset.coerceIn(0, _text.value.length)
    }

    fun updateCurrentOffset(offset: Int) {
        val safe = offset.coerceIn(0, _display.value.text.length)
        _currentOffset.value = safe
        scheduleLastReadWrite(safe)
    }

    fun currentChapter(): Chapter? =
        ChapterParser.chapterAtDisplay(_chapters.value, _display.value, _currentOffset.value)

    fun copyChapterText(): String = currentChapter()?.copyText ?: _text.value

    /** 简介：书名 + 正文前 100 字 + ……（永远不读取任何简介文件）。 */
    fun synopsis(): String {
        val settings = settingsRepository.settings.value
        val cleaned = _text.value
            .replace(settings.chapterStartMark.trim(), "")
            .replace(settings.chapterEndMark.trim(), "")
            .replace(Regex("<img[^>]*>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        val preview = cleaned.take(100)
        return if (preview.isEmpty()) book.displayName else "${book.displayName} $preview……"
    }

    fun wordCount(): Int = _text.value.count { !it.isWhitespace() }

    fun chapterCount(): Int = _chapters.value.size

    /** 当前章节在编辑态下的标题。 */
    fun currentEditTitle(): String = _editTitle.value

    // ---------------- 编辑模式 ----------------

    /** 进入编辑：先把每章归一化为独立标记块，避免共享块产生孤儿标记。 */
    fun startEditing(chapterIndex: Int = currentChapter()?.index ?: 0) {
        if (!_editable.value) return
        flushSave()
        pushUndoIfNeeded()
        val (start, end) = marks()
        val normalized = ChapterOps.normalize(_text.value, _chapters.value, start, end)
        if (normalized != _text.value) {
            applyDocument(normalized, immediateSave = false)
        }
        val parsed = _chapters.value
        val index = chapterIndex.coerceIn(0, (parsed.size - 1).coerceAtLeast(0))
        _editing.value = true
        enterEditChapter(parsed.getOrNull(index), index)
        undoBaseline = _text.value
    }

    fun stopEditing() {
        if (!_editing.value) return
        val chapterIndex = _editChapterIndex.value
        _editing.value = false
        applyParse(_text.value, immediate = true)
        val start = _display.value.chapterStarts.getOrNull(chapterIndex) ?: _currentOffset.value
        _currentOffset.value = start
        _jumpTarget.value = start
        flushSave()
    }

    fun editTitle(newTitle: String) {
        if (!_editing.value) return
        _editTitle.value = newTitle
        recomposeEditContent()
        scheduleUndoPush()
    }

    fun editBody(newBody: String) {
        if (!_editing.value) return
        _editBody.value = newBody
        recomposeEditContent()
        scheduleUndoPush()
    }

    private fun enterEditChapter(chapter: Chapter?, index: Int) {
        if (chapter == null) {
            _editChapterIndex.value = 0
            _editTitle.value = ""
            _editBody.value = ""
            editRangeStart = 0
            editRangeEnd = 0
            editBodyStart = 0
            return
        }
        _editChapterIndex.value = index
        _editTitle.value = chapter.title
        val rawBody = _text.value
            .substring(
                chapter.bodyStartOffset.coerceIn(0, _text.value.length),
                chapter.contentEndOffset.coerceIn(0, _text.value.length)
            )
        val leading = rawBody.length - rawBody.trimStart('\n', '\r').length
        val trailing = rawBody.length - rawBody.trimEnd('\n', '\r').length
        _editBody.value = rawBody.substring(leading, (rawBody.length - trailing).coerceAtLeast(leading))
        editRangeStart = chapter.titleStartOffset
        editRangeEnd = chapter.contentEndOffset
        editBodyStart = chapter.bodyStartOffset + leading
    }

    /** 用标题 + 正文重写当前章节的标题/正文范围。 */
    private fun recomposeEditContent() {
        val doc = _text.value
        val start = editRangeStart.coerceIn(0, doc.length)
        val end = editRangeEnd.coerceIn(start, doc.length)
        val prefix = doc.substring(0, start)
        val suffix = doc.substring(end)
        val title = _editTitle.value.trim().ifEmpty { "（未命名章节）" }
        val body = _editBody.value.trim('\n', '\r')
        val contentPrefix = if (prefix.isNotEmpty() && prefix.last() != '\n') "\n" else ""
        val newlineAfterTitle = "\n"
        val content = buildString {
            append(contentPrefix)
            append(title)
            append(newlineAfterTitle)
            if (body.isNotEmpty()) {
                append(body)
                append('\n')
            }
        }
        val newDoc = prefix + content + suffix
        editRangeEnd = start + content.length
        editBodyStart = start + contentPrefix.length + title.length + newlineAfterTitle.length
        _text.value = newDoc
        reparse(newDoc, immediate = false)
        loadMissingImages(newDoc)
        scheduleSave(newDoc)
    }

    fun switchEditChapter(index: Int) {
        if (!_editing.value) return
        flushSave()
        pushUndoIfNeeded()
        val list = _chapters.value
        val target = list.getOrNull(index)
        if (target == null) return
        enterEditChapter(target, index)
        undoBaseline = _text.value
    }

    /** 在当前章之后新增章节。 */
    fun insertChapterAfter(): Boolean {
        if (!_editing.value) return false
        flushSave()
        pushUndoIfNeeded()
        val (start, end) = marks()
        val list = _chapters.value
        val index = _editChapterIndex.value
        val current = list.getOrNull(index) ?: return false
        val newTitle = ChapterOps.nextChapterTitle(current.title, index + 2)
        val newText = ChapterOps.insertAfter(_text.value, list, index, start, end, newTitle)
        applyDocument(newText, immediateSave = true)
        val parsed = _chapters.value
        val nextIndex = (index + 1).coerceIn(0, (parsed.size - 1).coerceAtLeast(0))
        enterEditChapter(parsed.getOrNull(nextIndex), nextIndex)
        undoBaseline = _text.value
        onMessage("已新增章节：$newTitle")
        return true
    }

    /** 删除当前章节：至少保留一章。 */
    fun deleteCurrentChapter(): Boolean {
        if (!_editing.value) return false
        val list = _chapters.value
        if (list.size <= 1) {
            onMessage("至少保留一章，最后一章不可删除")
            return false
        }
        flushSave()
        pushUndoIfNeeded()
        val (start, end) = marks()
        val index = _editChapterIndex.value
        val newText = ChapterOps.deleteChapter(_text.value, list, index, start, end) ?: return false
        applyDocument(newText, immediateSave = true)
        val parsed = _chapters.value
        val nextIndex = index.coerceIn(0, (parsed.size - 1).coerceAtLeast(0))
        enterEditChapter(parsed.getOrNull(nextIndex), nextIndex)
        undoBaseline = _text.value
        onMessage("已删除章节，后续章节编号已顺延")
        return true
    }

    // ---------------- 撤销 ----------------

    fun undo(): Boolean {
        flushSave()
        if (undoStack.isEmpty()) {
            onMessage("没有可撤销的操作")
            return false
        }
        val snapshot = undoStack.removeLast()
        stateStore.saveUndo(stateKey, undoStack, undoSteps())
        _canUndo.value = undoStack.isNotEmpty()
        undoJob?.cancel()
        undoBaseline = snapshot
        applyDocument(snapshot, immediateSave = true)
        val index = _editChapterIndex.value.coerceIn(0, (_chapters.value.size - 1).coerceAtLeast(0))
        if (_editing.value) enterEditChapter(_chapters.value.getOrNull(index), index)
        onMessage("已撤销")
        return true
    }

    private fun scheduleUndoPush() {
        undoJob?.cancel()
        undoJob = scope.launch {
            delay(500)
            pushUndoIfNeeded()
        }
    }

    private fun pushUndoIfNeeded() {
        val current = _text.value
        if (undoBaseline == current) return
        undoStack.addLast(undoBaseline)
        while (undoStack.size > undoSteps()) undoStack.removeFirst()
        stateStore.saveUndo(stateKey, undoStack, undoSteps())
        _canUndo.value = true
        undoBaseline = current
    }

    // ---------------- 自动分章 ----------------

    fun autoChapter(): Int? {
        flushSave()
        pushUndoIfNeeded()
        val settings = settingsRepository.settings.value
        val (start, end) = marks()
        val result = ChapterOps.autoChapter(
            text = _text.value,
            startMark = start,
            endMark = end,
            prefix = settings.autoChapterPrefix,
            suffix = settings.autoChapterSuffix,
            minInnerLength = settings.autoChapterInnerMinLen,
            maxInnerLength = settings.autoChapterInnerMaxLen
        ) ?: return null
        applyDocument(result.first, immediateSave = true)
        if (_editing.value) {
            val index = _editChapterIndex.value.coerceIn(0, (_chapters.value.size - 1).coerceAtLeast(0))
            enterEditChapter(_chapters.value.getOrNull(index), index)
        }
        undoBaseline = _text.value
        return result.second
    }

    // ---------------- 图片 ----------------

    /**
     * 在当前光标处插入图片标记：
     * `<img src="img/001.png" width="600">` 独占一行；若插在段落中间，则把原段落拆成两段。
     */
    fun insertImageAt(offset: Int, fileName: String, imageDoc: DocumentFile) {
        insertImage(if (_editing.value) editBodyStart + offset else offset, fileName, imageDoc)
    }

    /** [offset] 是主文稿内的字符偏移。 */
    fun insertImage(offset: Int, fileName: String, imageDoc: DocumentFile) {
        pushUndoIfNeeded()
        val raw = _text.value
        val safe = offset.coerceIn(0, raw.length)
        val marker = "<img src=\"${SafStorage.IMG_DIR}/$fileName\" width=\"${InlineParser.DEFAULT_IMAGE_WIDTH}\">"
        val before = if (safe > 0 && raw[safe - 1] != '\n') "\n" else ""
        val after = if (safe < raw.length && raw[safe] != '\n') "\n" else ""
        val insert = before + marker + after
        val newText = raw.substring(0, safe) + insert + raw.substring(safe)
        if (_editing.value) {
            editRangeEnd += insert.length
            _editBody.value = newText
                .substring(
                    editBodyStart.coerceIn(0, newText.length),
                    editRangeEnd.coerceIn(0, newText.length)
                )
                .trimEnd('\n', '\r')
        }
        updateText(newText)
        val caret = if (_editing.value) (safe - editBodyStart) + insert.length else safe + insert.length
        requestCaret(caret.coerceAtLeast(0))
        undoBaseline = _text.value
        scope.launch {
            val bitmap = ImageLoader.load(context, imageDoc.uri, EDITOR_IMAGE_WIDTH)
            _imageBitmaps.update { it + (fileName to bitmap) }
        }
    }

    /** 自动分章等外部改写完成后同步内存状态（文件已经写入）。 */
    fun applyRewrittenText(newText: String) {
        saveJob?.cancel()
        _text.value = newText
        lastSavedText = newText
        undoBaseline = newText
        _saveStatus.value = SaveStatus.SAVED
        applyParse(newText, immediate = true)
        loadMissingImages(newText)
        if (_editing.value) {
            val index = _editChapterIndex.value.coerceIn(0, (_chapters.value.size - 1).coerceAtLeast(0))
            enterEditChapter(_chapters.value.getOrNull(index), index)
        }
    }

    // ---------------- 内部 ----------------

    private fun applyDocument(newText: String, immediateSave: Boolean) {
        _text.value = newText
        applyParse(newText, immediate = true)
        loadMissingImages(newText)
        if (immediateSave) {
            saveJob?.cancel()
            scope.launch { persist(newText) }
        } else {
            scheduleSave(newText)
        }
    }

    private fun reparse(raw: String, immediate: Boolean) {
        parseJob?.cancel()
        parseJob = scope.launch {
            if (!immediate) delay(250)
            val (start, end) = marks()
            val parsed = ChapterParser.parse(raw, start, end)
            if (!closed) {
                _chapters.value = parsed
                _display.value = ChapterParser.buildDisplay(raw, parsed)
            }
        }
    }

    private fun scheduleLastReadWrite(offset: Int) {
        lastOffsetJob?.cancel()
        lastOffsetJob = scope.launch {
            delay(800)
            persistLastRead(offset)
        }
    }

    private fun persistLastRead(offset: Int) {
        _lastReadTime.value = System.currentTimeMillis()
        stateStore.saveLastRead(stateKey, offset)
    }

    private fun loadMissingImages(raw: String) {
        val names = InlineParser.imageTokens(raw)
            .mapNotNull { (it.node as? InlineNode.Image)?.fileName }
            .distinct()
            .filter { name -> name !in _imageBitmaps.value }
        if (names.isEmpty()) return
        _imageBitmaps.update { map -> map + names.associateWith { null } }
        names.forEach { name ->
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    val doc = bookRepository.imageDocument(book, name) ?: return@withContext null
                    ImageLoader.load(context, doc.uri, EDITOR_IMAGE_WIDTH)
                }
                _imageBitmaps.update { it + (name to bitmap) }
            }
        }
    }

    private fun scheduleSave(snapshot: String) {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(settingsRepository.settings.value.autosaveDebounceMs)
            persist(snapshot)
        }
    }

    private suspend fun persist(snapshot: String) {
        saveMutex.withLock {
            if (snapshot == lastSavedText) return
            _saveStatus.value = SaveStatus.SAVING
            val result = withContext(Dispatchers.IO) { bookRepository.saveText(book, snapshot) }
            if (result.isSuccess) {
                lastSavedText = snapshot
                _saveStatus.value = SaveStatus.SAVED
            } else {
                _saveStatus.value = SaveStatus.ERROR
                onMessage("保存失败，请检查文件夹权限")
            }
        }
    }

    companion object {
        const val EDITOR_IMAGE_WIDTH = 1200
    }
}
