package app.filemanagerx.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import app.filemanagerx.domain.model.FileKind

/*
 * Fallback palette for devices without dynamic color (seed #4361EE): vibrant accents on the tonal
 * spot neutrals, generated with the Material Color Utilities and then fixed here as constants.
 */

internal val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF2048E5),
    onPrimary = Color(0xFFF2F1FF),
    primaryContainer = Color(0xFFDCE0FF),
    onPrimaryContainer = Color(0xFF00176B),
    secondary = Color(0xFF534DB6),
    onSecondary = Color(0xFFF5F1FF),
    secondaryContainer = Color(0xFFD0CDFF),
    onSecondaryContainer = Color(0xFF3E37A0),
    tertiary = Color(0xFF923881),
    onTertiary = Color(0xFFFFEEF6),
    tertiaryContainer = Color(0xFFFFD6F2),
    onTertiaryContainer = Color(0xFF5D0352),
    background = Color(0xFFFBF8FE),
    onBackground = Color(0xFF31323B),
    surface = Color(0xFFFBF8FE),
    onSurface = Color(0xFF31323B),
    surfaceVariant = Color(0xFFE2E1ED),
    onSurfaceVariant = Color(0xFF5E5E68),
    outline = Color(0xFF7A7A84),
    outlineVariant = Color(0xFFB1B1BC),
    error = Color(0xFFA8364B),
    onError = Color(0xFFFFF7F7),
    errorContainer = Color(0xFFFFD9DD),
    onErrorContainer = Color(0xFF6E0523),
    inverseSurface = Color(0xFF0D0E12),
    inverseOnSurface = Color(0xFF9D9CA2),
    inversePrimary = Color(0xFFBEC6FD),
    surfaceDim = Color(0xFFDAD9E4),
    surfaceBright = Color(0xFFFBF8FE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F2FB),
    surfaceContainer = Color(0xFFEFEDF6),
    surfaceContainerHigh = Color(0xFFE9E7F1),
    surfaceContainerHighest = Color(0xFFE2E1ED),
)

internal val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF99A9FF),
    onPrimary = Color(0xFF001F88),
    primaryContainer = Color(0xFF2A3AA3),
    onPrimaryContainer = Color(0xFFDCE0FF),
    secondary = Color(0xFF9691FE),
    onSecondary = Color(0xFF140076),
    secondaryContainer = Color(0xFF3D37A0),
    onSecondaryContainer = Color(0xFFCFCBFF),
    tertiary = Color(0xFFFF9EE6),
    onTertiary = Color(0xFF6A125D),
    tertiaryContainer = Color(0xFF7A2A6C),
    onTertiaryContainer = Color(0xFFFFD6F2),
    background = Color(0xFF0D0E12),
    onBackground = Color(0xFFE5E4F0),
    surface = Color(0xFF0D0E12),
    onSurface = Color(0xFFE5E4F0),
    surfaceVariant = Color(0xFF24252E),
    onSurfaceVariant = Color(0xFFAAAAB5),
    outline = Color(0xFF74757F),
    outlineVariant = Color(0xFF474750),
    error = Color(0xFFF97386),
    onError = Color(0xFF490013),
    errorContainer = Color(0xFF871C34),
    onErrorContainer = Color(0xFFFF97A3),
    inverseSurface = Color(0xFFFBF8FE),
    inverseOnSurface = Color(0xFF55545A),
    inversePrimary = Color(0xFF575D82),
    surfaceDim = Color(0xFF0D0E12),
    surfaceBright = Color(0xFF2B2C34),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF121318),
    surfaceContainer = Color(0xFF181920),
    surfaceContainerHigh = Color(0xFF1E1F26),
    surfaceContainerHighest = Color(0xFF24252E),
)

/** Pure black surfaces for OLED panels. */
internal fun ColorScheme.asAmoled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0A0A0D),
    surfaceContainer = Color(0xFF101014),
    surfaceContainerHigh = Color(0xFF16161B),
    surfaceContainerHighest = Color(0xFF1D1D23),
)

/** Accent hue per file kind; used for the icon tile of a row. Chosen to work on light and dark. */
data class KindColors(val container: Color, val content: Color)

fun kindColors(kind: FileKind, dark: Boolean): KindColors {
    val hue = when (kind) {
        FileKind.Folder -> return KindColors(Color.Transparent, Color.Transparent) // handled by theme colors
        FileKind.Image -> 0xFF1FA67A
        FileKind.Video -> 0xFFE0517A
        FileKind.Audio -> 0xFF9B59E6
        FileKind.Archive -> 0xFFE8920C
        FileKind.Document -> 0xFF3B82F6
        FileKind.Text -> 0xFF7C8798
        FileKind.Code -> 0xFF14A3C7
        FileKind.Apk -> 0xFF4CAF50
        FileKind.Other -> 0xFF8A8F9C
    }
    val base = Color(hue)
    return if (dark) {
        KindColors(base.copy(alpha = 0.18f), base.lighten(0.25f))
    } else {
        KindColors(base.copy(alpha = 0.14f), base.darken(0.12f))
    }
}

private fun Color.lighten(amount: Float) = Color(
    red = red + (1f - red) * amount,
    green = green + (1f - green) * amount,
    blue = blue + (1f - blue) * amount,
    alpha = alpha,
)

private fun Color.darken(amount: Float) = Color(
    red = red * (1f - amount),
    green = green * (1f - amount),
    blue = blue * (1f - amount),
    alpha = alpha,
)
