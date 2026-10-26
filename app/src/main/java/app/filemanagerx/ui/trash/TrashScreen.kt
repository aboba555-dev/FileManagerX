package app.filemanagerx.ui.trash

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.filemanagerx.R
import app.filemanagerx.core.UiText
import app.filemanagerx.data.trash.TrashEntry
import app.filemanagerx.data.trash.TrashManager
import app.filemanagerx.di.AppContainer
import app.filemanagerx.domain.FileKinds
import app.filemanagerx.domain.model.FileItem
import app.filemanagerx.ui.components.ConfirmDialog
import app.filemanagerx.ui.components.EmptyState
import app.filemanagerx.ui.components.Fa
import app.filemanagerx.ui.components.FaIcon
import app.filemanagerx.ui.components.FileListRow
import app.filemanagerx.ui.components.IconAction
import app.filemanagerx.ui.components.containerViewModel
import app.filemanagerx.ui.components.formatDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TrashState(
    val loading: Boolean = true,
    val entries: List<TrashEntry> = emptyList(),
    val selection: Set<String> = emptySet(),
)

class TrashViewModel(private val app: AppContainer) : ViewModel() {
    private val trash: TrashManager = app.trash

    private val _state = MutableStateFlow(TrashState())
    val state: StateFlow<TrashState> = _state.asStateFlow()

    val settings = app.settings.settings

    init {
        reload()
        viewModelScope.launch { app.tasks.changes.collect { reload() } }
    }

    fun reload() {
        viewModelScope.launch {
            val entries = trash.list()
            _state.update { state ->
                val ids = entries.mapTo(HashSet()) { it.id }
                state.copy(loading = false, entries = entries, selection = state.selection.filterTo(HashSet()) { it in ids })
            }
        }
    }

    fun toggle(id: String) = _state.update {
        it.copy(selection = if (id in it.selection) it.selection - id else it.selection + id)
    }

    fun selectAll() = _state.update { state -> state.copy(selection = state.entries.mapTo(LinkedHashSet()) { it.id }) }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    private fun chosen(all: Boolean): List<TrashEntry> {
        val current = _state.value
        return if (all || current.selection.isEmpty()) current.entries else current.entries.filter { it.id in current.selection }
    }

    fun restore() {
        val entries = chosen(all = false)
        clearSelection()
        app.tasks.launch(UiText.Res(R.string.task_restoring)) { progress ->
            trash.restore(entries, progress)
            UiText.Plural(R.plurals.result_restored, entries.size, entries.size)
        }
    }

    fun deleteForever(all: Boolean) {
        val entries = chosen(all)
        clearSelection()
        app.tasks.launch(UiText.Res(R.string.task_deleting)) { progress ->
            trash.purge(entries, progress)
            UiText.Plural(R.plurals.result_deleted, entries.size, entries.size)
        }
    }
}

@Composable
fun TrashScreen(onBack: () -> Unit) {
    val vm = containerViewModel { TrashViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<Boolean?>(null) } // null = hidden, value = delete everything?
    var menu by remember { mutableStateOf(false) }

    BackHandler {
        if (state.selection.isNotEmpty()) vm.clearSelection() else onBack()
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.selection.isEmpty()) {
                IconAction(Fa.ArrowLeft, stringResource(R.string.action_back), onBack)
                Text(
                    stringResource(R.string.trash_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                if (state.entries.isNotEmpty()) {
                    Box {
                        IconAction(Fa.Ellipsis, stringResource(R.string.action_more), { menu = true })
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_empty_trash)) },
                                leadingIcon = { FaIcon(Fa.Broom, size = 18.dp) },
                                onClick = { menu = false; confirm = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_select_all)) },
                                leadingIcon = { FaIcon(Fa.CheckDouble, size = 18.dp) },
                                onClick = { menu = false; vm.selectAll() },
                            )
                        }
                    }
                }
            } else {
                IconAction(Fa.Xmark, stringResource(R.string.action_cancel), vm::clearSelection)
                Text(
                    pluralStringResource(R.plurals.selected_count, state.selection.size, state.selection.size),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                IconAction(Fa.Rotate, stringResource(R.string.action_restore), vm::restore)
                IconAction(Fa.Trash, stringResource(R.string.action_delete_forever), { confirm = false })
            }
        }
        Text(
            stringResource(R.string.trash_hint, app.filemanagerx.data.trash.TrashManager.RETENTION_DAYS.toInt()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.entries.isEmpty() -> EmptyState(Fa.Trash, stringResource(R.string.trash_empty), modifier = Modifier.padding(top = 64.dp))
            else -> LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                items(state.entries, key = { it.id }) { entry ->
                    val item = FileItem(
                        path = entry.id,
                        name = entry.name,
                        isDirectory = entry.isDirectory,
                        isSymlink = false,
                        size = entry.size,
                        modifiedAt = entry.deletedAt,
                        kind = FileKinds.of(entry.name, entry.isDirectory),
                    )
                    FileListRow(
                        item = item,
                        selected = entry.id in state.selection,
                        selectionMode = state.selection.isNotEmpty(),
                        onClick = { vm.toggle(entry.id) },
                        onLongClick = { vm.toggle(entry.id) },
                        parentHint = entry.originalFolder + "  ·  " + formatDate(entry.deletedAt),
                        showThumbnail = false,
                    )
                }
            }
        }
    }

    confirm?.let { everything ->
        ConfirmDialog(
            title = stringResource(R.string.dialog_delete_forever_title),
            message = stringResource(if (everything) R.string.dialog_empty_trash_message else R.string.dialog_delete_forever_message),
            confirmLabel = stringResource(R.string.action_delete_forever),
            destructive = true,
            onConfirm = { vm.deleteForever(everything); confirm = null },
            onDismiss = { confirm = null },
        )
    }
}
