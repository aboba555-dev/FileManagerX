package app.filemanagerx.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.filemanagerx.R

val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
    Font(R.font.manrope_extrabold, FontWeight.ExtraBold),
)

val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
)

private fun style(weight: FontWeight, size: Int, line: Int, spacing: Float = 0f) = TextStyle(
    fontFamily = Manrope,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = spacing.sp,
)

val AppTypography = Typography(
    displayLarge = style(FontWeight.ExtraBold, 52, 58, -1.2f),
    displayMedium = style(FontWeight.ExtraBold, 42, 48, -0.8f),
    displaySmall = style(FontWeight.Bold, 34, 40, -0.4f),
    headlineLarge = style(FontWeight.ExtraBold, 30, 36, -0.4f),
    headlineMedium = style(FontWeight.Bold, 26, 32, -0.2f),
    headlineSmall = style(FontWeight.Bold, 22, 28),
    titleLarge = style(FontWeight.Bold, 20, 26),
    titleMedium = style(FontWeight.SemiBold, 16, 22, 0.1f),
    titleSmall = style(FontWeight.SemiBold, 14, 20, 0.1f),
    bodyLarge = style(FontWeight.Medium, 16, 24, 0.1f),
    bodyMedium = style(FontWeight.Medium, 14, 20, 0.1f),
    bodySmall = style(FontWeight.Normal, 12, 16, 0.2f),
    labelLarge = style(FontWeight.SemiBold, 14, 20, 0.1f),
    labelMedium = style(FontWeight.SemiBold, 12, 16, 0.4f),
    labelSmall = style(FontWeight.SemiBold, 11, 16, 0.5f),
)
