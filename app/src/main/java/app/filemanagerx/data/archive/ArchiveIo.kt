package app.filemanagerx.data.archive

import app.filemanagerx.data.fs.FsIo
import app.filemanagerx.domain.PathUtils
import com.topjohnwu.superuser.nio.FileSystemManager
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.SeekableByteChannel
import java.util.UUID

/** Receives the bytes of the entries an extraction or a test run asked for. */
internal interface EntrySink {
    fun directory(entry: ArchiveEntry)

    /** Returns where to write [entry]'s bytes, or `null` to skip the entry. The reader closes the stream. */
    fun openFile(entry: ArchiveEntry): OutputStream?

    /** Called after every entry that [openFile] accepted, with `success = false` when copying failed. */
    fun fileDone(entry: ArchiveEntry, success: Boolean)
}

/** An opened archive. Not thread-safe; one extraction at a time. */
internal interface ArchiveReader : Closeable {
    val format: ArchiveFormat
    val entries: List<ArchiveEntry>

    /** True when entries are encrypted and no (valid) password was supplied. */
    val requiresPassword: Boolean

    /** Streams every entry accepted by [wanted] to [sink]; [cancel] throws when the job was cancelled. */
    fun extract(wanted: (ArchiveEntry) -> Boolean, sink: EntrySink, cancel: () -> Unit)
}

/**
 * The archive file as the readers need it. Some libraries need a real `java.io.File`; when the
 * app process cannot read the archive (root-only location) it is copied into the cache first.
 */
internal class ArchiveSource(
    private val manager: FileSystemManager,
    val path: String,
    private val cacheDir: File,
) : Closeable {
    private var staged: File? = null

    val name: String get() = PathUtils.name(path)

    fun length(): Long = manager.getFile(path).length()

    /** A file the app process itself can read. */
    fun localFile(): File {
        val direct = File(path)
        if (direct.canRead()) return direct
        staged?.let { return it }
        val folder = File(cacheDir, "archive-${UUID.randomUUID().toString().take(8)}").apply { mkdirs() }
        val target = File(folder, name)
        openInput().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        staged = target
        return target
    }

    /** True when [localFile] is a temporary copy, so edits must be written back. */
    val isStaged: Boolean get() = staged != null

    fun openInput(): InputStream = FsIo.openRead(manager, manager.getFile(path))

    fun openChannel(): SeekableByteChannel = FsIo.openChannelRead(manager, manager.getFile(path))

    /** Copies [modified] (the staged copy, after an edit) back over the original archive. */
    fun writeBack(modified: File) {
        modified.inputStream().use { input ->
            FsIo.openWrite(manager, manager.getFile(path)).use { output -> input.copyTo(output) }
        }
    }

    override fun close() {
        staged?.parentFile?.deleteRecursively()
        staged = null
    }
}

internal const val COPY_BUFFER_SIZE = 128 * 1024

/** Copies [input] to [output], calling [cancel] between chunks so long copies stay cancellable. */
internal fun copyStream(input: InputStream, output: OutputStream, cancel: () -> Unit) {
    val buffer = ByteArray(COPY_BUFFER_SIZE)
    while (true) {
        cancel()
        val read = input.read(buffer)
        if (read < 0) break
        output.write(buffer, 0, read)
    }
}

/** Feeds one entry from [input] into [sink], keeping the sink's success bookkeeping correct. */
internal fun pumpEntry(entry: ArchiveEntry, input: InputStream, sink: EntrySink, cancel: () -> Unit) {
    val output = sink.openFile(entry) ?: return
    var success = false
    try {
        copyStream(input, output, cancel)
        output.close()
        success = true
    } finally {
        if (!success) runCatching { output.close() }
        sink.fileDone(entry, success)
    }
}

/** Reports every written byte to [onBytes]. */
internal class CountingOutputStream(
    private val delegate: OutputStream,
    private val onBytes: (Int) -> Unit,
) : OutputStream() {
    override fun write(b: Int) {
        delegate.write(b)
        onBytes(1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        onBytes(len)
    }

    override fun flush() = delegate.flush()

    override fun close() = delegate.close()
}

/** Discards everything; used by integrity tests. */
internal object NullOutputStream : OutputStream() {
    override fun write(b: Int) = Unit

    override fun write(b: ByteArray, off: Int, len: Int) = Unit
}
