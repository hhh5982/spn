package com.spn.novelmanager.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spn.novelmanager.data.ImageLoader
import com.spn.novelmanager.ui.theme.ReadingColors
import com.spn.novelmanager.viewmodel.AppViewModel
import com.spn.novelmanager.viewmodel.ReaderSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BookDetailScreen(
    viewModel: AppViewModel,
    session: ReaderSession,
    colors: ReadingColors
) {
    val context = LocalContext.current
    val text by session.text.collectAsStateWithLifecycle()
    val chapters by session.chapters.collectAsStateWithLifecycle()
    val lastRead by session.lastReadTime.collectAsStateWithLifecycle()
    val editable by session.editable.collectAsStateWithLifecycle()
    val loadFailed by session.loadFailed.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()

    val shelfBook = books.firstOrNull {
        it.txtFile?.uri?.toString() == session.book.txtFile.uri.toString()
    }
    var showEditDialog by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf("") }
    var pendingCover by remember { mutableStateOf<Uri?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var coverVersion by remember { mutableStateOf(0) }

    val coverLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) pendingCover = uri
    }

    val cover by produceState<Bitmap?>(
        initialValue = null,
        key1 = session.book.txtFile.uri,
        key2 = coverVersion
    ) {
        value = withContext(Dispatchers.IO) {
            val doc = session.book.folder.findFile("img")?.let { img ->
                img.findFile("cover.jpg")?.takeIf { it.isFile }
                    ?: img.findFile("cover.png")?.takeIf { it.isFile }
                    ?: img.findFile("cover.webp")?.takeIf { it.isFile }
            } ?: return@withContext null
            ImageLoader.load(context, doc.uri, 720)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { viewModel.back() }) {
                Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "返回书架", tint = colors.text)
            }
            Text(
                text = "小说详情",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.text,
                modifier = Modifier.weight(1f)
            )
            if (shelfBook != null) {
                TextButton(
                    onClick = {
                        nameDraft = shelfBook.displayName
                        pendingCover = null
                        errorText = null
                        showEditDialog = true
                    }
                ) {
                    Text("编辑", color = colors.accent)
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                modifier = Modifier.widthIn(max = 260.dp).fillMaxWidth(0.65f).clip(RoundedCornerShape(14.dp)),
                color = colors.surface
            ) {
                val bitmap = cover
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = session.book.displayName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.List, contentDescription = null, tint = colors.marker)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("暂无封面", color = colors.secondaryText, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = session.book.displayName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = colors.text,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "字数 ${text.count { !it.isWhitespace() }} · 章节 ${chapters.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.secondaryText
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "最近阅读：" + (
                    lastRead?.let { formatTime(it) } ?: "尚未阅读"
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondaryText
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = if (editable) session.synopsis() else "正在读取……",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.text,
                modifier = Modifier.fillMaxWidth()
            )

            if (loadFailed) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "读取失败：请检查 txt 所在目录的访问权限后重试。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(28.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Button(
                    onClick = { viewModel.enterReader(session, editMode = false) },
                    enabled = editable,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                ) { Text("阅读模式") }
                OutlinedButton(
                    onClick = { viewModel.enterReader(session, editMode = true) },
                    enabled = editable,
                    modifier = Modifier.weight(1f)
                ) { Text("编辑模式", color = colors.accent) }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    val editBook = shelfBook
    if (showEditDialog && editBook != null) {
        val duplicate = books.any {
            it.record.key != editBook.record.key && it.displayName.trim() == nameDraft.trim()
        }
        AlertDialog(
            onDismissRequest = {
                showEditDialog = false
                pendingCover = null
                errorText = null
            },
            title = { Text("编辑书籍") },
            text = {
                Column {
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = {
                            nameDraft = it
                            errorText = null
                        },
                        label = { Text("书籍名称") },
                        singleLine = true,
                        isError = errorText != null || duplicate,
                        supportingText = {
                            when {
                                errorText != null -> Text(errorText!!)
                                duplicate -> Text("该名称与其他书籍重复")
                                else -> Text("仅修改展示名称，不改动 txt 与所在目录")
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "所在目录：${editBook.folderName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.secondaryText
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { coverLauncher.launch(arrayOf("image/*")) }) {
                            Text("更换封面")
                        }
                        if (pendingCover != null) {
                            Text(
                                "已选择新封面，保存后生效",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.secondaryText
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = nameDraft.trim()
                        if (name.isEmpty()) {
                            errorText = "书籍名称不能为空"
                            return@TextButton
                        }
                        if (duplicate) {
                            errorText = "该名称与其他书籍重复"
                            return@TextButton
                        }
                        fun finish() {
                            session.book.displayName = name
                            showEditDialog = false
                            pendingCover = null
                            errorText = null
                        }
                        val cover = pendingCover
                        if (cover != null) {
                            viewModel.setCover(editBook, cover) { ok, message ->
                                if (ok) {
                                    coverVersion++
                                    if (name != editBook.displayName) {
                                        viewModel.renameBook(editBook, name) { renamed, msg ->
                                            if (renamed) finish() else errorText = msg
                                        }
                                    } else {
                                        finish()
                                    }
                                } else {
                                    errorText = message
                                }
                            }
                        } else if (name != editBook.displayName) {
                            viewModel.renameBook(editBook, name) { renamed, msg ->
                                if (renamed) finish() else errorText = msg
                            }
                        } else {
                            finish()
                        }
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showEditDialog = false
                        pendingCover = null
                        errorText = null
                    }
                ) { Text("取消") }
            }
        )
    }
}

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
