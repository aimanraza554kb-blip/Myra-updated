package com.myra.assistant.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val MyraColorScheme = darkColorScheme(
    primary = MyraRed,
    secondary = MyraPurple,
    tertiary = MyraPurpleDeep,
    background = MyraBlack,
    surface = MyraSurface,
    onPrimary = MyraTextPrimary,
    onSecondary = MyraTextPrimary,
    onBackground = MyraTextPrimary,
    onSurface = MyraTextPrimary
)

@Composable
fun MyraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = MyraBlack.value.toInt()
            window.navigationBarColor = MyraBlack.value.toInt()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }
    MaterialTheme(
        colorScheme = MyraColorScheme,
        typography = MyraTypography,
        content = content
    )
}
