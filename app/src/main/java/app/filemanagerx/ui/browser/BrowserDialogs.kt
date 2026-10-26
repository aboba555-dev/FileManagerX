@file:OptIn(ExperimentalLayoutApi::class)

package app.filemanagerx.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.filemanagerx.R
import app.filemanagerx.data.archive.ArchiveFormat
import app.filemanagerx.data.archive.CompressionLevel
import app.filemanagerx.domain.BatchRename
import app.filemanagerx.domain.RenameProblem
import app.filemanagerx.domain.RenameRule
import app.filemanagerx.domain.model.ClipMode
import app.filemanagerx.domain.model.FileItem
import app.filemanagerx.domain.model.ConflictPolicy
import app.filemanagerx.domain.model.SortField
import app.filemanagerx.domain.model.SortSpec
import app.filemanagerx.ui.components.ConfirmDialog
import app.filemanagerx.ui.components.TextInputDialog
import app.filemanagerx.ui.components.formatDate
import app.filemanagerx.ui.components.formatSize
import app.filemanagerx.ui.components.KindTile
import app.filemanagerx.ui.components.SectionLabel

@Composable
fun BrowserDialogs(vm: BrowserViewModel, sort: SortSpec) {
    val dialog by vm.dialog.collectAsStateWithLifecycle()
    when (val current = dialog) {
        null -> Unit
        is BrowserDialog.Create -> TextInputDialog(
            title = stringResource(if (current.folder) R.string.dialog_new_folder else R.string.dialog_new_file),
            initial = "",
            confirmLabel = stringResource(R.string.action_create),
            errorFor = { name -> if (name.isEmpty()) null else vm.nameError(name)?.let { stringResource(it) } },
            onConfirm = { vm.create(current.folder, it) },
            onDismiss = vm::dismissDialog,
            label = stringResource(R.string.label_name),
        )
        is BrowserDialog.Rename -> TextInputDialog(
            title = stringResource(R.string.dialog_rename),
            initial = current.item.name,
            confirmLabel = stringResource(R.string.action_rename),
            errorFor = { name -> vm.nameError(name, ignore = current.item.name)?.let { stringResource(it) } },
            onConfirm = { vm.rename(current.item, it) },
            onDismiss = vm::dismissDialog,
            label = stringResource(R.string.label_name),
        )
        is BrowserDialog.Delete -> ConfirmDialog(
            title = stringResource(if (current.toTrash) R.string.dialog_trash_title else R.string.dialog_delete_title),
            message = pluralStringResource(
                if (current.toTrash) R.plurals.dialog_trash_message else R.plurals.dialog_delete_message,
                current.paths.size,
                current.paths.size,
            ),
            confirmLabel = stringResource(if (current.toTrash) R.string.action_move_to_trash else R.string.action_delete),
            destructive = true,
            onConfirm = { vm.delete(current.paths) },
            onDismiss = vm::dismissDialog,
        )
        is BrowserDialog.Conflict -> ConflictDialog(
            count = current.conflicts.size,
            move = current.clip.mode == ClipMode.Cut,
            onPolicy = { vm.resolveConflict(current.clip, it) },
            onDismiss = vm::dismissDialog,
        )
        is BrowserDialog.Compress -> CompressDialog(vm, current.paths)
        is BrowserDialog.BatchRename -> BatchRenameDialog(vm, current.items)
        BrowserDialog.Sort -> SortSheet(sort, onChange = vm::setSort, onDismiss = vm::dismissDialog)
    }
}

@Composable
private fun ConflictDialog(count: Int, move: Boolean, onPolicy: (ConflictPolicy) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_conflict_title)) },
        text = {
            Text(pluralStringResource(R.plurals.dialog_conflict_message, count, count))
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { onPolicy(ConflictPolicy.Replace) }) { Text(stringResource(R.string.action_replace)) }
                TextButton(onClick = { onPolicy(ConflictPolicy.KeepBoth) }) { Text(stringResource(R.string.action_keep_both)) }
                TextButton(onClick = { onPolicy(ConflictPolicy.Skip) }) { Text(stringResource(R.string.action_skip)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortSheet(sort: SortSpec, onChange: (SortSpec) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.sort_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortField.entries.forEach { field ->
                    FilterChip(
                        selected = sort.field == field,
                        onClick = { onChange(sort.copy(field = field)) },
                        label = { Text(stringResource(sortLabel(field))) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = sort.ascending,
                    onClick = { onChange(sort.copy(ascending = true)) },
                    label = { Text(stringResource(R.string.sort_ascending)) },
                )
                FilterChip(
                    selected = !sort.ascending,
                    onClick = { onChange(sort.copy(ascending = false)) },
                    label = { Text(stringResource(R.string.sort_descending)) },
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.sort_folders_first), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = sort.foldersFirst, onCheckedChange = { onChange(sort.copy(foldersFirst = it)) })
            }
        }
    }
}

private fun sortLabel(field: SortField): Int = when (field) {
    SortField.Name -> R.string.sort_name
    SortField.Size -> R.string.sort_size
    SortField.Modified -> R.string.sort_modified
    SortField.Type -> R.string.sort_type
}

@Composable
private fun CompressDialog(vm: BrowserViewModel, paths: List<String>) {
    var format by rememberSaveable { mutableStateOf(ArchiveFormat.Zip) }
    var level by rememberSaveable { mutableStateOf(CompressionLevel.Normal) }
    var password by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf(vm.defaultArchiveName(paths, ArchiveFormat.Zip)) }
    val nameError = vm.nameError(name)?.takeIf { it != R.string.error_name_exists }

    AlertDialog(
        onDismissRequest = vm::dismissDialog,
        title = { Text(stringResource(R.string.dialog_compress_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.label_name)) },
                    singleLine = true,
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(stringResource(it)) } },
                    shape = MaterialTheme.shapes.medium,
                )
                SectionLabel(stringResource(R.string.label_format), Modifier.padding(horizontal = 0.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArchiveFormat.creatable.forEach { candidate ->
                        FilterChip(
                            selected = format == candidate,
                            onClick = {
                                val base = ArchiveFormat.entries.firstOrNull { name.endsWith(it.extension) }?.let { name.removeSuffix(it.extension) } ?: name
                                format = candidate
                                name = base + candidate.extension
                                if (!candidate.supportsPassword) password = ""
                            },
                            label = { Text(candidate.displayName) },
                        )
                    }
                }
                SectionLabel(stringResource(R.string.label_compression), Modifier.padding(horizontal = 0.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompressionLevel.entries.forEach { candidate ->
                        FilterChip(
                            selected = level == candidate,
                            onClick = { level = candidate },
                            label = { Text(stringResource(levelLabel(candidate))) },
                        )
                    }
                }
                if (format.supportsPassword) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.label_password_optional)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        supportingText = { Text(stringResource(R.string.hint_zip_aes)) },
                        shape = MaterialTheme.shapes.medium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { vm.compress(paths, name.trim(), format, level, password.toCharArray()) },
                enabled = nameError == null,
            ) { Text(stringResource(R.string.action_compress)) }
        },
        dismissButton = { TextButton(onClick = vm::dismissDialog) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun levelLabel(level: CompressionLevel): Int = when (level) {
    CompressionLevel.Store -> R.string.level_store
    CompressionLevel.Fast -> R.string.level_fast
    CompressionLevel.Normal -> R.string.level_normal
    CompressionLevel.Maximum -> R.string.level_maximum
}

private val OCTAL = Regex("^[0-7]{3,4}$")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PropertiesSheet(state: PropertiesState, rootActive: Boolean, vm: BrowserViewModel) {
    ModalBottomSheet(onDismissRequest = vm::closeProperties, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                KindTile(state.item.kind, size = 52.dp)
                Column {
                    Text(state.item.name, style = MaterialTheme.typography.titleLarge)
                    Text(state.item.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            val size = state.size
            PropertyRow(
                stringResource(R.string.prop_size),
                if (size == null) stringResource(R.string.prop_calculating)
                else formatSize(size.bytes) + " (" + size.bytes + " B)" + if (!state.sizeFinal) " …" else "",
            )
            if (state.item.isDirectory && size != null) {
                PropertyRow(stringResource(R.string.prop_contents), stringResource(R.string.prop_contents_value, size.files, size.folders))
            }
            if (state.item.modifiedAt > 0L) PropertyRow(stringResource(R.string.prop_modified), formatDate(state.item.modifiedAt))
            state.item.linkTarget?.let { PropertyRow(stringResource(R.string.prop_link_target), it) }
            state.permissions?.let { permissions ->
                PropertyRow(stringResource(R.string.prop_owner), "${permissions.owner}:${permissions.group}")
                permissions.seContext?.let { PropertyRow(stringResource(R.string.prop_selinux), it) }
                PermissionEditor(permissions.octal, state.item.isDirectory, onApply = vm::chmod)
            }
            state.mount?.let { mount ->
                PropertyRow(stringResource(R.string.prop_mount), "${mount.mountPoint} · ${mount.fileSystem} · " +
                    stringResource(if (mount.readOnly) R.string.mount_readonly else R.string.mount_readwrite))
                if (rootActive) {
                    FilledTonalButton(onClick = { vm.remount(readWrite = mount.readOnly) }) {
                        Text(stringResource(if (mount.readOnly) R.string.action_remount_rw else R.string.action_remount_ro))
                    }
                    if (mount.readOnly) {
                        OutlinedButton(onClick = { vm.remountTemporarily(TEMPORARY_RW_MINUTES) }) {
                            Text(stringResource(R.string.action_remount_rw_timed, TEMPORARY_RW_MINUTES))
                        }
                    }
                }
            }
            if (!state.item.isDirectory) {
                Spacer(Modifier.height(8.dp))
                SectionLabel(stringResource(R.string.prop_checksums), Modifier.padding(horizontal = 0.dp))
                listOf("MD5", "SHA-1", "SHA-256").forEach { algorithm ->
                    val value = state.checksums[algorithm]
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (value == null) {
                            OutlinedButton(
                                onClick = { vm.computeChecksum(algorithm) },
                                enabled = state.checksumRunning == null,
                            ) { Text(if (state.checksumRunning == algorithm) stringResource(R.string.prop_calculating) else algorithm) }
                        } else {
                            Column {
                                Text(algorithm, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(value, style = MaterialTheme.typography.bodySmall.copy(fontFamily = app.filemanagerx.ui.theme.JetBrainsMono))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PermissionEditor(current: String, isDirectory: Boolean, onApply: (String, Boolean) -> Unit) {
    var octal by rememberSaveable(current) { mutableStateOf(current) }
    var recursive by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        OutlinedTextField(
            value = octal,
            onValueChange = { octal = it.filter(Char::isDigit).take(4) },
            label = { Text(stringResource(R.string.prop_permissions)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = !OCTAL.matches(octal),
            shape = MaterialTheme.shapes.medium,
        )
        if (isDirectory) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.prop_recursive), Modifier.weight(1f))
                Switch(checked = recursive, onCheckedChange = { recursive = it })
            }
        }
        Button(onClick = { onApply(octal, recursive) }, enabled = OCTAL.matches(octal) && octal != current) {
            Text(stringResource(R.string.action_apply))
        }
    }
}

@Composable
private fun BatchRenameDialog(vm: BrowserViewModel, items: List<FileItem>) {
    var replaceMode by rememberSaveable { mutableStateOf(false) }
    var template by rememberSaveable { mutableStateOf("{name}_{n}") }
    var startText by rememberSaveable { mutableStateOf("1") }
    var padding by rememberSaveable { mutableStateOf("2") }
    var find by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    var regex by rememberSaveable { mutableStateOf(false) }
    var keepExtension by rememberSaveable { mutableStateOf(true) }

    val folderNames = remember { vm.folderNames() }
    val rule: RenameRule = if (replaceMode) {
        RenameRule.Replace(find, replacement, regex, keepExtension)
    } else {
        RenameRule.Pattern(template, startText.toIntOrNull() ?: 1, (padding.toIntOrNull() ?: 0).coerceIn(0, 9), keepExtension)
    }
    val previews = remember(items, rule, folderNames) { BatchRename.preview(items, rule, folderNames) }
    val hasProblem = previews.any { it.problem != null }
    val anyChange = previews.any { it.changed }

    AlertDialog(
        onDismissRequest = vm::dismissDialog,
        title = { Text(stringResource(R.string.dialog_batch_rename, items.size)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !replaceMode, onClick = { replaceMode = false }, label = { Text(stringResource(R.string.rename_mode_pattern)) })
                    FilterChip(selected = replaceMode, onClick = { replaceMode = true }, label = { Text(stringResource(R.string.rename_mode_replace)) })
                }
                if (replaceMode) {
                    OutlinedTextField(find, { find = it }, label = { Text(stringResource(R.string.rename_find)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(replacement, { replacement = it }, label = { Text(stringResource(R.string.rename_replace_with)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    SwitchRow(stringResource(R.string.rename_regex), regex) { regex = it }
                } else {
                    OutlinedTextField(template, { template = it }, label = { Text(stringResource(R.string.rename_template)) }, supportingText = { Text(stringResource(R.string.rename_tokens)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(startText, { startText = it.filter(Char::isDigit).take(6) }, label = { Text(stringResource(R.string.rename_start)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                        OutlinedTextField(padding, { padding = it.filter(Char::isDigit).take(1) }, label = { Text(stringResource(R.string.rename_padding)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    }
                }
                SwitchRow(stringResource(R.string.rename_keep_extension), keepExtension) { keepExtension = it }
                SectionLabel(stringResource(R.string.rename_preview))
                previews.take(PREVIEW_LIMIT).forEach { preview ->
                    Column {
                        Text(preview.item.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            preview.newName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (preview.problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                        preview.problem?.let {
                            Text(stringResource(renameProblemLabel(it)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                if (previews.size > PREVIEW_LIMIT) {
                    Text(stringResource(R.string.rename_more, previews.size - PREVIEW_LIMIT), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.applyBatchRename(previews) }, enabled = !hasProblem && anyChange) {
                Text(stringResource(R.string.action_rename))
            }
        },
        dismissButton = { TextButton(onClick = vm::dismissDialog) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private const val PREVIEW_LIMIT = 40
private const val TEMPORARY_RW_MINUTES = 10

private fun renameProblemLabel(problem: RenameProblem): Int = when (problem) {
    RenameProblem.Empty -> R.string.error_name_empty
    RenameProblem.InvalidName -> R.string.error_name_invalid
    RenameProblem.Duplicate -> R.string.rename_problem_duplicate
    RenameProblem.Exists -> R.string.error_name_exists
    RenameProblem.BadPattern -> R.string.rename_problem_pattern
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
