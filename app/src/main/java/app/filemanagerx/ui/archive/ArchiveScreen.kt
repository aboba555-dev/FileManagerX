@file:OptIn(ExperimentalLayoutApi::class)

package app.filemanagerx.ui.archive

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.filemanagerx.R
import app.filemanagerx.core.UiText
import app.filemanagerx.core.asString
import app.filemanagerx.domain.model.ConflictPolicy
import app.filemanagerx.ui.components.ConfirmDialog
import app.filemanagerx.ui.components.EmptyState
import app.filemanagerx.ui.components.Fa
import app.filemanagerx.ui.components.FaIcon
import app.filemanagerx.ui.components.FileListRow
import app.filemanagerx.ui.components.FolderPickerDialog
import app.filemanagerx.ui.components.IconAction
import app.filemanagerx.ui.components.PasswordDialog
import app.filemanagerx.ui.components.TaskOverlay
import app.filemanagerx.ui.components.formatSize

@Composable
fun ArchiveScreen(vm: ArchiveViewModel, onExit: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val passwordPrompt by vm.passwordPrompt.collectAsStateWithLifecycle()
    val extractDialog by vm.extractDialog.collectAsStateWithLifecycle()
    val deleteDialog by vm.deleteDialog.collectAsStateWithLifecycle()
    val infoVisible by vm.infoVisible.collectAsStateWithLifecycle()
    val context = LocalContext.current

    BackHandler { if (!vm.up()) onExit() }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is ArchiveEvent.Launch -> try {
                    context.startActivity(event.intent)
                } catch (_: ActivityNotFoundException) {
                    // No app can open this entry; nothing else to do.
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize()) {
            ArchiveHeader(state, vm, onExit)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    state.error != null && state.rows.isEmpty() -> ErrorView(state.error!!, vm)
                    state.rows.isEmpty() -> EmptyState(Fa.BoxOpen, stringResource(R.string.archive_folder_empty), modifier = Modifier.padding(top = 64.dp))
                    else -> LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 160.dp)) {
                        items(state.rows, key = { it.path }) { item ->
                            FileListRow(
                                item = item,
                                selected = item.path in state.selection,
                                selectionMode = state.selectionMode,
                                onClick = { vm.onRowClick(item) },
                                onLongClick = { vm.onRowLongClick(item) },
                                showThumbnail = false,
                            )
                        }
                    }
                }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter)) {
            TaskOverlay(tasks, vm::cancelTask)
            if (!state.loading && state.format != null) {
                Row(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(16.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    ExtendedFloatingActionButton(
                        onClick = { vm.requestExtract(onlySelection = state.selectionMode) },
                        icon = { FaIcon(Fa.Download, size = 20.dp) },
                        text = {
                            Text(
                                stringResource(if (state.selectionMode) R.string.action_extract_selected else R.string.action_extract_all),
                            )
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = RoundedCornerShape(22.dp),
                    )
                }
            }
        }
    }

    passwordPrompt?.let { prompt ->
        PasswordDialog(
            title = stringResource(R.string.dialog_password_title),
            wrong = prompt.wrong,
            onConfirm = vm::submitPassword,
            onDismiss = vm::dismissPassword,
        )
    }
    extractDialog?.let { ExtractDialog(it, vm) }
    deleteDialog?.let { paths ->
        ConfirmDialog(
            title = stringResource(R.string.dialog_delete_entries_title),
            message = pluralStringResource(R.plurals.dialog_delete_entries_message, paths.size, paths.size),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = { vm.delete(paths) },
            onDismiss = vm::dismissDelete,
        )
    }
    if (infoVisible) InfoDialog(state, onDismiss = { vm.showInfo(false) })
}

@Composable
private fun ArchiveHeader(state: ArchiveUiState, vm: ArchiveViewModel, onExit: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.selectionMode) {
                IconAction(Fa.Xmark, stringResource(R.string.action_cancel), vm::clearSelection)
                Text(
                    pluralStringResource(R.plurals.selected_count, state.selection.size, state.selection.size),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
            } else {
                IconAction(Fa.ArrowLeft, stringResource(R.string.action_back), { if (!vm.up()) onExit() })
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(state.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val format = state.format
                    if (format != null) {
                        Text(
                            stringResource(R.string.archive_subtitle, format.displayName, state.entryCount, formatSize(state.totalSize)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (state.encrypted) FaIcon(Fa.Lock, size = 16.dp, tint = MaterialTheme.colorScheme.primary)
            }
            if (state.format != null) {
                if (!state.selectionMode) IconAction(Fa.Vial, stringResource(R.string.action_test), vm::test)
                if (state.selectionMode && state.canEdit) IconAction(Fa.Trash, stringResource(R.string.action_delete), vm::requestDelete)
                Box {
                    IconAction(Fa.Ellipsis, stringResource(R.string.action_more), { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_select_all)) },
                            leadingIcon = { FaIcon(Fa.CheckDouble, size = 18.dp) },
                            onClick = { menu = false; vm.selectAll() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_info)) },
                            leadingIcon = { FaIcon(Fa.Info, size = 18.dp) },
                            onClick = { menu = false; vm.showInfo(true) },
                        )
                    }
                }
            }
        }
        if (state.format != null) {
            Text(
                text = "/" + state.cwd,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ErrorView(error: UiText, vm: ArchiveViewModel) {
    Column(Modifier.fillMaxSize().padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyState(Fa.Warning, error.asString())
        FilledTonalButton(onClick = vm::retry) { Text(stringResource(R.string.action_retry)) }
    }
}

private enum class ExtractTarget { Here, NamedFolder, Custom }

@Composable
private fun ExtractDialog(dialog: ExtractDialogState, vm: ArchiveViewModel) {
    var target by rememberSaveable { mutableStateOf(ExtractTarget.NamedFolder) }
    var policy by rememberSaveable { mutableStateOf(ConflictPolicy.KeepBoth) }
    var customPath by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }

    val hereLabel = vm.defaultDestinationParent
    val namedLabel = vm.namedFolderDestination

    AlertDialog(
        onDismissRequest = vm::dismissExtract,
        title = { Text(stringResource(R.string.dialog_extract_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TargetOption(target == ExtractTarget.NamedFolder, R.string.extract_to_named, namedLabel) { target = ExtractTarget.NamedFolder }
                TargetOption(target == ExtractTarget.Here, R.string.extract_here, hereLabel) { target = ExtractTarget.Here }
                TargetOption(target == ExtractTarget.Custom, R.string.extract_choose, customPath ?: "…") {
                    target = ExtractTarget.Custom
                    picking = true
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.label_if_exists), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(policy == ConflictPolicy.KeepBoth, { policy = ConflictPolicy.KeepBoth }, { Text(stringResource(R.string.action_keep_both)) })
                    FilterChip(policy == ConflictPolicy.Replace, { policy = ConflictPolicy.Replace }, { Text(stringResource(R.string.action_replace)) })
                    FilterChip(policy == ConflictPolicy.Skip, { policy = ConflictPolicy.Skip }, { Text(stringResource(R.string.action_skip)) })
                }
            }
        },
        confirmButton = {
            val destination = when (target) {
                ExtractTarget.Here -> hereLabel
                ExtractTarget.NamedFolder -> namedLabel
                ExtractTarget.Custom -> customPath
            }
            TextButton(onClick = { destination?.let { vm.extract(it, dialog.selection, policy) } }, enabled = destination != null) {
                Text(stringResource(R.string.action_extract))
            }
        },
        dismissButton = { TextButton(onClick = vm::dismissExtract) { Text(stringResource(R.string.action_cancel)) } },
    )

    if (picking) {
        FolderPickerDialog(
            initialPath = customPath ?: hereLabel,
            operations = vm.operations,
            title = stringResource(R.string.extract_choose),
            confirmLabel = stringResource(R.string.action_select_this_folder),
            onPick = { customPath = it; picking = false },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun TargetOption(selected: Boolean, label: Int, path: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
            Text(
                path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun InfoDialog(state: ArchiveUiState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(state.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoLine(R.string.info_format, state.format?.displayName.orEmpty())
                InfoLine(R.string.info_files, state.entryCount.toString())
                InfoLine(R.string.info_unpacked, formatSize(state.totalSize))
                InfoLine(R.string.info_encrypted, stringResource(if (state.encrypted) R.string.yes else R.string.no))
                InfoLine(R.string.info_editable, stringResource(if (state.canEdit) R.string.yes else R.string.no))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
    )
}

@Composable
private fun InfoLine(label: Int, value: String) {
    Column {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
