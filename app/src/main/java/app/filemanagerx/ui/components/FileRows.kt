package app.filemanagerx.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.filemanagerx.domain.model.FileItem
import app.filemanagerx.domain.model.FileKind

@Composable
private fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    val container = animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        label = "selection",
    )
    Box(
        modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(container.value),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) FaIcon(Fa.Check, size = 14.dp, tint = MaterialTheme.colorScheme.onPrimary)
    }
}

private fun FileItem.subtitle(sizeText: String): String = buildString {
    if (!isDirectory) append(sizeText)
    val date = formatDate(modifiedAt)
    if (date.isNotEmpty()) {
        if (isNotEmpty()) append("  ·  ")
        append(date)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileListRow(
    item: FileItem,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    parentHint: String? = null,
    showThumbnail: Boolean = true,
) {
    val background = animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f) else Color.Transparent,
        label = "row",
    )
    val thumbnail = rememberThumbnail(item, 44.dp, showThumbnail)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(background.value)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        KindTile(item.kind, isLink = item.isSymlink && item.kind != FileKind.Folder, thumbnail = thumbnail)
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = item.subtitle(formatSize(item.size))
            Text(
                parentHint ?: sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selectionMode) SelectionMark(selected)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGridCell(
    item: FileItem,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    showThumbnail: Boolean = true,
) {
    val background = animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f) else Color.Transparent,
        label = "cell",
    )
    val thumbnail = rememberThumbnail(item, 60.dp, showThumbnail)
    Box(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(background.value)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(10.dp),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            KindTile(item.kind, size = 60.dp, isLink = item.isSymlink && item.kind != FileKind.Folder, thumbnail = thumbnail)
            Text(
                item.name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (selectionMode) SelectionMark(selected, Modifier.align(Alignment.TopEnd))
    }
}
