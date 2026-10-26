package app.filemanagerx.ui.browser

import android.content.Intent
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.filemanagerx.R
import app.filemanagerx.core.UiText
import app.filemanagerx.data.archive.ArchiveFormat
import app.filemanagerx.data.archive.CompressionLevel
import app.filemanagerx.data.archive.CreateArchiveRequest
import app.filemanagerx.data.settings.AppSettings
import app.filemanagerx.di.AppContainer
import app.filemanagerx.domain.FileKinds
import app.filemanagerx.domain.PathUtils
import app.filemanagerx.domain.RenamePreview
import app.filemanagerx.domain.model.ClipMode
import app.filemanagerx.domain.model.ClipboardContent
import app.filemanagerx.domain.model.ConflictPolicy
import app.filemanagerx.domain.model.FileItem
import app.filemanagerx.domain.model.FileKind
import app.filemanagerx.domain.model.ListResult
import app.filemanagerx.domain.model.MountInfo
import app.filemanagerx.domain.model.PermissionInfo
import app.filemanagerx.domain.model.SizeInfo
import app.filemanagerx.domain.model.SortSpec
import app.filemanagerx.domain.model.ViewMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Problem { AccessDenied, NotFound, NotADirectory, Failed }

data class BrowserState(
    val path: String,
    val items: List<FileItem> = emptyList(),
    val loading: Boolean = true,
    val problem: Problem? = null,
    val selection: Set<String> = emptySet(),
    val writable: Boolean = true,
    val searchActive: Boolean = false,
    val searchQuery: String = "",
    val searching: Boolean = false,
    val canGoBack: Boolean = false,
) {
    val selectionMode: Boolean get() = selection.isNotEmpty()
}

sealed interface BrowserDialog {
    data class Create(val folder: Boolean) : BrowserDialog
    data class Rename(val item: FileItem) : BrowserDialog
    data class Delete(val paths: List<String>, val toTrash: Boolean) : BrowserDialog
    data class Conflict(val clip: ClipboardContent, val conflicts: List<String>) : BrowserDialog
    data class Compress(val paths: List<String>) : BrowserDialog
    data class BatchRename(val items: List<FileItem>) : BrowserDialog
    data object Sort : BrowserDialog
}

sealed interface BrowserEvent {
    data class OpenArchive(val path: String) : BrowserEvent
    data class OpenEditor(val path: String) : BrowserEvent
    data class Launch(val intent: Intent) : BrowserEvent
}

data class PropertiesState(
    val item: FileItem,
    val size: SizeInfo? = null,
    val sizeFinal: Boolean = false,
    val permissions: PermissionInfo? = null,
    val mount: MountInfo? = null,
    val checksums: Map<String, String> = emptyMap(),
    val checksumRunning: String? = null,
)

class BrowserViewModel(private val app: AppContainer, startPath: String) : ViewModel() {
    private val ops = app.fileOperations

    private val _state = MutableStateFlow(BrowserState(path = PathUtils.normalize(startPath)))
    val state: StateFlow<BrowserState> = _state.asStateFlow()

    val settings: StateFlow<AppSettings> = app.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val clipboard: StateFlow<ClipboardContent?> = app.clipboard.content

    val tasks = app.tasks.tasks

    private val _dialog = MutableStateFlow<BrowserDialog?>(null)
    val dialog: StateFlow<BrowserDialog?> = _dialog.asStateFlow()

    private val _properties = MutableStateFlow<PropertiesState?>(null)
    val properties: StateFlow<PropertiesState?> = _properties.asStateFlow()

    private val eventChannel = Channel<BrowserEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()


    private val history = ArrayDeque<String>()
    private var rawItems: List<FileItem> = emptyList()
    private var searchResults: List<FileItem> = emptyList()
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var sizeJob: Job? = null

    init {
        viewModelScope.launch { settings.collect { publish() } }
        viewModelScope.launch { app.tasks.changes.collect { load(silent = true) } }
        load()
    }

    // region Navigation

    fun open(path: String) {
        val target = PathUtils.normalize(path)
        if (target == _state.value.path) return
        closeSearch()
        history.addLast(_state.value.path)
        _state.update { it.copy(path = target, selection = emptySet(), canGoBack = true) }
        viewModelScope.launch { app.settings.pushRecent(target) }
        load()
    }

    fun goUp(): Boolean {
        val parent = PathUtils.parent(_state.value.path) ?: return false
        open(parent)
        return true
    }

    /** System back: leave search, clear selection, then walk the history. Returns false at the end. */
    fun back(): Boolean {
        val current = _state.value
        when {
            current.searchActive -> closeSearch()
            current.selectionMode -> clearSelection()
            history.isNotEmpty() -> {
                val previous = history.removeLast()
                _state.update { it.copy(path = previous, canGoBack = history.isNotEmpty()) }
                load()
            }
            else -> return false
        }
        return true
    }

    fun refresh() = load()

    private fun load(silent: Boolean = false) {
        loadJob?.cancel()
        val path = _state.value.path
        loadJob = viewModelScope.launch {
            if (!silent) _state.update { it.copy(loading = true, problem = null) }
            val result = ops.list(path)
            val writable = ops.isWritable(path)
            if (_state.value.path != path) return@launch
            when (result) {
                is ListResult.Success -> {
                    rawItems = result.items
                    // Drop selections that vanished (renamed, deleted elsewhere).
                    val existing = result.items.mapTo(HashSet()) { it.path }
                    _state.update { it.copy(problem = null, writable = writable, selection = it.selection.filterTo(HashSet()) { p -> p in existing }) }
                }
                is ListResult.AccessDenied -> fail(Problem.AccessDenied)
                is ListResult.NotFound -> fail(Problem.NotFound)
                is ListResult.NotADirectory -> fail(Problem.NotADirectory)
                is ListResult.Failure -> fail(Problem.Failed)
            }
            _state.update { it.copy(loading = false) }
            publish()
        }
    }

    private fun fail(problem: Problem) {
        rawItems = emptyList()
        _state.update { it.copy(problem = problem) }
    }

    private fun publish() {
        val config = settings.value
        val source = if (_state.value.searchActive) searchResults else rawItems
        val visible = if (config.showHidden) source else source.filterNot { it.isHidden }
        val sorted = if (_state.value.searchActive) visible else visible.sortedWith(config.sort.comparator())
        _state.update { it.copy(items = sorted) }
    }

    // endregion

    // region Selection and clicks

    fun onItemClick(item: FileItem) {
        if (_state.value.selectionMode) {
            toggleSelection(item.path)
            return
        }
        viewModelScope.launch {
            when {
                item.isDirectory -> open(item.path)
                item.kind == FileKind.Archive && app.archives.looksLikeArchive(item.name) ->
                    eventChannel.send(BrowserEvent.OpenArchive(item.path))
                FileKinds.isEditable(item.kind) -> eventChannel.send(BrowserEvent.OpenEditor(item.path))
                else -> launchView(item)
            }
        }
    }

    fun openAsText(item: FileItem) {
        viewModelScope.launch { eventChannel.send(BrowserEvent.OpenEditor(item.path)) }
    }

    fun openWithOtherApp(item: FileItem) {
        viewModelScope.launch { launchView(item) }
    }

    private suspend fun launchView(item: FileItem) {
        runCatching { app.fileOpener.viewIntent(item.path) }
            .onSuccess { eventChannel.send(BrowserEvent.Launch(it)) }
            .onFailure { app.tasks.post(UiText.Res(R.string.error_cannot_open, item.name)) }
    }

    fun onItemLongClick(item: FileItem) = toggleSelection(item.path)

    private fun toggleSelection(path: String) {
        _state.update {
            val next = if (path in it.selection) it.selection - path else it.selection + path
            it.copy(selection = next)
        }
    }

    fun selectAll() = _state.update { state -> state.copy(selection = state.items.mapTo(LinkedHashSet()) { it.path }) }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    private fun selectedItems(): List<FileItem> {
        val selection = _state.value.selection
        return _state.value.items.filter { it.path in selection }
    }

    // endregion

    // region Dialogs

    fun showDialog(dialog: BrowserDialog) {
        _dialog.value = dialog
    }

    fun dismissDialog() {
        _dialog.value = null
    }

    fun requestDelete() {
        val paths = selectedItems().map { it.path }
        if (paths.isEmpty()) return
        val toTrash = settings.value.useTrash && paths.all(app.trash::canTrash)
        if (settings.value.confirmDelete) showDialog(BrowserDialog.Delete(paths, toTrash)) else delete(paths)
    }

    fun requestRename() {
        selectedItems().singleOrNull()?.let { showDialog(BrowserDialog.Rename(it)) }
    }

    fun requestBatchRename() {
        val items = selectedItems()
        if (items.size > 1) showDialog(BrowserDialog.BatchRename(items))
    }

    /** Every name currently in the folder; the batch-rename preview needs it to spot clashes. */
    fun folderNames(): Set<String> = rawItems.mapTo(HashSet()) { it.name }

    /** Applies the previews one by one. Clashes were ruled out by the preview, so order does not matter. */
    fun applyBatchRename(previews: List<RenamePreview>) {
        dismissDialog()
        val changes = previews.filter { it.changed && it.problem == null }
        clearSelection()
        viewModelScope.launch {
            var renamed = 0
            for (change in changes) {
                val result = ops.rename(change.item.path, change.newName)
                if (result.isSuccess) renamed++ else result.onFailure { reportFailure(it) }
            }
            if (renamed > 0) postMessage(UiText.Plural(R.plurals.result_renamed, renamed, renamed))
            load(silent = true)
        }
    }

    fun requestCompress() {
        val paths = selectedItems().map { it.path }
        if (paths.isNotEmpty()) showDialog(BrowserDialog.Compress(paths))
    }

    @StringRes
    fun nameError(name: String, ignore: String? = null): Int? {
        PathUtils.validateName(name)?.let {
            return when (it) {
                PathUtils.NameError.Empty -> R.string.error_name_empty
                PathUtils.NameError.InvalidCharacters -> R.string.error_name_invalid
                PathUtils.NameError.Reserved -> R.string.error_name_reserved
                PathUtils.NameError.TooLong -> R.string.error_name_too_long
            }
        }
        val clash = rawItems.any { it.name == name && it.name != ignore }
        return if (clash) R.string.error_name_exists else null
    }

    // endregion

    // region File operations

    fun create(folder: Boolean, name: String) {
        dismissDialog()
        viewModelScope.launch {
            val parent = _state.value.path
            val result = if (folder) ops.createDirectory(parent, name) else ops.createFile(parent, name)
            result.onFailure { reportFailure(it) }
            load(silent = true)
        }
    }

    fun rename(item: FileItem, newName: String) {
        dismissDialog()
        viewModelScope.launch {
            ops.rename(item.path, newName).onFailure { reportFailure(it) }
            clearSelection()
            load(silent = true)
        }
    }

    fun delete(paths: List<String>) {
        dismissDialog()
        clearSelection()
        val useTrash = settings.value.useTrash
        app.tasks.launch(UiText.Res(R.string.task_deleting)) { progress ->
            // Anything on shared storage goes to the trash; system paths cannot be trashed and are removed for good.
            val allTrashed = app.trash.delete(paths, useTrash, progress)
            if (allTrashed && useTrash) {
                UiText.Plural(R.plurals.result_trashed, paths.size, paths.size)
            } else {
                UiText.Plural(R.plurals.result_deleted, paths.size, paths.size)
            }
        }
    }

    fun copySelection(cut: Boolean) {
        val paths = selectedItems().map { it.path }
        if (paths.isEmpty()) return
        app.clipboard.put(paths, if (cut) ClipMode.Cut else ClipMode.Copy)
        clearSelection()
    }

    fun clearClipboard() = app.clipboard.clear()

    fun paste() {
        val clip = clipboard.value ?: return
        val destination = _state.value.path
        viewModelScope.launch {
            val sameFolder = clip.paths.all { PathUtils.parent(it) == destination }
            when {
                sameFolder && clip.mode == ClipMode.Cut -> app.clipboard.clear()
                sameFolder -> runPaste(clip, destination, ConflictPolicy.KeepBoth)
                else -> {
                    val conflicts = ops.findConflicts(clip.paths, destination)
                    if (conflicts.isEmpty()) runPaste(clip, destination, ConflictPolicy.KeepBoth)
                    else showDialog(BrowserDialog.Conflict(clip, conflicts))
                }
            }
        }
    }

    fun resolveConflict(clip: ClipboardContent, policy: ConflictPolicy) {
        dismissDialog()
        runPaste(clip, _state.value.path, policy)
    }

    private fun runPaste(clip: ClipboardContent, destination: String, policy: ConflictPolicy) {
        val cut = clip.mode == ClipMode.Cut
        app.tasks.launch(UiText.Res(if (cut) R.string.task_moving else R.string.task_copying)) { progress ->
            if (cut) ops.move(clip.paths, destination, policy, progress) else ops.copy(clip.paths, destination, policy, progress)
            if (cut) app.clipboard.clear()
            UiText.Plural(if (cut) R.plurals.result_moved else R.plurals.result_copied, clip.paths.size, clip.paths.size)
        }
    }

    fun share() {
        val files = selectedItems()
        if (files.isEmpty()) return
        if (files.any { it.isDirectory }) {
            app.tasks.post(UiText.Res(R.string.error_share_folders))
            return
        }
        viewModelScope.launch {
            runCatching { app.fileOpener.shareIntent(files.map { it.path }) }
                .onSuccess { eventChannel.send(BrowserEvent.Launch(Intent.createChooser(it, null))) }
                .onFailure { reportFailure(it) }
        }
    }

    fun compress(paths: List<String>, name: String, format: ArchiveFormat, level: CompressionLevel, password: CharArray?) {
        dismissDialog()
        clearSelection()
        val output = PathUtils.join(_state.value.path, name)
        val request = CreateArchiveRequest(paths, output, format, level, password?.takeIf { it.isNotEmpty() })
        app.tasks.launch(UiText.Res(R.string.task_compressing)) { progress ->
            val written = app.archives.create(request, progress)
            UiText.Res(R.string.result_archive_created, PathUtils.name(written))
        }
    }

    fun defaultArchiveName(paths: List<String>, format: ArchiveFormat): String {
        val base = if (paths.size == 1) PathUtils.splitName(PathUtils.name(paths.first())).first else PathUtils.name(_state.value.path).ifEmpty { "archive" }
        return base + format.extension
    }

    private fun reportFailure(error: Throwable) {
        val text = when (error) {
            is app.filemanagerx.data.fs.AlreadyExistsException -> UiText.Res(R.string.error_name_exists)
            else -> UiText.Res(R.string.task_failed, error.message ?: error.javaClass.simpleName)
        }
        app.tasks.post(text)
    }

    // endregion

    // region View options

    fun setSort(sort: SortSpec) = viewModelScope.launch { app.settings.setSort(sort) }

    fun toggleViewMode() = viewModelScope.launch {
        app.settings.setViewMode(if (settings.value.viewMode == ViewMode.List) ViewMode.Grid else ViewMode.List)
    }

    fun toggleHidden() = viewModelScope.launch { app.settings.setShowHidden(!settings.value.showHidden) }

    fun cancelTask(id: Long) = app.tasks.cancel(id)

    fun postMessage(text: UiText) = app.tasks.post(text)

    // endregion

    // region Search

    fun startSearch() {
        searchResults = emptyList()
        _state.update { it.copy(searchActive = true, searchQuery = "", selection = emptySet()) }
        publish()
    }

    fun closeSearch() {
        searchJob?.cancel()
        searchResults = emptyList()
        _state.update { it.copy(searchActive = false, searchQuery = "", searching = false) }
        publish()
    }

    fun updateQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        searchResults = emptyList()
        if (query.length < 2) {
            _state.update { it.copy(searching = false) }
            publish()
            return
        }
        val root = _state.value.path
        searchJob = viewModelScope.launch {
            delay(300) // debounce typing
            _state.update { it.copy(searching = true) }
            val found = ArrayList<FileItem>()
            try {
                ops.search(root, query, settings.value.showHidden).collect { item ->
                    found += item
                    if (found.size % 10 == 1) {
                        searchResults = found.toList()
                        publish()
                    }
                }
            } finally {
                searchResults = found.toList()
                _state.update { it.copy(searching = false) }
                publish()
            }
        }
    }

    // endregion

    // region Properties

    fun showProperties() {
        val item = selectedItems().singleOrNull() ?: stateItemForCurrentFolder()
        loadProperties(item)
    }

    private fun stateItemForCurrentFolder(): FileItem {
        val path = _state.value.path
        return FileItem(path, PathUtils.name(path).ifEmpty { "/" }, true, false, 0L, 0L, FileKind.Folder)
    }

    private fun loadProperties(item: FileItem) {
        sizeJob?.cancel()
        _properties.value = PropertiesState(item)
        sizeJob = viewModelScope.launch {
            launch {
                val permissions = ops.permissions(item.path)
                val mount = ops.mountInfo(item.path)
                _properties.update { it?.copy(permissions = permissions, mount = mount) }
            }
            val total = ops.computeSize(item.path) { partial -> _properties.update { it?.copy(size = partial) } }
            _properties.update { it?.copy(size = total, sizeFinal = true) }
        }
    }

    fun closeProperties() {
        sizeJob?.cancel()
        _properties.value = null
    }

    fun computeChecksum(algorithm: String) {
        val current = _properties.value ?: return
        if (current.checksumRunning != null || current.item.isDirectory) return
        _properties.update { it?.copy(checksumRunning = algorithm) }
        viewModelScope.launch {
            val value = runCatching { ops.checksum(current.item.path, algorithm) }.getOrElse { it.message ?: "?" }
            _properties.update { it?.copy(checksums = it.checksums + (algorithm to value), checksumRunning = null) }
        }
    }

    fun chmod(octal: String, recursive: Boolean) {
        val item = _properties.value?.item ?: return
        viewModelScope.launch {
            ops.setPermissions(item.path, octal, recursive)
                .onSuccess { app.tasks.post(UiText.Res(R.string.result_permissions_changed)) }
                .onFailure { reportFailure(it) }
            _properties.update { it?.copy(permissions = ops.permissions(item.path)) }
        }
    }

    fun remount(readWrite: Boolean) {
        val current = _properties.value ?: return
        val mount = current.mount ?: return
        viewModelScope.launch {
            ops.remount(mount, readWrite)
                .onSuccess { app.tasks.post(UiText.Res(if (readWrite) R.string.result_remounted_rw else R.string.result_remounted_ro)) }
                .onFailure { reportFailure(it) }
            _properties.update { it?.copy(mount = ops.mountInfo(current.item.path)) }
            load(silent = true)
        }
    }

    /** Read/write now, back to read-only after [minutes]; the timer runs in the root process. */
    fun remountTemporarily(minutes: Int) {
        val current = _properties.value ?: return
        val mount = current.mount ?: return
        viewModelScope.launch {
            ops.remountReadWriteFor(mount, minutes)
                .onSuccess { app.tasks.post(UiText.Res(R.string.result_remounted_temporarily, minutes)) }
                .onFailure { reportFailure(it) }
            _properties.update { it?.copy(mount = ops.mountInfo(current.item.path)) }
            load(silent = true)
        }
    }

    val isRootActive: Boolean get() = app.shell.isRootActive

    fun toggleBookmark() {
        val path = _state.value.path
        viewModelScope.launch { app.settings.toggleBookmark(path) }
    }

    // endregion
}
