package app.filemanagerx.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.filemanagerx.R
import app.filemanagerx.core.UiText
import app.filemanagerx.core.asString
import app.filemanagerx.data.fs.FileTooLargeException
import app.filemanagerx.di.AppContainer
import app.filemanagerx.domain.PathUtils
import app.filemanagerx.ui.components.ConfirmDialog
import app.filemanagerx.ui.components.EmptyState
import app.filemanagerx.ui.components.Fa
import app.filemanagerx.ui.components.IconAction
import app.filemanagerx.ui.theme.JetBrainsMono
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditorState(
    val path: String,
    val loading: Boolean = true,
    val text: String = "",
    val original: String = "",
    val error: UiText? = null,
    val saving: Boolean = false,
) {
    val dirty: Boolean get() = text != original
    val name: String get() = PathUtils.name(path)
}

class EditorViewModel(private val app: AppContainer, path: String) : ViewModel() {
    private val _state = MutableStateFlow(EditorState(path))
    val state: StateFlow<EditorState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            app.fileOperations.readText(path)
                .onSuccess { text -> _state.update { it.copy(loading = false, text = text, original = text) } }
                .onFailure { error ->
                    val message = if (error is FileTooLargeException) {
                        UiText.Res(R.string.error_file_too_large)
                    } else {
                        UiText.Res(R.string.task_failed, error.message ?: error.javaClass.simpleName)
                    }
                    _state.update { it.copy(loading = false, error = message) }
                }
        }
    }

    fun onTextChange(text: String) = _state.update { it.copy(text = text) }

    fun save() {
        val current = _state.value
        if (!current.dirty || current.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            app.fileOperations.writeText(current.path, current.text)
                .onSuccess {
                    _state.update { it.copy(saving = false, original = current.text) }
                    app.tasks.post(UiText.Res(R.string.result_saved))
                    app.tasks.notifyChanged()
                }
                .onFailure { error ->
                    _state.update { it.copy(saving = false) }
                    app.tasks.post(UiText.Res(R.string.task_failed, error.message ?: error.javaClass.simpleName))
                }
        }
    }
}

@Composable
fun EditorScreen(vm: EditorViewModel, onExit: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmDiscard by remember { mutableStateOf(false) }

    val requestExit = { if (state.dirty) confirmDiscard = true else onExit() }
    BackHandler(onBack = requestExit)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Fa.ArrowLeft, stringResource(R.string.action_back), requestExit)
            Text(
                text = state.name + if (state.dirty) " •" else "",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            IconAction(
                Fa.Save,
                stringResource(R.string.action_save),
                vm::save,
                tint = if (state.dirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
        }
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.error != null -> EmptyState(Fa.Warning, state.error!!.asString(), modifier = Modifier.padding(top = 64.dp))
            else -> BasicTextField(
                value = state.text,
                onValueChange = vm::onTextChange,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = JetBrainsMono,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.dialog_discard_title),
            message = stringResource(R.string.dialog_discard_message),
            confirmLabel = stringResource(R.string.action_discard),
            destructive = true,
            onConfirm = { confirmDiscard = false; onExit() },
            onDismiss = { confirmDiscard = false },
        )
    }
}
