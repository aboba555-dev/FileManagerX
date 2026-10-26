package app.filemanagerx.ui.browser

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.filemanagerx.R
import app.filemanagerx.core.UiText
import app.filemanagerx.domain.PathUtils
import app.filemanagerx.domain.model.ClipMode
import app.filemanagerx.domain.model.FileItem
import app.filemanagerx.domain.model.ViewMode
import app.filemanagerx.ui.components.EmptyState
import app.filemanagerx.ui.components.Fa
import app.filemanagerx.ui.components.FaIcon
import app.filemanagerx.ui.components.FileGridCell
import app.filemanagerx.ui.components.FileListRow
import app.filemanagerx.ui.components.IconAction
import app.filemanagerx.ui.components.TaskOverlay

@Composable
fun BrowserScreen(
    vm: BrowserViewModel,
    onExit: () -> Unit,
    onOpenArchive: (String) -> Unit,
    onOpenEditor: (String) -> Unit,
    onAnalyze: (String) -> Unit,
    onFindDuplicates: (String) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val clipboard by vm.clipboard.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val properties by vm.properties.collectAsStateWithLifecycle()
    val context = LocalContext.current

    BackHandler { if (!vm.back()) onExit() }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is BrowserEvent.OpenArchive -> onOpenArchive(event.path)
                is BrowserEvent.OpenEditor -> onOpenEditor(event.path)
                is BrowserEvent.Launch -> try {
                    context.startActivity(event.intent)
                } catch (_: ActivityNotFoundException) {
                    vm.postMessage(UiText.Res(R.string.error_no_app))
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize()) {
            Header(state, settings.viewMode, settings.showHidden, state.path in settings.bookmarks, vm, onExit, onAnalyze, onFindDuplicates)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                FileContent(state, settings.viewMode, vm)
            }
        }

        Column(Modifier.align(Alignment.BottomCenter)) {
            TaskOverlay(tasks, vm::cancelTask)
            BottomBar(
                state = state,
                clipboardCount = clipboard?.paths?.size ?: 0,
                clipboardMode = clipboard?.mode,
                onPaste = vm::paste,
                onClearClipboard = vm::clearClipboard,
                onCreate = { folder -> vm.showDialog(BrowserDialog.Create(folder)) },
            )
        }
    }

    BrowserDialogs(vm, settings.sort)
    properties?.let { PropertiesSheet(it, vm.isRootActive, vm) }
}

// region Header

@Composable
private fun Header(
    state: BrowserState,
    viewMode: ViewMode,
    showHidden: Boolean,
    pinned: Boolean,
    vm: BrowserViewModel,
    onExit: () -> Unit,
    onAnalyze: (String) -> Unit,
    onFindDuplicates: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        AnimatedContent(
            targetState = when {
                state.selectionMode -> HeaderMode.Selection
                state.searchActive -> HeaderMode.Search
                else -> HeaderMode.Browse
            },
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "header",
        ) { mode ->
            when (mode) {
                HeaderMode.Browse -> BrowseHeader(state, viewMode, showHidden, pinned, vm, onExit, onAnalyze, onFindDuplicates)
                HeaderMode.Selection -> SelectionHeader(state, vm)
                HeaderMode.Search -> SearchHeader(state, vm)
            }
        }
        if (state.loading || state.searching) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
        } else {
            Spacer(Modifier.height(4.dp))
        }
    }
}

private enum class HeaderMode { Browse, Selection, Search }

@Composable
private fun BrowseHeader(
    state: BrowserState,
    viewMode: ViewMode,
    showHidden: Boolean,
    pinned: Boolean,
    vm: BrowserViewModel,
    onExit: () -> Unit,
    onAnalyze: (String) -> Unit,
    onFindDuplicates: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Fa.ArrowLeft, stringResource(R.string.action_back), { if (!vm.back()) onExit() })
            Text(
                text = PathUtils.name(state.path).ifEmpty { stringResource(R.string.root_title) },
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            IconAction(Fa.Search, stringResource(R.string.action_search), vm::startSearch)
            IconAction(
                if (viewMode == ViewMode.List) Fa.Grid else Fa.List,
                stringResource(R.string.action_view_mode),
                { vm.toggleViewMode() },
            )
            Box {
                IconAction(Fa.Ellipsis, stringResource(R.string.action_more), { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem(Fa.Sort, R.string.action_sort) { menu = false; vm.showDialog(BrowserDialog.Sort) }
                    MenuItem(if (showHidden) Fa.EyeSlash else Fa.Eye, if (showHidden) R.string.action_hide_hidden else R.string.action_show_hidden) {
                        menu = false
                        vm.toggleHidden()
                    }
                    MenuItem(Fa.FolderPlus, R.string.dialog_new_folder) { menu = false; vm.showDialog(BrowserDialog.Create(true)) }
                    MenuItem(Fa.FilePlus, R.string.dialog_new_file) { menu = false; vm.showDialog(BrowserDialog.Create(false)) }
                    MenuItem(Fa.Rotate, R.string.action_refresh) { menu = false; vm.refresh() }
                    MenuItem(Fa.Bookmark, if (pinned) R.string.action_unpin else R.string.action_pin) { menu = false; vm.toggleBookmark() }
                    MenuItem(Fa.ChartPie, R.string.action_analyze) { menu = false; onAnalyze(state.path) }
                    MenuItem(Fa.Clone, R.string.action_find_duplicates) { menu = false; onFindDuplicates(state.path) }
                    MenuItem(Fa.Info, R.string.action_properties) { menu = false; vm.showProperties() }
                }
            }
        }
        Breadcrumbs(state.path, onOpen = vm::open)
    }
}

@Composable
private fun MenuItem(glyph: String, label: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        leadingIcon = { FaIcon(glyph, size = 18.dp) },
        onClick = onClick,
    )
}

@Composable
private fun Breadcrumbs(path: String, onOpen: (String) -> Unit) {
    val crumbs = remember(path) { PathUtils.breadcrumbs(path) }
    val listState = rememberLazyListState()
    LaunchedEffect(path) { listState.scrollToItem((crumbs.size - 1).coerceAtLeast(0)) }
    LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(36.dp),
    ) {
        items(crumbs.size) { index ->
            val crumb = crumbs[index]
            val last = index == crumbs.lastIndex
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (index > 0) FaIcon(Fa.AngleRight, size = 14.dp, tint = MaterialTheme.colorScheme.outline)
                Text(
                    text = crumb.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (last) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable(enabled = !last) { onOpen(crumb.path) }
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun SelectionHeader(state: BrowserState, vm: BrowserViewModel) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconAction(Fa.Xmark, stringResource(R.string.action_cancel), vm::clearSelection)
        Text(
            text = pluralStringResource(R.plurals.selected_count, state.selection.size, state.selection.size),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        IconAction(Fa.Copy, stringResource(R.string.action_copy), { vm.copySelection(cut = false) })
        IconAction(Fa.Scissors, stringResource(R.string.action_cut), { vm.copySelection(cut = true) })
        IconAction(Fa.Trash, stringResource(R.string.action_delete), vm::requestDelete)
        Box {
            IconAction(Fa.Ellipsis, stringResource(R.string.action_more), { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MenuItem(Fa.CheckDouble, R.string.action_select_all) { menu = false; vm.selectAll() }
                MenuItem(Fa.FileZipper, R.string.action_compress) { menu = false; vm.requestCompress() }
                MenuItem(Fa.Share, R.string.action_share) { menu = false; vm.share() }
                if (state.selection.size > 1) {
                    MenuItem(Fa.PenToSquare, R.string.action_batch_rename) { menu = false; vm.requestBatchRename() }
                }
                if (state.selection.size == 1) {
                    MenuItem(Fa.Pen, R.string.action_rename) { menu = false; vm.requestRename() }
                    MenuItem(Fa.Info, R.string.action_properties) { menu = false; vm.showProperties() }
                }
            }
        }
    }
}

@Composable
private fun SearchHeader(state: BrowserState, vm: BrowserViewModel) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconAction(Fa.ArrowLeft, stringResource(R.string.action_back), vm::closeSearch)
        TextField(
            value = state.searchQuery,
            onValueChange = vm::updateQuery,
            placeholder = { Text(stringResource(R.string.search_hint, PathUtils.name(state.path).ifEmpty { "/" })) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.updateQuery(state.searchQuery) }),
            shape = RoundedCornerShape(28.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
    }
}

// endregion

// region Content

@Composable
private fun FileContent(state: BrowserState, viewMode: ViewMode, vm: BrowserViewModel) {
    val items = state.items
    when {
        state.problem != null -> ProblemView(state.problem, vm)
        state.loading && items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        items.isEmpty() -> EmptyState(
            glyph = if (state.searchActive) Fa.Search else Fa.FolderOpen,
            title = stringResource(if (state.searchActive) R.string.search_empty else R.string.folder_empty),
            modifier = Modifier.padding(top = 64.dp),
        )
        viewMode == ViewMode.List || state.searchActive -> FileList(state, items, vm)
        else -> FileGrid(state, items, vm)
    }
}

private val listBottomPadding = 160.dp

@Composable
private fun FileList(state: BrowserState, items: List<FileItem>, vm: BrowserViewModel) {
    val listState = androidx.compose.runtime.key(state.path, state.searchActive) { rememberLazyListState() }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = 4.dp, bottom = listBottomPadding),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.path }) { item ->
            FileListRow(
                item = item,
                selected = item.path in state.selection,
                selectionMode = state.selectionMode,
                onClick = { vm.onItemClick(item) },
                onLongClick = { vm.onItemLongClick(item) },
                parentHint = if (state.searchActive) PathUtils.parent(item.path) else null,
            )
        }
    }
}

@Composable
private fun FileGrid(state: BrowserState, items: List<FileItem>, vm: BrowserViewModel) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = listBottomPadding),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.path }) { item ->
            FileGridCell(
                item = item,
                selected = item.path in state.selection,
                selectionMode = state.selectionMode,
                onClick = { vm.onItemClick(item) },
                onLongClick = { vm.onItemLongClick(item) },
            )
        }
    }
}

@Composable
private fun ProblemView(problem: Problem, vm: BrowserViewModel) {
    val (glyph, title, hint) = when (problem) {
        Problem.AccessDenied -> Triple(Fa.Lock, R.string.problem_denied, R.string.problem_denied_hint)
        Problem.NotFound -> Triple(Fa.FolderMinus, R.string.problem_not_found, null)
        Problem.NotADirectory -> Triple(Fa.File, R.string.problem_not_dir, null)
        Problem.Failed -> Triple(Fa.Warning, R.string.problem_failed, null)
    }
    Column(Modifier.fillMaxSize().padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyState(glyph, stringResource(title), hint?.let { stringResource(it) })
        FilledTonalButton(onClick = vm::refresh) { Text(stringResource(R.string.action_refresh)) }
    }
}

// endregion

@Composable
private fun BottomBar(
    state: BrowserState,
    clipboardCount: Int,
    clipboardMode: ClipMode?,
    onPaste: () -> Unit,
    onClearClipboard: () -> Unit,
    onCreate: (folder: Boolean) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val showPaste = clipboardCount > 0 && !state.selectionMode
    val showFab = state.writable && !state.selectionMode && !state.searchActive
    if (!showPaste && !showFab) return
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showPaste) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shadowElevation = 6.dp,
                modifier = Modifier.weight(1f),
            ) {
                Row(Modifier.padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pluralStringResource(
                            if (clipboardMode == ClipMode.Cut) R.plurals.clipboard_cut else R.plurals.clipboard_copied,
                            clipboardCount,
                            clipboardCount,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onPaste, enabled = state.writable) { Text(stringResource(R.string.action_paste)) }
                    IconAction(Fa.Xmark, stringResource(R.string.action_cancel), onClearClipboard)
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (showFab) {
            Box {
                FloatingActionButton(
                    onClick = { menu = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(22.dp),
                ) { FaIcon(Fa.Plus, size = 22.dp) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem(Fa.FolderPlus, R.string.dialog_new_folder) { menu = false; onCreate(true) }
                    MenuItem(Fa.FilePlus, R.string.dialog_new_file) { menu = false; onCreate(false) }
                }
            }
        }
    }
}
