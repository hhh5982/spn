package com.spn.novelmanager.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spn.novelmanager.model.AppSettings
import com.spn.novelmanager.model.OrientationMode
import com.spn.novelmanager.ui.screens.BookDetailScreen
import com.spn.novelmanager.ui.screens.BookshelfScreen
import com.spn.novelmanager.ui.screens.ProfileScreen
import com.spn.novelmanager.ui.screens.ReaderScreen
import com.spn.novelmanager.ui.screens.SettingsScreen
import com.spn.novelmanager.ui.theme.ReadingColors
import com.spn.novelmanager.ui.theme.appColorScheme
import com.spn.novelmanager.ui.theme.readingColors
import com.spn.novelmanager.viewmodel.AppViewModel
import com.spn.novelmanager.viewmodel.Screen

@Composable
fun AppRoot(viewModel: AppViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val stack by viewModel.stack.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    val colors = readingColors(settings)

    MaterialTheme(colorScheme = appColorScheme(settings, colors)) {
        SystemBarsEffect(isDark = colors.isDark, background = colors.background)
        ApplyOrientation(settings.orientation)
        val current = stack.lastOrNull() ?: Screen.Bookshelf
        val showBottomBar = current is Screen.Bookshelf || current is Screen.Settings || current is Screen.Profile

        Scaffold(
            containerColor = colors.background,
            snackbarHost = { SnackbarHost(viewModel.snackbarHostState) },
            bottomBar = {
                if (showBottomBar) {
                    BottomBar(
                        settings = settings,
                        colors = colors,
                        current = current,
                        onSelect = { viewModel.selectTab(it) }
                    )
                }
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (current) {
                    is Screen.Bookshelf -> BookshelfScreen(
                        viewModel = viewModel,
                        settings = settings,
                        books = books,
                        colors = colors
                    )
                    is Screen.Settings -> SettingsScreen(
                        viewModel = viewModel,
                        settings = settings,
                        colors = colors,
                        canGoBack = stack.size > 1,
                        onBack = { viewModel.back() }
                    )
                    is Screen.Profile -> ProfileScreen(
                        viewModel = viewModel,
                        colors = colors
                    )
                    is Screen.Detail -> BookDetailScreen(
                        viewModel = viewModel,
                        session = current.session,
                        colors = colors
                    )
                    is Screen.Reader -> ReaderScreen(
                        viewModel = viewModel,
                        session = current.session,
                        colors = colors
                    )
                }
            }
        }

        BackHandler(enabled = stack.size > 1) { viewModel.back() }
    }
}

@Composable
private fun SystemBarsEffect(isDark: Boolean, background: Color) {
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.Transparent.toArgb()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.navigationBarColor = Color.Transparent.toArgb()
            window.isNavigationBarContrastEnforced = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                window.isStatusBarContrastEnforced = false
            }
        } else {
            // API 24–28 不支持浅色导航栏图标，用与页面一致的底色兜底，避免图标不可见。
            window.navigationBarColor = background.toArgb()
        }
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !isDark
        controller.isAppearanceLightNavigationBars = !isDark
    }
}

@Composable
private fun ApplyOrientation(mode: OrientationMode) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    LaunchedEffect(mode, activity) {
        activity?.requestedOrientation = when (mode) {
            OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun BottomBar(
    settings: AppSettings,
    colors: ReadingColors,
    current: Screen,
    onSelect: (Screen) -> Unit
) {
    val itemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = colors.accent,
        selectedTextColor = colors.accent,
        indicatorColor = colors.surface,
        unselectedIconColor = colors.secondaryText,
        unselectedTextColor = colors.secondaryText
    )
    NavigationBar(containerColor = colors.background, contentColor = colors.text) {
        NavigationBarItem(
            selected = current is Screen.Bookshelf,
            onClick = { onSelect(Screen.Bookshelf) },
            icon = { Icon(Icons.Default.List, contentDescription = null) },
            label = { Text("书架") },
            colors = itemColors
        )
        NavigationBarItem(
            selected = current is Screen.Settings,
            onClick = { onSelect(Screen.Settings) },
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            label = { Text("设置") },
            colors = itemColors
        )
        NavigationBarItem(
            selected = current is Screen.Profile,
            onClick = { onSelect(Screen.Profile) },
            icon = { Icon(Icons.Default.Person, contentDescription = null) },
            label = { Text("我的") },
            colors = itemColors
        )
    }
}
