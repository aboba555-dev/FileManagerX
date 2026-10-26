package app.filemanagerx.ui.home

import android.os.Environment
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.filemanagerx.R
import app.filemanagerx.data.fs.RootStatus
import app.filemanagerx.data.fs.VolumeInfo
import app.filemanagerx.data.fs.VolumeKind
import app.filemanagerx.data.settings.AppSettings
import app.filemanagerx.di.AppContainer
import app.filemanagerx.domain.PathUtils
import app.filemanagerx.ui.components.Fa
import app.filemanagerx.ui.components.FaIcon
import app.filemanagerx.ui.components.IconAction
import app.filemanagerx.ui.components.SectionLabel
import app.filemanagerx.ui.components.containerViewModel
import app.filemanagerx.ui.components.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(private val app: AppContainer) : ViewModel() {
    private val _volumes = MutableStateFlow<List<VolumeInfo>>(emptyList())
    val volumes: StateFlow<List<VolumeInfo>> = _volumes.asStateFlow()

    val rootStatus: StateFlow<RootStatus> = app.rootManager.status

    val settings: StateFlow<AppSettings> = app.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun reload() {
        viewModelScope.launch { _volumes.value = withContext(Dispatchers.IO) { app.volumes.load() } }
    }

    fun unpin(path: String) {
        viewModelScope.launch { app.settings.toggleBookmark(path) }
    }

    fun clearRecents() {
        viewModelScope.launch { app.settings.clearRecents() }
    }

    fun setRootMode(enabled: Boolean) {
        viewModelScope.launch { app.settings.setRootModeEnabled(enabled) }
    }
}

@Composable
fun HomeScreen(
    onOpenFolder: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTrash: () -> Unit,
    onAnalyze: (String) -> Unit,
) {
    val vm = containerViewModel { HomeViewModel(it) }
    val volumes by vm.volumes.collectAsStateWithLifecycle()
    val rootStatus by vm.rootStatus.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        vm.reload()
        onPauseOrDispose { }
    }

    val primary = remember { Environment.getExternalStorageDirectory().path }
    val rootActive = settings.rootModeEnabled && rootStatus == RootStatus.Granted

    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 24.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.home_tagline),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconAction(Fa.Gear, stringResource(R.string.settings_title), onOpenSettings)
            }
        }

        item { SectionLabel(stringResource(R.string.home_storage)) }
        items(volumes.size) { index ->
            StorageCard(volumes[index], onClick = { onOpenFolder(volumes[index].path) })
        }

        if (settings.bookmarks.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.home_pinned)) }
            item {
                PathChips(settings.bookmarks, Fa.Bookmark, onClick = onOpenFolder, onLongClick = vm::unpin)
            }
        }
        if (settings.recents.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(stringResource(R.string.home_recent), Modifier.weight(1f))
                    IconAction(Fa.Xmark, stringResource(R.string.action_clear), vm::clearRecents)
                }
            }
            item { PathChips(settings.recents, Fa.ClockRotateLeft, onClick = onOpenFolder, onLongClick = null) }
        }

        item { SectionLabel(stringResource(R.string.home_quick_access)) }
        item {
            QuickAccess(
                entries = listOf(
                    QuickEntry(Fa.Download, R.string.quick_downloads, "$primary/Download"),
                    QuickEntry(Fa.FileImage, R.string.quick_pictures, "$primary/Pictures"),
                    QuickEntry(Fa.Mobile, R.string.quick_camera, "$primary/DCIM"),
                    QuickEntry(Fa.FileAudio, R.string.quick_music, "$primary/Music"),
                    QuickEntry(Fa.FileVideo, R.string.quick_movies, "$primary/Movies"),
                    QuickEntry(Fa.FileLines, R.string.quick_documents, "$primary/Documents"),
                ),
                onOpen = onOpenFolder,
            )
        }

        item { SectionLabel(stringResource(R.string.home_tools)) }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolRow(Fa.TrashArrowUp, R.string.trash_title, onOpenTrash)
                ToolRow(Fa.ChartPie, R.string.analyzer_title, { onAnalyze(primary) })
            }
        }

        item { SectionLabel(stringResource(R.string.home_system)) }
        item {
            RootCard(
                status = rootStatus,
                enabled = settings.rootModeEnabled,
                onToggle = vm::setRootMode,
            )
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SystemRow(Fa.FolderTree, R.string.system_root, "/", rootActive, onOpenFolder)
                SystemRow(Fa.Microchip, R.string.system_system, "/system", rootActive, onOpenFolder)
                SystemRow(Fa.Shield, R.string.system_data, "/data", rootActive, onOpenFolder)
                SystemRow(Fa.Terminal, R.string.system_etc, "/etc", rootActive, onOpenFolder)
            }
        }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }
}

@Composable
private fun StorageCard(volume: VolumeInfo, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(28.dp)).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            StorageRing(volume.usedFraction, Modifier.size(84.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FaIcon(
                        if (volume.kind == VolumeKind.Internal) Fa.Mobile else Fa.SdCard,
                        size = 16.dp,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(volume.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.storage_used_of, formatSize(volume.usedBytes), formatSize(volume.totalBytes)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.storage_free, formatSize(volume.freeBytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StorageRing(fraction: Float, modifier: Modifier = Modifier) {
    val progress by animateFloatAsState(fraction, tween(900), label = "ring")
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val color = if (fraction > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 10.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(color, -90f, 360f * progress, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
    }
}

private data class QuickEntry(val glyph: String, val label: Int, val path: String)

@Composable
private fun QuickAccess(entries: List<QuickEntry>, onOpen: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { entry ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(22.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .clickable { onOpen(entry.path) }
                            .padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) { FaIcon(entry.glyph, size = 20.dp, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
                        Text(stringResource(entry.label), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun RootCard(status: RootStatus, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val active = enabled && status == RootStatus.Granted
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FaIcon(Fa.Bolt, size = 26.dp)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.root_mode), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(
                        when {
                            status == RootStatus.Checking -> R.string.root_status_checking
                            active -> R.string.root_status_active
                            status == RootStatus.Unavailable -> R.string.root_status_unavailable
                            else -> R.string.root_status_off
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (status == RootStatus.Checking) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            } else {
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
        }
    }
}

@Composable
private fun SystemRow(glyph: String, label: Int, path: String, rootActive: Boolean, onOpen: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { onOpen(path) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FaIcon(glyph, size = 20.dp, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
            Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FaIcon(
            if (rootActive) Fa.Unlock else Fa.Lock,
            size = 16.dp,
            tint = if (rootActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PathChips(paths: List<String>, glyph: String, onClick: (String) -> Unit, onLongClick: ((String) -> Unit)?) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        paths.forEach { path ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .combinedClickable(onClick = { onClick(path) }, onLongClick = onLongClick?.let { handler -> { handler(path) } })
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                FaIcon(glyph, size = 18.dp, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(
                        PathUtils.name(path).ifEmpty { path },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
    }
}

@Composable
private fun ToolRow(glyph: String, label: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FaIcon(glyph, size = 20.dp, tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(label), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        FaIcon(Fa.AngleRight, size = 14.dp, tint = MaterialTheme.colorScheme.outline)
    }
}
