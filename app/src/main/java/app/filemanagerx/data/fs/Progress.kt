package app.filemanagerx.data.fs

/**
 * Receives progress from long-running operations. [total] is 0 while the amount of work is not
 * known yet (the UI then shows an indeterminate bar).
 */
fun interface ProgressSink {
    fun update(detail: String, done: Long, total: Long)

    companion object {
        val None = ProgressSink { _, _, _ -> }
    }
}
