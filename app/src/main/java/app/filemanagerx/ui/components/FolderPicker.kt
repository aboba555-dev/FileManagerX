package app.filemanagerx.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.filemanagerx.R
import app.filemanagerx.data.fs.FileOperations
import app.filemanagerx.domain.PathUtils
import app.filemanagerx.domain.model.FileItem
import app.filemanagerx.domain.model.FileKind
import app.filemanagerx.domain.model.ListResult
import app.filemanagerx.domain.NaturalOrder

/** Full-screen chooser for a destination folder. Works in root mode too, since it lists through [FileOperations]. */
@Composable
fun FolderPickerDialog(
    initialPath: String,
    operations: FileOperations,
    title: String,
    confirmLabel: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            var path by rememberSaveable { mutableStateOf(PathUtils.normalize(initialPath)) }
            var folders by androidx.compose.runtime.remember { mutableStateOf<List<FileItem>>(emptyList()) }
            var loading by androidx.compose.runtime.remember { mutableStateOf(true) }

            LaunchedEffect(path) {
                loading = true
                folders = when (val result = operations.list(path)) {
                    is ListResult.Success -> result.items
                        .filter { it.isDirectory }
                        .sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
                    else -> emptyList()
                }
                loading = false
            }

            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconAction(Fa.Xmark, stringResource(R.string.action_cancel), onDismiss)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(title, style = MaterialTheme.typography.titleLarge)
                        Text(
                            path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    PathUtils.parent(path)?.let { parent ->
                        item(key = "..") {
                            PickerRow(label = "..", onClick = { path = parent })
                        }
                    }
                    items(folders, key = { it.path }) { folder ->
                        PickerRow(label = folder.name, onClick = { path = folder.path })
                    }
                    if (loading) {
                        item(key = "loading") {
                            Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                                CircularProgressIndicator(Modifier.height(28.dp))
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Button(
                    onClick = { onPick(path) },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Text(confirmLabel)
                }
                Spacer(Modifier.height(0.dp))
            }
        }
    }
}

@Composable
private fun PickerRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        KindTile(FileKind.Folder, size = 40.dp)
        Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
