package com.spn.novelmanager.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spn.novelmanager.data.BookRepository
import com.spn.novelmanager.data.SafStorage
import com.spn.novelmanager.data.SettingsRepository
import com.spn.novelmanager.model.AppSettings
import com.spn.novelmanager.model.BookRef
import com.spn.novelmanager.model.ImportResult
import com.spn.novelmanager.model.ShelfBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Bookshelf : Screen
    data object Settings : Screen
    data object Profile : Screen
    data class Detail(val session: ReaderSession) : Screen
    data class Reader(val session: ReaderSession) : Screen
}

class AppViewModel(application: Application) : AndroidViewModel(application) {

    val settingsRepository = SettingsRepository(application)
    val bookRepository = BookRepository(application, settingsRepository)

    val settings: StateFlow<AppSettings> = settingsRepository.settings
    val books: StateFlow<List<ShelfBook>> = bookRepository.books
    val snackbarHostState = androidx.compose.material3.SnackbarHostState()

    private val _stack = MutableStateFlow<List<Screen>>(listOf(Screen.Bookshelf))
    val stack: StateFlow<List<Screen>> = _stack.asStateFlow()

    init {
        refreshShelf()
    }

    fun refreshShelf() {
        viewModelScope.launch(Dispatchers.IO) { bookRepository.refresh() }
    }

    fun postMessage(message: String) {
        viewModelScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    fun selectTab(screen: Screen) {
        val current = _stack.value
        if (current.size == 1 && current[0] == screen) return
        _stack.value = listOf(screen)
    }

    fun openBook(book: ShelfBook) {
        viewModelScope.launch {
            if (book.record.txtUri != null && !SafStorage.hasAllFilesAccess()) {
                postMessage("请先授予「所有文件访问」权限后再打开该书")
                return@launch
            }
            val ref = withContext(Dispatchers.IO) { bookRepository.openBook(book) }
            if (ref == null) {
                postMessage("无法打开该书：未找到 txt 或所在目录不可访问")
                refreshShelf()
                return@launch
            }
            _stack.update { it + Screen.Detail(createSession(ref)) }
        }
    }

    /** 从详情页进入阅读或编辑模式。 */
    fun enterReader(session: ReaderSession, editMode: Boolean) {
        if (editMode) {
            session.startEditing()
        } else {
            session.stopEditing()
        }
        _stack.update { it + Screen.Reader(session) }
    }

    fun openReference(session: ReaderSession, name: String) {
        viewModelScope.launch {
            session.flushSave()
            val ref = withContext(Dispatchers.IO) {
                bookRepository.resolveReference(session.book, name)
            }
            if (ref == null) {
                val fileName = name.trim().removeSuffix(".txt")
                postMessage("未找到引用：$fileName（引用/$fileName.txt）")
                return@launch
            }
            // 引用文打开模式跟随来源：阅读态引用仍为阅读态，编辑态引用进入编辑态。
            val editMode = session.editing.value
            _stack.update { it + Screen.Reader(createSession(ref, startInEdit = editMode)) }
        }
    }

    /** 从阅读页样式面板进入底部 Tab 的设置页，返回时回到当前小说。 */
    fun openSettingsPage() {
        _stack.update { it + Screen.Settings }
    }

    private fun createSession(ref: BookRef, startInEdit: Boolean = false): ReaderSession =
        ReaderSession(
            getApplication(),
            bookRepository,
            settingsRepository,
            ref,
            viewModelScope,
            { message -> postMessage(message) },
            startInEdit
        )

    fun back() {
        val stack = _stack.value
        if (stack.size <= 1) return
        val current = stack.last()
        // 编辑态返回：先退出编辑（落盘）再回到小说详情页。
        if (current is Screen.Reader && current.session.editing.value) {
            current.session.stopEditing()
        }
        current.sessionFlush()
        if (current is Screen.Detail) current.session.close()
        _stack.value = stack.dropLast(1)
    }

    fun onAppPause() {
        _stack.value.forEach { it.sessionFlush() }
    }

    private fun Screen.sessionFlush() {
        when (this) {
            is Screen.Detail -> session.flushSave()
            is Screen.Reader -> session.flushSave()
            else -> Unit
        }
    }

    fun importTxt(txtUri: Uri, onResult: (ImportResult) -> Unit = {}) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { bookRepository.importTxt(txtUri) }
            when (result) {
                is ImportResult.Success -> postMessage("已添加《${result.displayName}》")
                ImportResult.Duplicate -> postMessage("该书籍已存在")
                is ImportResult.Failure -> postMessage(result.message)
            }
            refreshShelf()
            onResult(result)
        }
    }

    fun renameBook(book: ShelfBook, newName: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { bookRepository.renameDisplay(book.record, newName) }
            if (result.isSuccess) {
                postMessage("已修改书籍名称")
                onResult(true, null)
            } else {
                val message = result.exceptionOrNull()?.message ?: "修改失败"
                postMessage(message)
                onResult(false, message)
            }
            refreshShelf()
        }
    }

    fun setCover(book: ShelfBook, imageUri: Uri, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { bookRepository.setCover(book, imageUri) }
            if (result.isSuccess) {
                postMessage("封面已更新")
                onResult(true, null)
            } else {
                val message = result.exceptionOrNull()?.message ?: "封面更新失败"
                postMessage(message)
                onResult(false, message)
            }
            refreshShelf()
        }
    }

    fun insertImage(session: ReaderSession, imageUri: Uri, caretOffset: Int) {
        viewModelScope.launch {
            val doc = withContext(Dispatchers.IO) { bookRepository.insertImage(session.book, imageUri) }
            if (doc == null) {
                postMessage("图片复制失败")
                return@launch
            }
            session.insertImageAt(caretOffset, doc.name ?: "image", doc)
        }
    }

    suspend fun rewriteText(session: ReaderSession, newText: String): Boolean =
        withContext(Dispatchers.IO) {
            val result = bookRepository.saveText(session.book, newText)
            if (result.isSuccess) {
                session.applyRewrittenText(newText)
                true
            } else false
        }
}
