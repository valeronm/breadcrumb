package io.github.valeronm.breadcrumb.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // Remembered because a ColorScheme is an identity, not just a value: components memoize their
    // derived colours on the instance, and LocalColorScheme is static, so handing down a fresh
    // instance drops every one of those and invalidates the whole tree under it. Rebuilding is for
    // when the theme or the wallpaper's colours actually change, which is what the keys say.
    val colorScheme = remember(darkTheme, context) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            // The library's own defaults are the baseline purple, which is the Material sample's
            // colour and no app's identity. Below S there is no wallpaper to read, so the app
            // states one — see SeededScheme.
            darkTheme -> SeededDarkScheme
            else -> SeededLightScheme
        }.let {
            // Light theme ships inverted by default: the scaffold canvas is near-white (background)
            // while filled cards sit on the darker surfaceContainerHighest. The platform look
            // (system Settings, Google apps) is the opposite — a dipped canvas with near-white
            // content cards — so remap those two roles. Dark already stacks the right way.
            if (darkTheme) {
                it
            } else {
                it.copy(
                    background = it.surfaceContainer,
                    surfaceContainerHighest = it.surfaceContainerLowest,
                )
            }
        }
    }
    MaterialExpressiveTheme(colorScheme = colorScheme, typography = AppTypography, content = content)
}
