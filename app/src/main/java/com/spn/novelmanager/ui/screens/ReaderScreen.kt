package com.spn.novelmanager.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spn.novelmanager.data.SafStorage
import com.spn.novelmanager.domain.Chapter
import com.spn.novelmanager.model.AppSettings
import com.spn.novelmanager.model.FlipMode
import com.spn.novelmanager.model.ReadingTheme
import com.spn.novelmanager.ui.editor.NovelEditor
import com.spn.novelmanager.ui.theme.ReadingColors
import com.spn.novelmanager.ui.theme.readingColors
import com.spn.novelmanager.ui.theme.themeDisplayName
import com.spn.novelmanager.viewmodel.AppViewModel
import com.spn.novelmanager.viewmodel.ReaderSession
import com.spn.novelmanager.viewmodel.SaveStatus
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** 行距四档：紧凑 / 标准 / 宽松 / 超大。 */
private val LINE_SPACING_PRESETS = listOf(
    "紧凑" to 1.15f,
    "标准" to 1.35f,
    "宽松" to 1.60f,
    "超大" to 1.90f
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    viewModel: AppViewModel,
    session: ReaderSession,
    colors: ReadingColors
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val chapters by session.chapters.collectAsStateWithLifecycle()
    val currentOffset by session.currentOffset.collectAsStateWithLifecycle()
    val display by session.display.collectAsStateWithLifecycle()
    val saveStatus by session.saveStatus.collectAsStateWithLifecycle()
    val editable by session.editable.collectAsStateWithLifecycle()
    val loadFailed by session.loadFailed.collectAsStateWithLifecycle()
    val editing by session.editing.collectAsStateWithLifecycle()
    val editTitle by session.editTitle.collectAsStateWithLifecycle()
    val editChapterIndex by session.editChapterIndex.collectAsStateWithLifecycle()
    val canUndo by session.canUndo.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var toolbarVisible by remember { mutableStateOf(false) }
    var stylePanelVisible by remember { mutableStateOf(false) }
    var brightnessPanelVisible by remember { mutableStateOf(false) }
    /** 默认跟随系统亮度，不接管窗口亮度；关闭后才允许手动调节。 */
    var useSystemBrightness by remember { mutableStateOf(true) }
    var manualBrightness by remember { mutableStateOf(1f) }
    var showInsertDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showAutoChapterDialog by remember { mutableStateOf(false) }
    var caretOffset by remember { mutableStateOf(0) }
    var lastLightTheme by remember {
        mutableStateOf(if (settings.theme == ReadingTheme.NIGHT) ReadingTheme.LIGHT else settings.theme)
    }
    var sliderValue by remember { mutableStateOf(0f) }

    val chapter: Chapter? = if (editing) {
        chapters.getOrNull(editChapterIndex)
    } else {
        chapters.lastOrNull { currentOffset >= (display.chapterStarts.getOrNull(it.index) ?: 0) }
    }
    val chapterIndex = chapter?.index ?: 0
    val activity = remember(context) { context.findActivity() }

    LaunchedEffect(chapterIndex, chapters.size, editing) {
        sliderValue = chapterIndex.toFloat()
    }

    // 进入阅读态不自动显示工具栏，只能由点击屏幕中部或右上角设置图标唤出。
    LaunchedEffect(editing) {
        brightnessPanelVisible = false
        stylePanelVisible = false
        toolbarVisible = false
    }

    // 默认使用系统亮度；只有关闭「系统亮度」后才改当前 App 窗口亮度。
    LaunchedEffect(useSystemBrightness, manualBrightness, editing) {
        val window = activity?.window ?: return@LaunchedEffect
        window.attributes = window.attributes.apply {
            screenBrightness = if (useSystemBrightness || editing) {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            } else {
                manualBrightness
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val window = activity?.window ?: return@onDispose
            window.attributes = window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }

    fun hideBars() {
        toolbarVisible = false
        stylePanelVisible = false
        brightnessPanelVisible = false
    }

    /** 右上角设置图标：工具栏已展开则收起；已收起则展开（编辑态只展开工具栏，不直接弹样式面板）。 */
    fun toggleStyleFromTopBar() {
        if (toolbarVisible) {
            hideBars()
        } else {
            toolbarVisible = true
            stylePanelVisible = !editing
            brightnessPanelVisible = false
        }
    }

    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            if (SafStorage.isSupportedImage(context, uri)) {
                viewModel.insertImage(session, uri, caretOffset)
            } else {
                viewModel.postMessage("仅支持 png / jpg / webp 图片")
            }
        }
    }

    fun openDirectory() {
        scope.launch { drawerState.open() }
    }

    fun jumpChapter(index: Int) {
        if (index < 0 || index >= chapters.size) return
        if (editing) session.switchEditChapter(index) else session.jumpToChapter(index)
    }

    fun switchTheme(theme: ReadingTheme) {
        if (theme != ReadingTheme.NIGHT) lastLightTheme = theme
        viewModel.settingsRepository.update { it.copy(theme = theme) }
    }

    val contentBottomInset: Dp = when {
        !toolbarVisible -> 0.dp
        stylePanelVisible -> 268.dp
        brightnessPanelVisible && !editing -> 164.dp
        else -> 96.dp
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        scrimColor = Color.Black.copy(alpha = 0.35f),
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = colors.surface,
                modifier = Modifier.width(300.dp)
            ) {
                ChapterDrawer(
                    session = session,
                    chapters = chapters,
                    currentChapter = chapter,
                    editing = editing,
                    colors = colors,
                    onJump = { index ->
                        jumpChapter(index)
                        scope.launch { drawerState.close() }
                    },
                    onAutoChapter = {
                        scope.launch { drawerState.close() }
                        showAutoChapterDialog = true
                    }
                )
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
            Column(modifier = Modifier.fillMaxSize()) {
                ReaderTopBar(
                    session = session,
                    chapter = chapter,
                    editing = editing,
                    saveStatus = saveStatus,
                    colors = colors,
                    canUndo = canUndo,
                    onBack = { viewModel.back() },
                    onDirectory = { openDirectory() },
                    onStyle = { toggleStyleFromTopBar() },
                    onUndo = { session.undo() },
                    onInsertImage = { imageLauncher.launch(arrayOf("image/*")) }
                )

                if (loadFailed) {
                    Text(
                        text = "读取失败：请检查文件夹权限后重新打开。当前内容不会被覆盖。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }

                if (editing) {
                    EditTitleHeader(
                        title = editTitle,
                        settings = settings,
                        colors = colors,
                        onTitleChange = { session.editTitle(it) },
                        onInsert = { showInsertDialog = true },
                        onDelete = { showDeleteDialog = true }
                    )
                    HorizontalDivider(color = colors.marker.copy(alpha = 0.2f))
                }

                NovelEditor(
                    session = session,
                    settings = settings,
                    colors = colors,
                    onMiddleTap = {
                        if (toolbarVisible) {
                            hideBars()
                        } else if (!editing) {
                            toolbarVisible = true
                            stylePanelVisible = false
                            brightnessPanelVisible = false
                        }
                    },
                    onReadOffsetChanged = { offset -> session.updateCurrentOffset(offset) },
                    onSelectionChanged = { offset -> caretOffset = offset },
                    onQuoteJump = { name ->
                        if (editable) viewModel.openReference(session, name)
                    },
                    onMessage = { message -> viewModel.postMessage(message) },
                    onUserScrolled = { hideBars() },
                    bottomInset = contentBottomInset,
                    modifier = Modifier.weight(1f).imePadding()
                )
            }

            // 底部半透明工具栏 + 贴在其上方的样式 / 亮度面板。
            if (toolbarVisible) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(colors.surface.copy(alpha = 0.92f))
                ) {
                    HorizontalDivider(color = colors.marker.copy(alpha = 0.18f))
                    if (stylePanelVisible) {
                        StylePanel(
                            settings = settings,
                            colors = colors,
                            showFlipMode = !editing,
                            onFontSize = { size ->
                                viewModel.settingsRepository.update {
                                    it.copy(fontSizeSp = size.coerceIn(12f, 34f))
                                }
                            },
                            onLineSpacing = { spacing ->
                                viewModel.settingsRepository.update { it.copy(lineSpacing = spacing) }
                            },
                            onTheme = { theme -> switchTheme(theme) },
                            onFlipMode = { mode ->
                                viewModel.settingsRepository.update { it.copy(flipMode = mode) }
                            },
                            onMoreSettings = {
                                hideBars()
                                viewModel.openSettingsPage()
                            }
                        )
                    }
                    if (brightnessPanelVisible && !editing) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "系统亮度",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.text,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = useSystemBrightness,
                                    onCheckedChange = { useSystemBrightness = it },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = colors.accent,
                                        checkedTrackColor = colors.accent.copy(alpha = 0.4f)
                                    )
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "亮度 ${(manualBrightness * 100).roundToInt()}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (useSystemBrightness) colors.marker else colors.secondaryText
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Slider(
                                    value = manualBrightness,
                                    onValueChange = { manualBrightness = it },
                                    valueRange = 0.2f..1.0f,
                                    enabled = !useSystemBrightness,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                    ReaderToolbar(
                        editing = editing,
                        colors = colors,
                        nightSelected = settings.theme == ReadingTheme.NIGHT,
                        brightnessSelected = brightnessPanelVisible,
                        showChapterRow = !stylePanelVisible,
                        chapterIndex = chapterIndex,
                        chapterCount = chapters.size,
                        sliderValue = sliderValue,
                        onSliderValue = { sliderValue = it },
                        onSliderFinish = { jumpChapter(sliderValue.roundToInt()) },
                        onPrevChapter = { jumpChapter(chapterIndex - 1) },
                        onNextChapter = { jumpChapter(chapterIndex + 1) },
                        onDirectory = { openDirectory() },
                        onBrightness = {
                            if (editing) {
                                viewModel.postMessage("编辑模式不支持亮度调节")
                            } else {
                                brightnessPanelVisible = !brightnessPanelVisible
                                if (brightnessPanelVisible) stylePanelVisible = false
                            }
                        },
                        onNight = {
                            if (settings.theme == ReadingTheme.NIGHT) {
                                switchTheme(lastLightTheme)
                            } else {
                                switchTheme(ReadingTheme.NIGHT)
                            }
                        },
                        onStyle = {
                            stylePanelVisible = !stylePanelVisible
                            if (stylePanelVisible) brightnessPanelVisible = false
                        }
                    )
                }
            }
        }
    }

    if (showInsertDialog) {
        AlertDialog(
            onDismissRequest = { showInsertDialog = false },
            title = { Text("新增章节") },
            text = { Text("是否在当前章节后面新增一个章节？后续章节编号将自动顺延。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showInsertDialog = false
                        session.insertChapterAfter()
                    }
                ) { Text("确认新增") }
            },
            dismissButton = {
                TextButton(onClick = { showInsertDialog = false }) { Text("取消") }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除章节") },
            text = {
                Text(
                    if (chapters.size <= 1) "至少保留一章，最后一章不可删除。"
                    else "是否删除当前章节？后续章节编号将自动顺延。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        session.deleteCurrentChapter()
                    }
                ) {
                    Text(
                        "确认删除",
                        color = if (chapters.size > 1) MaterialTheme.colorScheme.error else colors.secondaryText
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }

    if (showAutoChapterDialog) {
        AlertDialog(
            onDismissRequest = { showAutoChapterDialog = false },
            title = { Text("自动分章") },
            text = { Text("将无视现有标记，按全文「第*章」重新切分，并为每章重新补上标记。是否继续？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAutoChapterDialog = false
                        val count = session.autoChapter()
                        if (count == null) {
                            viewModel.postMessage("未识别到「第N章」标题，未做修改")
                        } else {
                            viewModel.postMessage("自动分章完成，共 $count 章")
                            if (!editing) session.jumpTo(0)
                        }
                    }
                ) { Text("确认分章") }
            },
            dismissButton = {
                TextButton(onClick = { showAutoChapterDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ReaderToolbar(
    editing: Boolean,
    colors: ReadingColors,
    nightSelected: Boolean,
    brightnessSelected: Boolean,
    showChapterRow: Boolean,
    chapterIndex: Int,
    chapterCount: Int,
    sliderValue: Float,
    onSliderValue: (Float) -> Unit,
    onSliderFinish: () -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onDirectory: () -> Unit,
    onBrightness: () -> Unit,
    onNight: () -> Unit,
    onStyle: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp)
    ) {
        if (showChapterRow) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onPrevChapter, enabled = chapterIndex > 0) {
                    Text(
                        "上一章",
                        color = if (chapterIndex > 0) colors.text else colors.marker,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (editing) {
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        "第 ${chapterIndex + 1} / ${chapterCount.coerceAtLeast(1)} 章",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.secondaryText
                    )
                    Spacer(modifier = Modifier.weight(1f))
                } else if (chapterCount > 1) {
                    Slider(
                        value = sliderValue.coerceIn(0f, (chapterCount - 1).toFloat()),
                        onValueChange = onSliderValue,
                        onValueChangeFinished = onSliderFinish,
                        valueRange = 0f..(chapterCount - 1).toFloat(),
                        steps = (chapterCount - 2).coerceAtLeast(0),
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        "第 1 / 1 章",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.secondaryText
                    )
                    Spacer(modifier = Modifier.weight(1f))
                }
                TextButton(onClick = onNextChapter, enabled = chapterIndex < chapterCount - 1) {
                    Text(
                        "下一章",
                        color = if (chapterIndex < chapterCount - 1) colors.text else colors.marker,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToolbarAction(
                icon = { tint -> Icon(Icons.Default.List, contentDescription = "目录", tint = tint) },
                label = "目录",
                active = false,
                colors = colors,
                onClick = onDirectory,
                modifier = Modifier.weight(1f)
            )
            ToolbarAction(
                icon = { tint -> Icon(Icons.Default.Brightness6, contentDescription = "亮度", tint = tint) },
                label = "亮度",
                active = brightnessSelected,
                colors = colors,
                onClick = onBrightness,
                modifier = Modifier.weight(1f)
            )
            ToolbarAction(
                icon = { tint ->
                    if (nightSelected) {
                        Icon(Icons.Default.LightMode, contentDescription = "日间", tint = tint)
                    } else {
                        Icon(Icons.Default.DarkMode, contentDescription = "夜间", tint = tint)
                    }
                },
                label = if (nightSelected) "日间" else "夜间",
                active = nightSelected,
                colors = colors,
                onClick = onNight,
                modifier = Modifier.weight(1f)
            )
            ToolbarAction(
                icon = { tint -> Icon(Icons.Default.Settings, contentDescription = "设置", tint = tint) },
                label = "设置",
                active = false,
                colors = colors,
                onClick = onStyle,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ToolbarAction(
    icon: @Composable (Color) -> Unit,
    label: String,
    active: Boolean,
    colors: ReadingColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tint = if (active) colors.accent else colors.text
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        icon(tint)
        Text(
            text = label,
            fontSize = 11.sp,
            color = if (active) colors.accent else colors.secondaryText,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun StylePanel(
    settings: AppSettings,
    colors: ReadingColors,
    showFlipMode: Boolean,
    onFontSize: (Float) -> Unit,
    onLineSpacing: (Float) -> Unit,
    onTheme: (ReadingTheme) -> Unit,
    onFlipMode: (FlipMode) -> Unit,
    onMoreSettings: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "字号",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.text,
                modifier = Modifier.width(56.dp)
            )
            TextButton(onClick = { onFontSize(settings.fontSizeSp - 1f) }) {
                Text("A-", color = colors.text, fontSize = 16.sp)
            }
            Text(
                text = settings.fontSizeSp.roundToInt().toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.accent,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(36.dp)
            )
            TextButton(onClick = { onFontSize(settings.fontSizeSp + 1f) }) {
                Text("A+", color = colors.text, fontSize = 16.sp)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "行距",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.text,
                modifier = Modifier.width(56.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LINE_SPACING_PRESETS.forEach { (label, value) ->
                    val selected = abs(settings.lineSpacing - value) < 0.05f
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (selected) colors.accent.copy(alpha = 0.16f) else Color.Transparent
                            )
                            .border(
                                width = 1.dp,
                                color = if (selected) colors.accent else colors.marker.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable { onLineSpacing(value) }
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            color = if (selected) colors.accent else colors.text
                        )
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "背景",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.text,
                modifier = Modifier.width(56.dp)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ReadingTheme.entries.forEach { theme ->
                    val preview = readingColors(settings.copy(theme = theme))
                    val selected = settings.theme == theme
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(preview.background)
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) colors.accent else colors.marker.copy(alpha = 0.5f),
                                shape = CircleShape
                            )
                            .clickable { onTheme(theme) }
                    )
                }
                Text(
                    text = themeDisplayName(settings.theme),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.secondaryText
                )
            }
        }

        if (showFlipMode) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "翻页",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.text,
                    modifier = Modifier.width(56.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlipMode.entries.forEach { mode ->
                        val selected = settings.flipMode == mode
                        val label = when (mode) {
                            FlipMode.SCROLL -> "上下滑动"
                            FlipMode.PAGE -> "整页翻动"
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (selected) colors.accent.copy(alpha = 0.16f) else Color.Transparent
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (selected) colors.accent else colors.marker.copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable { onFlipMode(mode) }
                                .padding(horizontal = 12.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                color = if (selected) colors.accent else colors.text
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "更多设置",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.text,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onMoreSettings) {
                Text("前往设置页", color = colors.accent, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ReaderTopBar(
    session: ReaderSession,
    chapter: Chapter?,
    editing: Boolean,
    saveStatus: SaveStatus,
    colors: ReadingColors,
    canUndo: Boolean,
    onBack: () -> Unit,
    onDirectory: () -> Unit,
    onStyle: () -> Unit,
    onUndo: () -> Unit,
    onInsertImage: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background)
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "返回", tint = colors.text)
        }
        IconButton(onClick = onDirectory) {
            Icon(Icons.Default.List, contentDescription = "章节目录", tint = colors.text)
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 2.dp)) {
            Text(
                text = session.book.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append(if (editing) "编辑模式" else (chapter?.title ?: "未分章"))
                    val status = when (saveStatus) {
                        SaveStatus.SAVING -> "保存中…"
                        SaveStatus.SAVED -> "已保存"
                        SaveStatus.ERROR -> "保存失败"
                        SaveStatus.IDLE -> null
                    }
                    if (status != null) append(" · ").append(status)
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (editing) {
            TextButton(onClick = onUndo, enabled = canUndo) {
                Text(
                    "撤销",
                    color = if (canUndo) colors.text else colors.marker
                )
            }
            TextButton(onClick = onInsertImage) { Text("插图", color = colors.accent) }
        }
        IconButton(onClick = onStyle) {
            Icon(Icons.Default.Settings, contentDescription = "设置", tint = colors.text)
        }
    }
}

@Composable
private fun EditTitleHeader(
    title: String,
    settings: AppSettings,
    colors: ReadingColors,
    onTitleChange: (String) -> Unit,
    onInsert: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        BasicTextField(
            value = title,
            onValueChange = onTitleChange,
            modifier = Modifier.weight(1f).padding(top = 4.dp, end = 4.dp, bottom = 4.dp),
            textStyle = TextStyle(
                fontSize = (settings.fontSizeSp * 1.3f).sp,
                fontWeight = FontWeight.Bold,
                color = colors.text,
                fontFamily = when (settings.fontFamily) {
                    "serif" -> FontFamily.Serif
                    "monospace" -> FontFamily.Monospace
                    "sans-serif-condensed" -> FontFamily.SansSerif
                    else -> FontFamily.SansSerif
                }
            ),
            decorationBox = { inner ->
                if (title.isBlank()) {
                    Text(
                        text = "章节标题",
                        color = colors.secondaryText,
                        fontSize = (settings.fontSizeSp * 1.3f).sp,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    inner()
                }
            }
        )
        IconButton(onClick = onInsert, modifier = Modifier.padding(top = 2.dp)) {
            Icon(Icons.Default.Add, contentDescription = "新增章节", tint = colors.accent)
        }
        IconButton(onClick = onDelete, modifier = Modifier.padding(top = 2.dp)) {
            Icon(Icons.Default.Close, contentDescription = "删除章节", tint = colors.secondaryText)
        }
    }
}

@Composable
private fun ChapterDrawer(
    session: ReaderSession,
    chapters: List<Chapter>,
    currentChapter: Chapter?,
    editing: Boolean,
    colors: ReadingColors,
    onJump: (Int) -> Unit,
    onAutoChapter: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "章节目录",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.text,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${chapters.size} 章",
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondaryText
            )
        }
        HorizontalDivider(color = colors.marker.copy(alpha = 0.25f))
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(chapters, key = { "${it.index}_${it.startOffset}" }) { item ->
                val selected = currentChapter?.index == item.index
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (selected) colors.accent.copy(alpha = 0.14f) else Color.Transparent)
                        .clickable { onJump(item.index) }
                        .padding(horizontal = 18.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.title,
                        color = if (selected) colors.accent else colors.text,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        HorizontalDivider(color = colors.marker.copy(alpha = 0.25f))
        TextButton(
            onClick = onAutoChapter,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text("自动分章（按全文「第*章」重新切分）", color = colors.accent)
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
