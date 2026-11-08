package com.spn.novelmanager.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.spn.novelmanager.data.ImageLoader
import com.spn.novelmanager.data.SafStorage
import com.spn.novelmanager.model.AppSettings
import com.spn.novelmanager.model.ShelfBook
import com.spn.novelmanager.ui.theme.ReadingColors
import com.spn.novelmanager.viewmodel.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun BookshelfScreen(
    viewModel: AppViewModel,
    settings: AppSettings,
    books: List<ShelfBook>,
    colors: ReadingColors
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var allFilesAccess by remember { mutableStateOf(SafStorage.hasAllFilesAccess()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allFilesAccess = SafStorage.hasAllFilesAccess()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importTxt(uri)
    }

    fun requestAllFilesAccess() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(Uri.parse("package:${context.packageName}"))
        val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        runCatching { context.startActivity(intent) }
            .recoverCatching { context.startActivity(fallback) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "书架",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.text,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { importLauncher.launch(TXT_MIME_TYPES) }) {
                Text("+", fontSize = 22.sp, color = colors.accent)
            }
        }

        if (!allFilesAccess && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                color = colors.surface
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "需要「所有文件访问」权限，才能在 txt 所在目录写入 img、引用与备份",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.secondaryText,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { requestAllFilesAccess() }) {
                        Text("授权", color = colors.accent)
                    }
                }
            }
        }

        if (books.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.List,
                        contentDescription = null,
                        tint = colors.marker
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("书架还是空的", color = colors.text)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "点击右上角 + 选择一本 .txt 小说，txt 所在目录即这本书的根目录",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.secondaryText,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 136.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(books, key = { it.key }) { book ->
                    BookCard(
                        book = book,
                        colors = colors,
                        onClick = {
                            if (book.hasMainTxt) {
                                viewModel.openBook(book)
                            } else {
                                viewModel.postMessage("该书未找到 txt 或所在目录不可访问")
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun BookCard(
    book: ShelfBook,
    colors: ReadingColors,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val coverBitmap by produceState<Bitmap?>(initialValue = null, key1 = book) {
        value = withContext(Dispatchers.IO) {
            val doc = book.folder?.findFile(SafStorage.IMG_DIR)?.let { img ->
                img.findFile("cover.jpg")?.takeIf { it.isFile }
                    ?: img.findFile("cover.png")?.takeIf { it.isFile }
                    ?: img.findFile("cover.webp")?.takeIf { it.isFile }
            } ?: return@withContext null
            ImageLoader.load(context, doc.uri, 480)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp)),
            color = colors.surface
        ) {
            val bitmap = coverBitmap
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = book.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.List,
                            contentDescription = null,
                            tint = colors.marker
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "暂无封面",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.secondaryText
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = book.displayName,
            color = colors.text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

private val TXT_MIME_TYPES = arrayOf("text/plain", "text/*", "application/octet-stream")
