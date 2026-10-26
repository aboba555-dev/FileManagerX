package app.filemanagerx.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import app.filemanagerx.data.fs.ThumbnailLoader
import app.filemanagerx.domain.model.FileItem

/** Provided once in [app.filemanagerx.ui.AppRoot]; `null` disables previews. */
val LocalThumbnails = compositionLocalOf<ThumbnailLoader?> { null }

/** Loads (and caches) a preview for [item]; returns `null` while loading or when there is none. */
@Composable
fun rememberThumbnail(item: FileItem, size: Dp, enabled: Boolean): ImageBitmap? {
    val loader = LocalThumbnails.current
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val state = produceState<ImageBitmap?>(initialValue = null, item.path, item.modifiedAt, item.size, sizePx, enabled, loader) {
        value = if (enabled && loader != null) loader.load(item, sizePx) else null
    }
    return state.value
}
