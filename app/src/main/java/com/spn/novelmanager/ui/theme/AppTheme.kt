package com.spn.novelmanager.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.spn.novelmanager.model.AppSettings
import com.spn.novelmanager.model.ReadingTheme

data class ReadingColors(
    val background: Color,
    val surface: Color,
    val text: Color,
    val secondaryText: Color,
    val quote: Color,
    val marker: Color,
    val accent: Color,
    val isDark: Boolean
)

fun readingColors(settings: AppSettings): ReadingColors {
    val base = when (settings.theme) {
        ReadingTheme.LIGHT -> ReadingColors(
            background = Color(0xFFFFFFFF),
            surface = Color(0xFFF6F4EF),
            text = Color(0xFF222222),
            secondaryText = Color(0xFF707070),
            quote = Color(0xFF2F6F5E),
            marker = Color(0xFFB5AEA1),
            accent = Color(0xFF2F5D50),
            isDark = false
        )
        ReadingTheme.NIGHT -> ReadingColors(
            background = Color(0xFF121212),
            surface = Color(0xFF1D1D1D),
            text = Color(0xFFE6E6E6),
            secondaryText = Color(0xFF9A9A9A),
            quote = Color(0xFF8CCFB8),
            marker = Color(0xFF616161),
            accent = Color(0xFF8CCFB8),
            isDark = true
        )
        ReadingTheme.PARCHMENT -> ReadingColors(
            background = Color(0xFFF5ECD7),
            surface = Color(0xFFF0E3C8),
            text = Color(0xFF4A3B2A),
            secondaryText = Color(0xFF8A7357),
            quote = Color(0xFF8B5E34),
            marker = Color(0xFFB9A98A),
            accent = Color(0xFF8B5E34),
            isDark = false
        )
        ReadingTheme.EYE_CARE -> ReadingColors(
            background = Color(0xFFC7EDCC),
            surface = Color(0xFFBCE3C3),
            text = Color(0xFF223B2A),
            secondaryText = Color(0xFF527A60),
            quote = Color(0xFF2E6B4F),
            marker = Color(0xFF93B79B),
            accent = Color(0xFF2E6B4F),
            isDark = false
        )
        ReadingTheme.BLUE -> ReadingColors(
            background = Color(0xFFE9F1FA),
            surface = Color(0xFFDCE8F4),
            text = Color(0xFF22384C),
            secondaryText = Color(0xFF5B7590),
            quote = Color(0xFF2F5D8A),
            marker = Color(0xFFA9BFD3),
            accent = Color(0xFF2F5D8A),
            isDark = false
        )
    }
    return base
}

fun readerTextColor(settings: AppSettings, colors: ReadingColors): Color =
    settings.textColor?.let { Color(it) } ?: colors.text

fun appColorScheme(settings: AppSettings, colors: ReadingColors): ColorScheme {
    val colorsWithAccent = if (colors.isDark) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = Color(0xFF06271D),
            background = colors.background,
            onBackground = colors.text,
            surface = colors.surface,
            onSurface = colors.text,
            surfaceVariant = colors.surface,
            onSurfaceVariant = colors.secondaryText,
            outline = colors.marker
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = Color.White,
            background = colors.background,
            onBackground = colors.text,
            surface = colors.surface,
            onSurface = colors.text,
            surfaceVariant = colors.surface,
            onSurfaceVariant = colors.secondaryText,
            outline = colors.marker
        )
    }
    return colorsWithAccent
}

@Composable
fun themeDisplayName(theme: ReadingTheme): String = when (theme) {
    ReadingTheme.LIGHT -> "浅色默认"
    ReadingTheme.NIGHT -> "夜间模式"
    ReadingTheme.PARCHMENT -> "羊皮卷"
    ReadingTheme.EYE_CARE -> "护眼背景"
    ReadingTheme.BLUE -> "浅蓝"
}
