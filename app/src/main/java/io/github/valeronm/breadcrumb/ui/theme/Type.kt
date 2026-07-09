package io.github.valeronm.breadcrumb.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import io.github.valeronm.breadcrumb.R

// Google Sans (the official Google Fonts release), bundled as a single variable font with a
// wght 400–700 axis. Each weight the app uses is registered against the same file with the
// matching variation setting so the renderer picks the true weight instead of synthesizing it.
@OptIn(ExperimentalTextApi::class)
private fun googleSans(weight: FontWeight) = Font(
    R.font.google_sans,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val GoogleSans = FontFamily(
    googleSans(FontWeight.Normal),
    googleSans(FontWeight.Medium),
    googleSans(FontWeight.SemiBold),
    googleSans(FontWeight.Bold),
)

// The default Material 3 scale with the family swapped in. Google Sans has a smaller x-height
// than Roboto, so the same sp reads smaller — sizes are scaled up slightly to compensate.
private const val SIZE_SCALE = 1.06f

private fun TextStyle.withGoogleSans() = copy(
    fontFamily = GoogleSans,
    fontSize = fontSize * SIZE_SCALE,
)

private val defaults = Typography()
val AppTypography = Typography(
    displayLarge = defaults.displayLarge.withGoogleSans(),
    displayMedium = defaults.displayMedium.withGoogleSans(),
    displaySmall = defaults.displaySmall.withGoogleSans(),
    headlineLarge = defaults.headlineLarge.withGoogleSans(),
    headlineMedium = defaults.headlineMedium.withGoogleSans(),
    headlineSmall = defaults.headlineSmall.withGoogleSans(),
    titleLarge = defaults.titleLarge.withGoogleSans(),
    titleMedium = defaults.titleMedium.withGoogleSans(),
    titleSmall = defaults.titleSmall.withGoogleSans(),
    bodyLarge = defaults.bodyLarge.withGoogleSans(),
    bodyMedium = defaults.bodyMedium.withGoogleSans(),
    bodySmall = defaults.bodySmall.withGoogleSans(),
    labelLarge = defaults.labelLarge.withGoogleSans(),
    labelMedium = defaults.labelMedium.withGoogleSans(),
    labelSmall = defaults.labelSmall.withGoogleSans(),
)
