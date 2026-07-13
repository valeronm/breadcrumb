package io.github.valeronm.breadcrumb.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }.let {
        // Light theme ships inverted by default: the scaffold canvas is near-white (background)
        // while filled cards sit on the darker surfaceContainerHighest. The platform look
        // (system Settings, Google apps) is the opposite — a dipped canvas with near-white
        // content cards — so remap those two roles. Dark already stacks the right way.
        if (darkTheme) it else it.copy(
            background = it.surfaceContainer,
            surfaceContainerHighest = it.surfaceContainerLowest,
        )
    }
    MaterialTheme(colorScheme = colorScheme, typography = AppTypography, content = content)
}
