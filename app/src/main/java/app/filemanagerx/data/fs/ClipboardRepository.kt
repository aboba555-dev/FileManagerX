package app.filemanagerx.data.fs

import app.filemanagerx.domain.model.ClipMode
import app.filemanagerx.domain.model.ClipboardContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-app cut/copy buffer shared by every screen. */
class ClipboardRepository {
    private val _content = MutableStateFlow<ClipboardContent?>(null)
    val content: StateFlow<ClipboardContent?> = _content.asStateFlow()

    fun put(paths: List<String>, mode: ClipMode) {
        _content.value = if (paths.isEmpty()) null else ClipboardContent(paths, mode)
    }

    fun clear() {
        _content.value = null
    }
}
