package app.filemanagerx.data.tasks

import android.os.SystemClock
import androidx.compose.runtime.Immutable
import app.filemanagerx.R
import app.filemanagerx.core.UiText
import app.filemanagerx.data.fs.ProgressSink
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class TaskProgress(
    val id: Long,
    val title: UiText,
    val detail: String = "",
    val done: Long = 0L,
    val total: Long = 0L,
) {
    /** `null` while the amount of work is unknown. */
    val fraction: Float? get() = if (total > 0L) (done.toFloat() / total).coerceIn(0f, 1f) else null
}

/**
 * Runs long operations (copy, extract, compress...) on an application-wide scope so they survive
 * screen changes, and publishes progress, finished-messages and "something changed on disk" ticks.
 */
class TaskCenter(private val scope: CoroutineScope) {
    private val nextId = AtomicLong(1)
    private val jobs = ConcurrentHashMap<Long, Job>()

    private val _tasks = MutableStateFlow<List<TaskProgress>>(emptyList())
    val tasks: StateFlow<List<TaskProgress>> = _tasks.asStateFlow()

    private val _messages = MutableSharedFlow<UiText>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** One-shot user-facing results ("Copied 3 items", errors...). */
    val messages: SharedFlow<UiText> = _messages.asSharedFlow()

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Emits after every task so open folder listings can reload. */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    /** Emits an extra "reload" tick without running a task (e.g. after a rename). */
    fun notifyChanged() {
        _changes.tryEmit(Unit)
    }

    fun post(message: UiText) {
        _messages.tryEmit(message)
    }

    /** [block] returns the message to show on success, or `null` for none. */
    fun launch(title: UiText, block: suspend (ProgressSink) -> UiText?): Job {
        val id = nextId.getAndIncrement()
        _tasks.update { it + TaskProgress(id = id, title = title) }
        val job = scope.launch {
            try {
                block(sinkFor(id))?.let { _messages.tryEmit(it) }
            } catch (e: CancellationException) {
                _messages.tryEmit(UiText.Res(R.string.task_cancelled))
                throw e
            } catch (e: Throwable) {
                _messages.tryEmit(UiText.Res(R.string.task_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                _tasks.update { list -> list.filterNot { it.id == id } }
                jobs.remove(id)
                _changes.tryEmit(Unit)
            }
        }
        jobs[id] = job
        return job
    }

    fun cancel(id: Long) {
        jobs[id]?.cancel()
    }

    private fun sinkFor(id: Long): ProgressSink {
        var lastPublished = 0L
        return ProgressSink { detail, done, total ->
            val now = SystemClock.uptimeMillis()
            val finished = total > 0L && done >= total
            if (finished || now - lastPublished >= PUBLISH_INTERVAL_MS) {
                lastPublished = now
                _tasks.update { list ->
                    list.map { task -> if (task.id == id) task.copy(detail = detail, done = done, total = total) else task }
                }
            }
        }
    }

    private companion object {
        const val PUBLISH_INTERVAL_MS = 100L
    }
}
