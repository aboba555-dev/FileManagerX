package app.filemanagerx.ui.duplicates

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.filemanagerx.R
import app.filemanagerx.core.UiText
import app.filemanagerx.data.fs.DuplicateGroup
import app.filemanagerx.data.fs.DuplicateScanProgress
import app.filemanagerx.di.AppContainer
import app.filemanagerx.domain.PathUtils
import app.filemanagerx.ui.components.ConfirmDialog
import app.filemanagerx.ui.components.EmptyState
import app.filemanagerx.ui.components.Fa
import app.filemanagerx.ui.components.IconAction
import app.filemanagerx.ui.components.SectionLabel
import app.filemanagerx.ui.components.formatSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DuplicatesState(
    val root: String,
    val scanning: Boolean = true,
    val progress: DuplicateScanProgress? = null,
    val groups: List<DuplicateGroup> = emptyList(),
    val selected: Set<String> = emptySet(),
) {
    val wasted: Long get() = groups.sumOf { it.wasted }
    val selectedBytes: Long get() = groups.sumOf { group -> group.size * group.paths.count { it in selected } }
}

class DuplicatesViewModel(private val app: AppContainer, root: String) : ViewModel() {
    private val _state = MutableStateFlow(DuplicatesState(PathUtils.normalize(root)))
    val state: StateFlow<DuplicatesState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        scan()
    }

    fun scan() {
        job?.cancel()
        _state.update { DuplicatesState(it.root) }
        job = viewModelScope.launch {
            val hidden = app.settings.settings.first().showHidden
            val groups = app.duplicates.find(_state.value.root, hidden) { progress ->
                _state.update { it.copy(progress = progress) }
            }
            _state.update { it.copy(scanning = false, groups = groups, progress = null) }
        }
    }

    fun toggle(path: String) = _state.update { state ->
        state.copy(selected = if (path in state.selected) state.selected - path else state.selected + path)
    }

    /** In every group keeps the first path (alphabetically) and marks the other copies. */
    fun selectAllButOne() = _state.update { state ->
        state.copy(selected = state.groups.flatMapTo(HashSet()) { it.paths.drop(1) })
    }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    fun deleteSelected() {
        val paths = _state.value.selected.toList()
        if (paths.isEmpty()) return
        val removed = paths.toSet()
        // A group with a single survivor is no longer a duplicate; drop it from the list right away.
        _state.update { state ->
            val groups = state.groups
                .map { it.copy(paths = it.paths.filterNot { path -> path in removed }) }
                .filter { it.paths.size > 1 }
            state.copy(groups = groups, selected = emptySet())
        }
        app.tasks.launch(UiText.Res(R.string.task_deleting)) { progress ->
            val trash = app.settings.settings.first().useTrash
            val allTrashed = app.trash.delete(paths, trash, progress)
            if (allTrashed && trash) {
                UiText.Plural(R.plurals.result_trashed, paths.size, paths.size)
            } else {
                UiText.Plural(R.plurals.result_deleted, paths.size, paths.size)
            }
        }
    }
}

@Composable
fun DuplicatesScreen(vm: DuplicatesViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }

    if (confirm) {
        ConfirmDialog(
            title = stringResource(R.string.dialog_delete_title),
            message = pluralStringResource(R.plurals.dialog_delete_message, state.selected.size, state.selected.size),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = { confirm = false; vm.deleteSelected() },
            onDismiss = { confirm = false },
        )
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Fa.ArrowLeft, stringResource(R.string.action_back), onBack)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.duplicates_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    PathUtils.name(state.root).ifEmpty { stringResource(R.string.root_title) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!state.scanning && state.groups.isNotEmpty()) {
                TextButton(onClick = vm::selectAllButOne) { Text(stringResource(R.string.duplicates_select_extra)) }
            }
        }

        if (state.scanning) {
            val phase = state.progress?.phase
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
            Text(
                stringResource(
                    when (phase) {
                        DuplicateScanProgress.Phase.Sampling -> R.string.duplicates_phase_sampling
                        DuplicateScanProgress.Phase.Hashing -> R.string.duplicates_phase_hashing
                        else -> R.string.duplicates_phase_listing
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }

        if (!state.scanning && state.groups.isEmpty()) {
            EmptyState(Fa.CircleCheck, stringResource(R.string.duplicates_none), modifier = Modifier.padding(top = 64.dp))
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (!state.scanning) {
                    item {
                        Text(
                            stringResource(R.string.duplicates_summary, formatSize(state.wasted)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                }
                items(state.groups, key = { it.paths.first() }) { group ->
                    SectionLabel(
                        stringResource(R.string.duplicates_group, group.paths.size, formatSize(group.size)),
                        Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                    group.paths.forEach { path ->
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.toggle(path) }.padding(horizontal = 12.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Checkbox(checked = path in state.selected, onCheckedChange = { vm.toggle(path) })
                            Column(Modifier.weight(1f)) {
                                Text(PathUtils.name(path), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    PathUtils.parent(path).orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (state.selected.isNotEmpty()) {
            Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.navigationBarsPadding().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.duplicates_selected, state.selected.size, formatSize(state.selectedBytes)),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = vm::clearSelection) { Text(stringResource(R.string.action_cancel)) }
                    Button(onClick = { confirm = true }) { Text(stringResource(R.string.action_delete)) }
                }
            }
        }
    }
}
