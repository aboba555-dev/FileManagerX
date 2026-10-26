package app.filemanagerx.ui.archive

import android.content.Intent
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.filemanagerx.R
import app.filemanagerx.core.UiText
import app.filemanagerx.data.archive.ArchiveException
import app.filemanagerx.data.archive.ArchiveFormat
import app.filemanagerx.data.archive.ArchiveSession
import app.filemanagerx.data.archive.ExtractRequest
import app.filemanagerx.data.fs.ProgressSink
import app.filemanagerx.di.AppContainer
import app.filemanagerx.domain.FileKinds
import app.filemanagerx.domain.NaturalOrder
import app.filemanagerx.domain.PathUtils
import app.filemanagerx.domain.model.ConflictPolicy
import app.filemanagerx.domain.model.FileItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class ArchiveUiState(
    val archivePath: String,
    val loading: Boolean = true,
    val format: ArchiveFormat? = null,
    /** Folder currently shown, relative to the archive root; empty at the root. */
    val cwd: String = "",
    val rows: List<FileItem> = emptyList(),
    val selection: Set<String> = emptySet(),
    val entryCount: Int = 0,
    val totalSize: Long = 0L,
    val encrypted: Boolean = false,
    val error: UiText? = null,
) {
    val name: String get() = PathUtils.name(archivePath)
    val selectionMode: Boolean get() = selection.isNotEmpty()
    val canEdit: Boolean get() = format == ArchiveFormat.Zip
}

data class PasswordPrompt(val wrong: Boolean)

data class ExtractDialogState(val selection: Set<String>?)

sealed interface ArchiveEvent {
    data class Launch(val intent: Intent) : ArchiveEvent
}

class ArchiveViewModel(private val app: AppContainer, private val archivePath: String) : ViewModel() {
    private val _state = MutableStateFlow(ArchiveUiState(archivePath))
    val state: StateFlow<ArchiveUiState> = _state.asStateFlow()

    private val _passwordPrompt = MutableStateFlow<PasswordPrompt?>(null)
    val passwordPrompt: StateFlow<PasswordPrompt?> = _passwordPrompt.asStateFlow()

    private val _extractDialog = MutableStateFlow<ExtractDialogState?>(null)
    val extractDialog: StateFlow<ExtractDialogState?> = _extractDialog.asStateFlow()

    private val _deleteDialog = MutableStateFlow<Set<String>?>(null)
    val deleteDialog: StateFlow<Set<String>?> = _deleteDialog.asStateFlow()

    private val _infoVisible = MutableStateFlow(false)
    val infoVisible: StateFlow<Boolean> = _infoVisible.asStateFlow()

    private val eventChannel = Channel<ArchiveEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    val tasks = app.tasks.tasks
    val operations = app.fileOperations

    private var session: ArchiveSession? = null
    private var password: CharArray? = null
    private var pendingAfterPassword: (() -> Unit)? = null

    init {
        open()
    }

    /** Folder a "here" extraction goes to. Archives handed over by other apps live in our cache, so use Downloads then. */
    val defaultDestinationParent: String
        get() {
            val parent = PathUtils.parent(archivePath) ?: PathUtils.ROOT
            val cache = app.appContext.cacheDir.path
            return if (PathUtils.isSameOrDescendant(parent, cache)) {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path
            } else {
                parent
            }
        }

    val namedFolderDestination: String
        get() = PathUtils.join(defaultDestinationParent, PathUtils.splitName(PathUtils.name(archivePath)).first)

    // region Opening

    private fun open(): Job =
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            session?.close()
            session = null
            try {
                val opened = app.archives.open(archivePath, password)
                session = opened
                _state.update {
                    it.copy(
                        loading = false,
                        format = opened.format,
                        entryCount = opened.entries.count { entry -> !entry.isDirectory },
                        totalSize = opened.entries.sumOf { entry -> entry.size.coerceAtLeast(0L) },
                        encrypted = opened.entries.any { entry -> entry.isEncrypted } || opened.requiresPassword,
                        selection = emptySet(),
                    )
                }
                rebuild()
                if (opened.requiresPassword) _passwordPrompt.value = PasswordPrompt(wrong = false)
            } catch (e: ArchiveException.PasswordRequired) {
                _state.update { it.copy(loading = false, error = UiText.Res(R.string.error_password_required)) }
                _passwordPrompt.value = PasswordPrompt(wrong = false)
            } catch (e: ArchiveException.WrongPassword) {
                _state.update { it.copy(loading = false, error = UiText.Res(R.string.error_wrong_password)) }
                _passwordPrompt.value = PasswordPrompt(wrong = true)
            } catch (e: ArchiveException.Unsupported) {
                _state.update { it.copy(loading = false, error = UiText.Res(R.string.error_archive_unsupported, e.message.orEmpty())) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = UiText.Res(R.string.error_archive_corrupt)) }
            }
        }

    fun submitPassword(value: CharArray) {
        password = value
        _passwordPrompt.value = null
        val after = pendingAfterPassword
        pendingAfterPassword = null
        viewModelScope.launch {
            open().join()
            after?.invoke()
        }
    }

    fun dismissPassword() {
        _passwordPrompt.value = null
        pendingAfterPassword = null
    }

    fun retry() {
        open()
    }

    /** Runs [action] once the archive can be read; asks for the password first when it cannot be yet. */
    private fun withPassword(action: () -> Unit) {
        val current = session
        if (current != null && current.requiresPassword && password == null) {
            pendingAfterPassword = action
            _passwordPrompt.value = PasswordPrompt(wrong = false)
        } else {
            action()
        }
    }

    // endregion

    // region Browsing

    private fun rebuild() {
        val entries = session?.entries ?: return
        val cwd = _state.value.cwd
        val prefix = if (cwd.isEmpty()) "" else "$cwd/"
        val rows = LinkedHashMap<String, FileItem>()
        val folderSizes = HashMap<String, Long>()
        for (entry in entries) {
            if (!entry.path.startsWith(prefix) || entry.path == cwd) continue
            val relative = entry.path.removePrefix(prefix)
            val first = relative.substringBefore('/')
            val nested = relative.contains('/')
            if (nested || entry.isDirectory) {
                val folderPath = prefix + first
                folderSizes.merge(folderPath, if (entry.isDirectory) 0L else entry.size.coerceAtLeast(0L), Long::plus)
                rows.putIfAbsent(folderPath, entryItem(folderPath, first, true, 0L, entry.modifiedAt))
            } else {
                rows[entry.path] = entryItem(entry.path, first, false, entry.size, entry.modifiedAt)
            }
        }
        val sorted = rows.values
            .map { item -> if (item.isDirectory) item.copy(size = folderSizes[item.path] ?: 0L) else item }
            .sortedWith { a, b ->
                val byFolder = b.isDirectory.compareTo(a.isDirectory)
                if (byFolder != 0) byFolder else NaturalOrder.compare(a.name, b.name)
            }
        _state.update { it.copy(rows = sorted) }
    }

    private fun entryItem(path: String, name: String, directory: Boolean, size: Long, modified: Long) =
        FileItem(path, name, directory, false, size, modified, FileKinds.of(name, directory))

    fun openFolder(path: String) {
        _state.update { it.copy(cwd = path, selection = emptySet()) }
        rebuild()
    }

    /** Goes one folder up; returns false at the archive root. */
    fun up(): Boolean {
        val current = _state.value
        if (current.selectionMode) {
            clearSelection()
            return true
        }
        if (current.cwd.isEmpty()) return false
        _state.update { it.copy(cwd = it.cwd.substringBeforeLast('/', ""), selection = emptySet()) }
        rebuild()
        return true
    }

    fun onRowClick(item: FileItem) {
        if (_state.value.selectionMode) {
            toggle(item.path)
        } else if (item.isDirectory) {
            openFolder(item.path)
        } else {
            openEntry(item)
        }
    }

    fun onRowLongClick(item: FileItem) = toggle(item.path)

    private fun toggle(path: String) = _state.update {
        it.copy(selection = if (path in it.selection) it.selection - path else it.selection + path)
    }

    fun selectAll() = _state.update { state -> state.copy(selection = state.rows.mapTo(LinkedHashSet()) { it.path }) }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    // endregion

    // region Actions

    /** Extracts one entry into the cache and hands it to another app. */
    private fun openEntry(item: FileItem) = withPassword {
        viewModelScope.launch {
            val folder = File(app.appContext.cacheDir, "staging/archive-${UUID.randomUUID().toString().take(8)}").path
            try {
                withContext(Dispatchers.IO) {
                    val current = session ?: return@withContext
                    app.archives.extract(
                        current,
                        ExtractRequest(folder, setOf(item.path), ConflictPolicy.Replace),
                        ProgressSink.None,
                    )
                }
                val extracted = PathUtils.join(folder, item.path)
                eventChannel.send(ArchiveEvent.Launch(app.fileOpener.viewIntent(extracted)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.tasks.post(failureText(e))
            }
        }
    }

    fun requestExtract(onlySelection: Boolean) = withPassword {
        val selection = _state.value.selection
        _extractDialog.value = ExtractDialogState(if (onlySelection && selection.isNotEmpty()) selection else null)
    }

    fun dismissExtract() {
        _extractDialog.value = null
    }

    fun extract(destination: String, selection: Set<String>?, policy: ConflictPolicy) {
        _extractDialog.value = null
        clearSelection()
        val request = ExtractRequest(destination, selection, policy)
        val pass = password
        app.tasks.launch(UiText.Res(R.string.task_extracting)) { progress ->
            // A private session: the screen may be closed while this task keeps running.
            val count = app.archives.open(archivePath, pass).use { opened -> app.archives.extract(opened, request, progress) }
            UiText.Plural(R.plurals.result_extracted, count, count, PathUtils.name(destination).ifEmpty { destination })
        }
    }

    fun test() = withPassword {
        val pass = password
        app.tasks.launch(UiText.Res(R.string.task_testing)) { progress ->
            app.archives.open(archivePath, pass).use { opened -> app.archives.test(opened, progress) }
            UiText.Res(R.string.result_test_ok)
        }
    }

    fun requestDelete() {
        val selection = _state.value.selection
        if (selection.isNotEmpty() && _state.value.canEdit) _deleteDialog.value = selection
    }

    fun dismissDelete() {
        _deleteDialog.value = null
    }

    fun delete(paths: Set<String>) {
        _deleteDialog.value = null
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            session?.close()
            session = null
            try {
                app.archives.open(archivePath, password).use { opened -> app.archives.deleteEntries(opened, paths) }
                app.tasks.post(UiText.Plural(R.plurals.result_entries_removed, paths.size, paths.size))
                app.tasks.notifyChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.tasks.post(failureText(e))
            }
            open()
        }
    }

    fun showInfo(visible: Boolean) {
        _infoVisible.value = visible
    }

    fun cancelTask(id: Long) = app.tasks.cancel(id)

    private fun failureText(error: Throwable): UiText = when (error) {
        is ArchiveException.WrongPassword -> UiText.Res(R.string.error_wrong_password)
        is ArchiveException.PasswordRequired -> UiText.Res(R.string.error_password_required)
        else -> UiText.Res(R.string.task_failed, error.message ?: error.javaClass.simpleName)
    }

    // endregion

    override fun onCleared() {
        session?.close()
        session = null
        password?.fill('\u0000')
    }
}
