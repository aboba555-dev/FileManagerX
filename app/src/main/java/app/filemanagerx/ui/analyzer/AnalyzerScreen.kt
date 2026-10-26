package app.filemanagerx.ui.analyzer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.filemanagerx.R
import app.filemanagerx.di.AppContainer
import app.filemanagerx.domain.PathUtils
import app.filemanagerx.domain.model.FileItem
import app.filemanagerx.domain.model.ListResult
import app.filemanagerx.ui.components.EmptyState
import app.filemanagerx.ui.components.Fa
import app.filemanagerx.ui.components.IconAction
import app.filemanagerx.ui.components.KindTile
import app.filemanagerx.ui.components.containerViewModel
import app.filemanagerx.ui.components.formatSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class AnalyzerNode(val item: FileItem, val bytes: Long)

data class AnalyzerState(
    val path: String,
    val scanning: Boolean = true,
    val nodes: List<AnalyzerNode> = emptyList(),
    val failed: Boolean = false,
) {
    val total: Long get() = nodes.sumOf { it.bytes }
}

class AnalyzerViewModel(private val app: AppContainer, startPath: String) : ViewModel() {
    private val ops = app.fileOperations

    private val _state = MutableStateFlow(AnalyzerState(PathUtils.normalize(startPath)))
    val state: StateFlow<AnalyzerState> = _state.asStateFlow()

    private val history = ArrayDeque<String>()
    private var scanJob: Job? = null

    init {
        scan()
    }

    fun open(item: FileItem) {
        if (!item.isDirectory) return
        history.addLast(_state.value.path)
        _state.update { AnalyzerState(item.path) }
        scan()
    }

    fun back(): Boolean {
        val previous = history.removeLastOrNull() ?: return false
        _state.update { AnalyzerState(previous) }
        scan()
        return true
    }

    private fun scan() {
        scanJob?.cancel()
        val path = _state.value.path
        scanJob = viewModelScope.launch {
            val listing = ops.list(path)
            if (listing !is ListResult.Success) {
                _state.update { it.copy(scanning = false, failed = true) }
                return@launch
            }
            // Files have their size already; folders start at zero and grow as they are measured.
            _state.update { state ->
                state.copy(nodes = listing.items.map { AnalyzerNode(it, if (it.isDirectory) 0L else it.size) })
            }
            val limiter = Semaphore(PARALLEL_SCANS)
            coroutineScope {
                listing.items.filter { it.isDirectory }.map { folder ->
                    async {
                        limiter.withPermit {
                            var lastUpdate = 0L
                            val size = ops.computeSize(folder.path) { partial ->
                                val now = System.currentTimeMillis()
                                if (now - lastUpdate >= PARTIAL_INTERVAL_MS) {
                                    lastUpdate = now
                                    setBytes(path, folder.path, partial.bytes)
                                }
                            }
                            setBytes(path, folder.path, size.bytes)
                        }
                    }
                }.awaitAll()
            }
            _state.update { it.copy(scanning = false) }
        }
    }

    private fun setBytes(scanPath: String, itemPath: String, bytes: Long) {
        _state.update { state ->
            if (state.path != scanPath) state else state.copy(
                nodes = state.nodes.map { if (it.item.path == itemPath) it.copy(bytes = bytes) else it },
            )
        }
    }

    private companion object {
        const val PARALLEL_SCANS = 4
        const val PARTIAL_INTERVAL_MS = 250L
    }
}

@Composable
fun AnalyzerScreen(vm: AnalyzerViewModel, onExit: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler { if (!vm.back()) onExit() }

    val sorted = remember(state.nodes) { state.nodes.filter { it.bytes > 0L }.sortedByDescending { it.bytes } }

    // Surface (not a bare background) provides LocalContentColor, otherwise Text/icons fall back to black.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Fa.ArrowLeft, stringResource(R.string.action_back), { if (!vm.back()) onExit() })
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    PathUtils.name(state.path).ifEmpty { stringResource(R.string.root_title) },
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.analyzer_total, formatSize(state.total)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))

        when {
            state.failed -> EmptyState(Fa.Lock, stringResource(R.string.problem_denied), stringResource(R.string.problem_denied_hint), Modifier.padding(top = 64.dp))
            sorted.isEmpty() && !state.scanning -> EmptyState(Fa.FolderOpen, stringResource(R.string.folder_empty), modifier = Modifier.padding(top = 64.dp))
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                items(sorted, key = { it.item.path }) { node ->
                    SizeRow(node, state.total, onClick = { vm.open(node.item) })
                }
            }
        }
    }
    }
}

@Composable
private fun SizeRow(node: AnalyzerNode, total: Long, onClick: () -> Unit) {
    val fraction = if (total > 0L) (node.bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = node.item.isDirectory, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        KindTile(node.item.kind, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    node.item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(formatSize(node.bytes), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
    }
}
