package com.spn.novelmanager.data

import android.content.Context
import com.spn.novelmanager.model.AppSettings
import com.spn.novelmanager.model.FlipMode
import com.spn.novelmanager.model.OrientationMode
import com.spn.novelmanager.model.ReadingTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = normalize(transform(_settings.value))
        _settings.value = next
        save(next)
    }

    /** 章节标记合法性：非空且互不相同。非法时返回 false 且不写入。 */
    @Synchronized
    fun setChapterMarks(start: String, end: String): Boolean {
        val s = start.trim()
        val e = end.trim()
        if (s.isEmpty() || e.isEmpty() || s == e) return false
        update { it.copy(chapterStartMark = s, chapterEndMark = e) }
        return true
    }

    @Synchronized
    fun resetAll() {
        prefs.edit().clear().apply()
        val defaults = AppSettings()
        _settings.value = defaults
        save(defaults)
    }

    /** 导出全部设置（含版本号）。 */
    fun exportJson(): String {
        val s = _settings.value
        return JSONObject().apply {
            put("version", EXPORT_VERSION)
            put("fontFamily", s.fontFamily)
            put("fontSizeSp", s.fontSizeSp.toDouble())
            put("textColor", s.textColor ?: JSONObject.NULL)
            put("bold", s.bold)
            put("underline", s.underline)
            put("lineSpacing", s.lineSpacing.toDouble())
            put("theme", s.theme.name)
            put("orientation", s.orientation.name)
            put("flipMode", s.flipMode.name)
            put("undoSteps", s.undoSteps)
            put("chapterStartMark", s.chapterStartMark)
            put("chapterEndMark", s.chapterEndMark)
            put("autoChapterPrefix", s.autoChapterPrefix)
            put("autoChapterSuffix", s.autoChapterSuffix)
            put("autoChapterInnerMinLen", s.autoChapterInnerMinLen)
            put("autoChapterInnerMaxLen", s.autoChapterInnerMaxLen ?: JSONObject.NULL)
            put("autoChapterPreview", s.autoChapterPreview)
            put("autoBackupOnOpen", s.autoBackupOnOpen)
            put("maxBackupCount", s.maxBackupCount)
            put("doubleTapCopyParagraph", s.doubleTapCopyParagraph)
            put("longPressQuoteJump", s.longPressQuoteJump)
            put("autosaveDebounceMs", s.autosaveDebounceMs)
        }.toString(2)
    }

    /** 导入设置：先逐项校验，全部合法才整体应用。 */
    @Synchronized
    fun importJson(raw: String): Result<Unit> = runCatching {
        val obj = JSONObject(raw)
        if (obj.optInt("version", -1) != EXPORT_VERSION) {
            throw IllegalArgumentException("设置文件版本不受支持")
        }
        fun str(key: String): String = obj.optString(key).trim().ifEmpty {
            throw IllegalArgumentException("设置项 $key 缺失或为空")
        }
        fun bool(key: String): Boolean {
            if (!obj.has(key) || obj.get(key) !is Boolean) throw IllegalArgumentException("设置项 $key 非法")
            return obj.getBoolean(key)
        }
        fun int(key: String, range: IntRange): Int {
            val value = obj.optInt(key, Int.MIN_VALUE)
            if (value == Int.MIN_VALUE || value !in range) throw IllegalArgumentException("设置项 $key 超出范围")
            return value
        }
        fun long(key: String): Long {
            if (!obj.has(key)) throw IllegalArgumentException("设置项 $key 缺失")
            return obj.getLong(key)
        }
        fun optionalInt(key: String): Int? =
            if (obj.isNull(key)) null else {
                val value = obj.optInt(key, Int.MIN_VALUE)
                if (value == Int.MIN_VALUE) throw IllegalArgumentException("设置项 $key 非法")
                value
            }
        val fontSize = obj.optDouble("fontSizeSp", Double.NaN)
        if (fontSize.isNaN() || fontSize < 12.0 || fontSize > 34.0) {
            throw IllegalArgumentException("字号超出范围")
        }
        val lineSpacing = obj.optDouble("lineSpacing", Double.NaN)
        if (lineSpacing.isNaN() || lineSpacing < 1.0 || lineSpacing > 3.0) {
            throw IllegalArgumentException("行距超出范围")
        }

        val start = str("chapterStartMark")
        val end = str("chapterEndMark")
        if (start == end) throw IllegalArgumentException("章节标记不能相同")

        val minLen = int("autoChapterInnerMinLen", 0..9999)
        val maxLen = optionalInt("autoChapterInnerMaxLen")
        if (maxLen != null && maxLen < minLen) throw IllegalArgumentException("自动分章最大长度不能小于最小长度")

        val next = AppSettings(
            fontFamily = str("fontFamily"),
            fontSizeSp = fontSize.toFloat(),
            textColor = optionalInt("textColor"),
            bold = bool("bold"),
            underline = bool("underline"),
            lineSpacing = lineSpacing.toFloat(),
            theme = enumOf(str("theme"), ReadingTheme.LIGHT, ReadingTheme.entries.map { it.name }),
            orientation = enumOf(str("orientation"), OrientationMode.PORTRAIT, OrientationMode.entries.map { it.name }),
            flipMode = enumOf(str("flipMode"), FlipMode.SCROLL, FlipMode.entries.map { it.name }),
            undoSteps = int("undoSteps", 5..100),
            chapterStartMark = start,
            chapterEndMark = end,
            autoChapterPrefix = obj.optString("autoChapterPrefix"),
            autoChapterSuffix = obj.optString("autoChapterSuffix"),
            autoChapterInnerMinLen = minLen,
            autoChapterInnerMaxLen = maxLen,
            autoChapterPreview = bool("autoChapterPreview"),
            autoBackupOnOpen = bool("autoBackupOnOpen"),
            maxBackupCount = int("maxBackupCount", 1..20),
            doubleTapCopyParagraph = bool("doubleTapCopyParagraph"),
            longPressQuoteJump = bool("longPressQuoteJump"),
            autosaveDebounceMs = long("autosaveDebounceMs")
        )
        val applied = normalize(next)
        _settings.value = applied
        save(applied)
        Unit
    }.recoverCatching { error ->
        throw IllegalArgumentException(error.message ?: "设置文件格式不正确")
    }

    private inline fun <reified T : Enum<T>> enumOf(name: String, default: T, allowed: List<String>): T =
        if (name in allowed) enumValueOf<T>(name) else default

    private fun normalize(s: AppSettings): AppSettings = s.copy(
        fontSizeSp = s.fontSizeSp.coerceIn(12f, 34f),
        lineSpacing = s.lineSpacing.coerceIn(1.0f, 3.0f),
        undoSteps = s.undoSteps.coerceIn(5, 100),
        maxBackupCount = s.maxBackupCount.coerceIn(1, 20),
        autosaveDebounceMs = autosaveDebounceMsClamp(s.autosaveDebounceMs),
        autoChapterInnerMinLen = s.autoChapterInnerMinLen.coerceAtLeast(0),
        autoChapterInnerMaxLen = s.autoChapterInnerMaxLen?.coerceAtLeast(s.autoChapterInnerMinLen)
    )

    private fun autosaveDebounceMsClamp(v: Long): Long = (((v / 100L).coerceIn(5L, 50L)) * 100L)

    private fun load(): AppSettings {
        val d = AppSettings()
        return runCatching {
            AppSettings(
                fontFamily = prefs.getString(KEY_FONT, d.fontFamily) ?: d.fontFamily,
                fontSizeSp = prefs.getFloat(KEY_FONT_SIZE, d.fontSizeSp),
                textColor = if (prefs.getBoolean(KEY_TEXT_COLOR_SET, false)) prefs.getInt(KEY_TEXT_COLOR, 0xFF222222.toInt()) else null,
                bold = prefs.getBoolean(KEY_BOLD, d.bold),
                underline = prefs.getBoolean(KEY_UNDERLINE, d.underline),
                lineSpacing = prefs.getFloat(KEY_LINE_SPACING, d.lineSpacing),
                theme = enumOrDefault(prefs.getString(KEY_THEME, d.theme.name), d.theme),
                orientation = enumOrDefault(prefs.getString(KEY_ORIENTATION, d.orientation.name), d.orientation),
                flipMode = enumOrDefault(prefs.getString(KEY_FLIP_MODE, d.flipMode.name), d.flipMode),
                undoSteps = prefs.getInt(KEY_UNDO_STEPS, d.undoSteps),
                chapterStartMark = prefs.getString(KEY_CHAPTER_START, d.chapterStartMark) ?: d.chapterStartMark,
                chapterEndMark = prefs.getString(KEY_CHAPTER_END, d.chapterEndMark) ?: d.chapterEndMark,
                autoChapterPrefix = prefs.getString(KEY_AUTO_PREFIX, d.autoChapterPrefix) ?: d.autoChapterPrefix,
                autoChapterSuffix = prefs.getString(KEY_AUTO_SUFFIX, d.autoChapterSuffix) ?: d.autoChapterSuffix,
                autoChapterInnerMinLen = prefs.getInt(KEY_AUTO_MIN, d.autoChapterInnerMinLen),
                autoChapterInnerMaxLen = if (prefs.getBoolean(KEY_AUTO_MAX_SET, false)) prefs.getInt(KEY_AUTO_MAX, 10) else null,
                autoChapterPreview = prefs.getBoolean(KEY_AUTO_PREVIEW, d.autoChapterPreview),
                autoBackupOnOpen = prefs.getBoolean(KEY_AUTO_BACKUP, d.autoBackupOnOpen),
                maxBackupCount = prefs.getInt(KEY_MAX_BACKUP, d.maxBackupCount),
                doubleTapCopyParagraph = prefs.getBoolean(KEY_DOUBLE_TAP_COPY, d.doubleTapCopyParagraph),
                longPressQuoteJump = prefs.getBoolean(KEY_LONG_PRESS_JUMP, d.longPressQuoteJump),
                autosaveDebounceMs = prefs.getLong(KEY_DEBOUNCE, d.autosaveDebounceMs)
            )
        }.getOrDefault(d)
    }

    private fun save(s: AppSettings) {
        prefs.edit()
            .putString(KEY_FONT, s.fontFamily)
            .putFloat(KEY_FONT_SIZE, s.fontSizeSp)
            .putBoolean(KEY_TEXT_COLOR_SET, s.textColor != null)
            .putInt(KEY_TEXT_COLOR, s.textColor ?: 0)
            .putBoolean(KEY_BOLD, s.bold)
            .putBoolean(KEY_UNDERLINE, s.underline)
            .putFloat(KEY_LINE_SPACING, s.lineSpacing)
            .putString(KEY_THEME, s.theme.name)
            .putString(KEY_ORIENTATION, s.orientation.name)
            .putString(KEY_FLIP_MODE, s.flipMode.name)
            .putInt(KEY_UNDO_STEPS, s.undoSteps)
            .putString(KEY_CHAPTER_START, s.chapterStartMark)
            .putString(KEY_CHAPTER_END, s.chapterEndMark)
            .putString(KEY_AUTO_PREFIX, s.autoChapterPrefix)
            .putString(KEY_AUTO_SUFFIX, s.autoChapterSuffix)
            .putInt(KEY_AUTO_MIN, s.autoChapterInnerMinLen)
            .putBoolean(KEY_AUTO_MAX_SET, s.autoChapterInnerMaxLen != null)
            .putInt(KEY_AUTO_MAX, s.autoChapterInnerMaxLen ?: 0)
            .putBoolean(KEY_AUTO_PREVIEW, s.autoChapterPreview)
            .putBoolean(KEY_AUTO_BACKUP, s.autoBackupOnOpen)
            .putInt(KEY_MAX_BACKUP, s.maxBackupCount)
            .putBoolean(KEY_DOUBLE_TAP_COPY, s.doubleTapCopyParagraph)
            .putBoolean(KEY_LONG_PRESS_JUMP, s.longPressQuoteJump)
            .putLong(KEY_DEBOUNCE, s.autosaveDebounceMs)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        runCatching { enumValueOf<T>(name ?: "") }.getOrDefault(default)

    companion object {
        const val EXPORT_VERSION = 1
        private const val PREFS_NAME = "novel_settings"
        private const val KEY_FONT = "fontFamily"
        private const val KEY_FONT_SIZE = "fontSizeSp"
        private const val KEY_TEXT_COLOR_SET = "textColorSet"
        private const val KEY_TEXT_COLOR = "textColor"
        private const val KEY_BOLD = "bold"
        private const val KEY_UNDERLINE = "underline"
        private const val KEY_LINE_SPACING = "lineSpacing"
        private const val KEY_THEME = "theme"
        private const val KEY_ORIENTATION = "orientation"
        private const val KEY_FLIP_MODE = "flipMode"
        private const val KEY_UNDO_STEPS = "undoSteps"
        private const val KEY_CHAPTER_START = "chapterStartMark"
        private const val KEY_CHAPTER_END = "chapterEndMark"
        private const val KEY_AUTO_PREFIX = "autoChapterPrefix"
        private const val KEY_AUTO_SUFFIX = "autoChapterSuffix"
        private const val KEY_AUTO_MIN = "autoChapterInnerMinLen"
        private const val KEY_AUTO_MAX = "autoChapterInnerMaxLen"
        private const val KEY_AUTO_MAX_SET = "autoChapterInnerMaxLenSet"
        private const val KEY_AUTO_PREVIEW = "autoChapterPreview"
        private const val KEY_AUTO_BACKUP = "autoBackupOnOpen"
        private const val KEY_MAX_BACKUP = "maxBackupCount"
        private const val KEY_DOUBLE_TAP_COPY = "doubleTapCopyParagraph"
        private const val KEY_LONG_PRESS_JUMP = "longPressQuoteJump"
        private const val KEY_DEBOUNCE = "autosaveDebounceMs"

        val FONT_OPTIONS = listOf(
            "sans-serif" to "系统默认（无衬线）",
            "serif" to "衬线体",
            "monospace" to "等宽体",
            "sans-serif-condensed" to "窄体"
        )
    }
}
