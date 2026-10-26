package app.filemanagerx.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.filemanagerx.R

val FaFamily = FontFamily(Font(R.font.fa_solid_900, FontWeight.Black))

/** Draws a Font Awesome glyph from [Fa] inside a square box of [size], so icons line up in rows. */
@Composable
fun FaIcon(
    glyph: String,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color = LocalContentColor.current,
) {
    val fontSize = with(LocalDensity.current) { (size * 0.8f).toSp() }
    val style = remember(fontSize) {
        TextStyle(fontFamily = FaFamily, fontWeight = FontWeight.Black, fontSize = fontSize, textAlign = TextAlign.Center)
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Text(text = glyph, style = style, color = tint, maxLines = 1, softWrap = false)
    }
}
