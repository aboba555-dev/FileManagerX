@file:OptIn(ExperimentalLayoutApi::class)

package app.filemanagerx.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.filemanagerx.R
import app.filemanagerx.data.fs.RootStatus
import app.filemanagerx.data.settings.AppSettings
import app.filemanagerx.di.AppContainer
import app.filemanagerx.domain.model.ThemeMode
import app.filemanagerx.ui.components.Fa
import app.filemanagerx.ui.components.FaIcon
import app.filemanagerx.ui.components.IconAction
import app.filemanagerx.ui.components.SectionLabel
import app.filemanagerx.ui.components.containerViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val app: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> = app.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val rootStatus: StateFlow<RootStatus> = app.rootManager.status

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    fun setThemeMode(mode: ThemeMode) = launch { app.settings.setThemeMode(mode) }
    fun setDynamicColor(value: Boolean) = launch { app.settings.setDynamicColor(value) }
    fun setAmoled(value: Boolean) = launch { app.settings.setAmoled(value) }
    fun setShowHidden(value: Boolean) = launch { app.settings.setShowHidden(value) }
    fun setConfirmDelete(value: Boolean) = launch { app.settings.setConfirmDelete(value) }
    fun setUseTrash(value: Boolean) = launch { app.settings.setUseTrash(value) }
    fun setThumbnails(value: Boolean) = launch { app.settings.setThumbnails(value) }
    fun setRootMode(value: Boolean) = launch { app.settings.setRootModeEnabled(value) }
    fun setZipCharset(value: String) = launch { app.settings.setZipCharset(value) }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenAbout: () -> Unit) {
    val vm = containerViewModel { SettingsViewModel(it) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val rootStatus by vm.rootStatus.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconAction(Fa.ArrowLeft, stringResource(R.string.action_back), onBack)
                Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 8.dp))
            }
        }

        item { SectionLabel(stringResource(R.string.settings_appearance)) }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { vm.setThemeMode(mode) },
                            label = { Text(stringResource(themeLabel(mode))) },
                            leadingIcon = { FaIcon(themeGlyph(mode), size = 14.dp) },
                        )
                    }
                }
            }
        }
        item { SwitchRow(Fa.Palette, R.string.setting_dynamic, R.string.setting_dynamic_hint, settings.dynamicColor, vm::setDynamicColor) }
        item { SwitchRow(Fa.Moon, R.string.setting_amoled, R.string.setting_amoled_hint, settings.amoled, vm::setAmoled) }

        item { SectionLabel(stringResource(R.string.settings_files)) }
        item { SwitchRow(Fa.Eye, R.string.setting_hidden, R.string.setting_hidden_hint, settings.showHidden, vm::setShowHidden) }
        item { SwitchRow(Fa.TrashArrowUp, R.string.setting_use_trash, R.string.setting_use_trash_hint, settings.useTrash, vm::setUseTrash) }
        item { SwitchRow(Fa.FileImage, R.string.setting_thumbnails, R.string.setting_thumbnails_hint, settings.thumbnails, vm::setThumbnails) }
        item { SwitchRow(Fa.Trash, R.string.setting_confirm_delete, R.string.setting_confirm_delete_hint, settings.confirmDelete, vm::setConfirmDelete) }

        item { SectionLabel(stringResource(R.string.settings_root)) }
        item {
            SwitchRow(
                Fa.Bolt,
                R.string.root_mode,
                when {
                    rootStatus == RootStatus.Checking -> R.string.root_status_checking
                    settings.rootModeEnabled && rootStatus == RootStatus.Granted -> R.string.root_status_active
                    rootStatus == RootStatus.Unavailable -> R.string.root_status_unavailable
                    else -> R.string.setting_root_hint
                },
                settings.rootModeEnabled,
                vm::setRootMode,
            )
        }

        item { SectionLabel(stringResource(R.string.settings_archives)) }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(stringResource(R.string.setting_zip_charset), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.setting_zip_charset_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppSettings.ZIP_CHARSETS.forEach { charset ->
                        FilterChip(
                            selected = settings.zipCharset == charset,
                            onClick = { vm.setZipCharset(charset) },
                            label = { Text(charset) },
                        )
                    }
                }
            }
        }

        item { SectionLabel(stringResource(R.string.settings_about)) }
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(onClick = onOpenAbout)
                    .padding(horizontal = 8.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                FaIcon(Fa.Heart, size = 20.dp, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.about_title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                FaIcon(Fa.AngleRight, size = 16.dp, tint = MaterialTheme.colorScheme.outline)
            }
        }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }
}

private fun themeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.System -> R.string.theme_system
    ThemeMode.Light -> R.string.theme_light
    ThemeMode.Dark -> R.string.theme_dark
}

private fun themeGlyph(mode: ThemeMode): String = when (mode) {
    ThemeMode.System -> Fa.CircleHalf
    ThemeMode.Light -> Fa.Sun
    ThemeMode.Dark -> Fa.Moon
}

@Composable
private fun SwitchRow(glyph: String, title: Int, hint: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable { onChange(!checked) }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FaIcon(glyph, size = 20.dp, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
