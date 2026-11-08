package com.spn.novelmanager.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.spn.novelmanager.data.SettingsRepository
import com.spn.novelmanager.model.AppSettings
import com.spn.novelmanager.model.OrientationMode
import com.spn.novelmanager.model.ReadingTheme
import com.spn.novelmanager.ui.components.SectionCard
import com.spn.novelmanager.ui.components.SettingRow
import com.spn.novelmanager.ui.theme.ReadingColors
import com.spn.novelmanager.ui.theme.readingColors
import com.spn.novelmanager.ui.theme.themeDisplayName
import com.spn.novelmanager.viewmodel.AppViewModel
import com.spn.novelmanager.viewmodel.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    settings: AppSettings,
    colors: ReadingColors,
    canGoBack: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = viewModel.settingsRepository

    var fontMenu by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                            it.write(repository.exportJson())
                        } ?: error("无法写入文件")
                    }.isSuccess
                }
                viewModel.postMessage(if (ok) "设置已导出" else "导出失败：无法写入文件")
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val raw = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                            ?: error("无法读取文件")
                    }
                }
                val text = raw.getOrNull()
                if (text == null) {
                    viewModel.postMessage("导入失败：无法读取文件，原设置保持不变")
                    return@launch
                }
                val result = withContext(Dispatchers.IO) { repository.importJson(text) }
                viewModel.postMessage(
                    result.fold(
                        onSuccess = { "设置已导入" },
                        onFailure = { "导入失败：${it.message ?: "文件格式不正确"}，原设置保持不变" }
                    )
                )
            }
        }
    }

    var startDraft by remember { mutableStateOf(settings.chapterStartMark) }
    var endDraft by remember { mutableStateOf(settings.chapterEndMark) }
    var prefixDraft by remember { mutableStateOf(settings.autoChapterPrefix) }
    var suffixDraft by remember { mutableStateOf(settings.autoChapterSuffix) }
    var minDraft by remember { mutableStateOf(settings.autoChapterInnerMinLen.toString()) }
    var maxDraft by remember { mutableStateOf(settings.autoChapterInnerMaxLen?.toString() ?: "") }

    LaunchedEffect(settings.chapterStartMark) {
        if (startDraft != settings.chapterStartMark) startDraft = settings.chapterStartMark
    }
    LaunchedEffect(settings.chapterEndMark) {
        if (endDraft != settings.chapterEndMark) endDraft = settings.chapterEndMark
    }
    LaunchedEffect(settings.autoChapterPrefix) {
        if (prefixDraft != settings.autoChapterPrefix) prefixDraft = settings.autoChapterPrefix
    }
    LaunchedEffect(settings.autoChapterSuffix) {
        if (suffixDraft != settings.autoChapterSuffix) suffixDraft = settings.autoChapterSuffix
    }
    LaunchedEffect(settings.autoChapterInnerMinLen) {
        val text = settings.autoChapterInnerMinLen.toString()
        if (minDraft != text) minDraft = text
    }
    LaunchedEffect(settings.autoChapterInnerMaxLen) {
        val text = settings.autoChapterInnerMaxLen?.toString() ?: ""
        if (maxDraft != text) maxDraft = text
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (canGoBack) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "返回阅读页")
                }
            }
            Text(
                text = "设置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.text,
                modifier = Modifier.weight(1f).padding(start = if (canGoBack) 0.dp else 12.dp)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionCard(title = "文字设置", colors = colors) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "字体",
                        color = colors.text,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Box {
                        TextButton(onClick = { fontMenu = true }) {
                            Text(
                                SettingsRepository.FONT_OPTIONS.firstOrNull { it.first == settings.fontFamily }
                                    ?.second ?: settings.fontFamily,
                                color = colors.accent
                            )
                        }
                        DropdownMenu(expanded = fontMenu, onDismissRequest = { fontMenu = false }) {
                            SettingsRepository.FONT_OPTIONS.forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        fontMenu = false
                                        repository.update { it.copy(fontFamily = value) }
                                    }
                                )
                            }
                        }
                    }
                }

                Text(
                    text = "字号：${settings.fontSizeSp.roundToInt()} sp",
                    color = colors.text,
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = settings.fontSizeSp,
                    onValueChange = { value -> repository.update { it.copy(fontSizeSp = value) } },
                    valueRange = 12f..34f,
                    steps = 21
                )

                Text(
                    text = "文字颜色",
                    color = colors.text,
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TEXT_COLORS.forEach { (argb, label) ->
                        val selected = settings.textColor == argb
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(Color(argb))
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) colors.accent else colors.marker,
                                    shape = CircleShape
                                )
                                .clickable {
                                    repository.update { it.copy(textColor = argb) }
                                }
                        )
                    }
                    TextButton(
                        onClick = { repository.update { it.copy(textColor = null) } }
                    ) {
                        Text(
                            if (settings.textColor == null) "跟随主题 ✓" else "跟随主题",
                            color = colors.accent
                        )
                    }
                }

                SettingRow(label = "文字加粗", colors = colors) {
                    Switch(
                        checked = settings.bold,
                        onCheckedChange = { checked -> repository.update { it.copy(bold = checked) } }
                    )
                }
                SettingRow(label = "正文下划线", colors = colors) {
                    Switch(
                        checked = settings.underline,
                        onCheckedChange = { checked -> repository.update { it.copy(underline = checked) } }
                    )
                }
            }

            SectionCard(title = "背景主题", colors = colors) {
                ReadingTheme.entries.forEach { theme ->
                    val preview = readingColors(settings.copy(theme = theme))
                    val selected = settings.theme == theme
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CircleShape)
                            .clickable { repository.update { it.copy(theme = theme) } }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = { repository.update { it.copy(theme = theme) } }
                        )
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(preview.background)
                                .border(1.dp, preview.marker, CircleShape)
                        )
                        Text(
                            text = themeDisplayName(theme),
                            color = colors.text,
                            modifier = Modifier.padding(start = 10.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            SectionCard(title = "阅读与显示", colors = colors) {
                Text(
                    text = "阅读方向（切换后立即生效并保留阅读位置）",
                    color = colors.text,
                    style = MaterialTheme.typography.bodyMedium
                )
                OrientationMode.entries.forEach { mode ->
                    val label = when (mode) {
                        OrientationMode.PORTRAIT -> "竖屏（默认）"
                        OrientationMode.LANDSCAPE -> "横屏"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CircleShape)
                            .clickable { repository.update { it.copy(orientation = mode) } },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = settings.orientation == mode,
                            onClick = { repository.update { it.copy(orientation = mode) } }
                        )
                        Text(label, color = colors.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "行距：${String.format("%.2f", settings.lineSpacing)}",
                    color = colors.text,
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = settings.lineSpacing,
                    onValueChange = { value -> repository.update { it.copy(lineSpacing = value) } },
                    valueRange = 1.0f..3.0f,
                    steps = 19
                )

                Text(
                    text = "编辑撤销步数：${settings.undoSteps} 步（5–100）",
                    color = colors.text,
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = settings.undoSteps.toFloat(),
                    onValueChange = { value ->
                        repository.update { it.copy(undoSteps = value.roundToInt().coerceIn(5, 100)) }
                    },
                    valueRange = 5f..100f,
                    steps = 94
                )
                Text(
                    text = "撤销栈按书保存在应用私有目录，关闭小说或重启 App 后仍然保留。",
                    color = colors.secondaryText,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            SectionCard(title = "章节标记", colors = colors) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startDraft,
                        onValueChange = { value ->
                            startDraft = value
                            tryPersistMarks(repository, value, endDraft)
                        },
                        label = { Text("起始标记") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = endDraft,
                        onValueChange = { value ->
                            endDraft = value
                            tryPersistMarks(repository, startDraft, value)
                        },
                        label = { Text("结束标记") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (marksInvalid(startDraft, endDraft)) {
                    Text(
                        text = "标记不能为空，且两个标记不能相同",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        text = "建议不要填写正文中高频出现的普通字符",
                        color = colors.secondaryText,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            SectionCard(title = "自动分章", colors = colors) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = prefixDraft,
                        onValueChange = { value ->
                            prefixDraft = value
                            repository.update { it.copy(autoChapterPrefix = value) }
                        },
                        label = { Text("前置文字") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = suffixDraft,
                        onValueChange = { value ->
                            suffixDraft = value
                            repository.update { it.copy(autoChapterSuffix = value) }
                        },
                        label = { Text("后置文字") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minDraft,
                        onValueChange = { value ->
                            minDraft = value.filter { it.isDigit() }.take(4)
                            val min = minDraft.toIntOrNull()
                            if (min != null) {
                                val max = settings.autoChapterInnerMaxLen
                                if (max == null || min <= max) {
                                    repository.update { it.copy(autoChapterInnerMinLen = min) }
                                }
                            }
                        },
                        label = { Text("中间内容最小长度") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = minDraft.toIntOrNull() == null || (
                            settings.autoChapterInnerMaxLen != null &&
                                (minDraft.toIntOrNull() ?: 0) > settings.autoChapterInnerMaxLen!!
                            ),
                        modifier = Modifier.weight(1f)
                    )
                    if (settings.autoChapterInnerMaxLen != null) {
                        OutlinedTextField(
                            value = maxDraft,
                            onValueChange = { value ->
                                maxDraft = value.filter { it.isDigit() }.take(4)
                                val max = maxDraft.toIntOrNull()
                                if (max != null && max >= settings.autoChapterInnerMinLen) {
                                    repository.update { it.copy(autoChapterInnerMaxLen = max) }
                                }
                            },
                            label = { Text("最大长度") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            isError = maxDraft.toIntOrNull() == null ||
                                (maxDraft.toIntOrNull() ?: 0) < settings.autoChapterInnerMinLen,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }

                SettingRow(label = "中间内容不限长度", colors = colors) {
                    Switch(
                        checked = settings.autoChapterInnerMaxLen == null,
                        onCheckedChange = { unlimited ->
                            if (unlimited) {
                                maxDraft = ""
                                repository.update { it.copy(autoChapterInnerMaxLen = null) }
                            } else {
                                val min = settings.autoChapterInnerMinLen
                                val candidate = maxDraft.toIntOrNull()?.coerceAtLeast(min) ?: (min + 10)
                                maxDraft = candidate.toString()
                                repository.update { it.copy(autoChapterInnerMaxLen = candidate) }
                            }
                        }
                    )
                }

                SettingRow(label = "执行前展示识别结果预览", colors = colors) {
                    Switch(
                        checked = settings.autoChapterPreview,
                        onCheckedChange = { checked -> repository.update { it.copy(autoChapterPreview = checked) } }
                    )
                }
                Text(
                    text = "识别公式：${settings.autoChapterPrefix}[长度 ${settings.autoChapterInnerMinLen}" +
                        "–${settings.autoChapterInnerMaxLen?.toString() ?: "不限"}]${settings.autoChapterSuffix}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.secondaryText
                )
            }

            SectionCard(title = "备份设置", colors = colors) {
                SettingRow(label = "打开小说自动备份", colors = colors) {
                    Switch(
                        checked = settings.autoBackupOnOpen,
                        onCheckedChange = { checked -> repository.update { it.copy(autoBackupOnOpen = checked) } }
                    )
                }
                Text(
                    text = "最大保留份数：${settings.maxBackupCount}",
                    color = colors.text,
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = settings.maxBackupCount.toFloat(),
                    onValueChange = { value ->
                        repository.update { it.copy(maxBackupCount = value.roundToInt()) }
                    },
                    valueRange = 1f..20f,
                    steps = 18
                )
                Text(
                    text = "备份为纯文本副本，保存在本书目录的 备份/ 下，文件名格式为「书名_日期_时间.txt」。",
                    color = colors.secondaryText,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            SectionCard(title = "阅读交互", colors = colors) {
                Text(
                    text = "固定手势：长按复制当前段落；双击 @引用名@ 跳转对应小说。",
                    color = colors.secondaryText,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "自动保存防抖：${String.format("%.1f", settings.autosaveDebounceMs / 1000f)} 秒",
                    color = colors.text,
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = settings.autosaveDebounceMs / 1000f,
                    onValueChange = { value ->
                        repository.update {
                            it.copy(autosaveDebounceMs = (value * 1000f).roundToLong())
                        }
                    },
                    valueRange = 0.5f..5f,
                    steps = 44
                )
            }

            SectionCard(title = "设置导入导出", colors = colors) {
                Text(
                    text = "导出为 JSON（含版本号与全部设置字段）；导入会先完整校验，全部合法才应用。",
                    color = colors.secondaryText,
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exportLauncher.launch("novel-settings.json") }) {
                        Text("导出设置", color = colors.accent)
                    }
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
                    ) {
                        Text("导入设置", color = colors.accent)
                    }
                }
            }

            SectionCard(title = "其他", colors = colors) {
                TextButton(onClick = { showResetDialog = true }) {
                    Text("重置全部设置", color = MaterialTheme.colorScheme.error)
                }
                HorizontalDivider(color = colors.marker.copy(alpha = 0.2f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.selectTab(Screen.Profile) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "关于",
                        color = colors.text,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text("前往「我的」", color = colors.secondaryText, style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("重置全部设置") },
            text = { Text("将恢复所有设置为默认值。书架中的书籍不会被删除。是否继续？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        repository.resetAll()
                        viewModel.postMessage("已恢复默认设置")
                    }
                ) { Text("确认重置", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("取消") }
            }
        )
    }
}

private fun marksInvalid(start: String, end: String): Boolean {
    val s = start.trim()
    val e = end.trim()
    return s.isEmpty() || e.isEmpty() || s == e
}

private fun tryPersistMarks(repository: SettingsRepository, start: String, end: String) {
    val s = start.trim()
    val e = end.trim()
    if (s.isNotEmpty() && e.isNotEmpty() && s != e) {
        repository.setChapterMarks(s, e)
    }
}

private val TEXT_COLORS = listOf(
    0xFF222222.toInt() to "墨黑",
    0xFF555555.toInt() to "深灰",
    0xFF5A4632.toInt() to "棕褐",
    0xFF1F4E79.toInt() to "藏蓝",
    0xFF2E6B4F.toInt() to "墨绿",
    0xFFB03A2E.toInt() to "朱红",
    0xFFFFFFFF.toInt() to "纯白"
)
