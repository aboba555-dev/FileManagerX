package app.filemanagerx.data.archive

import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader

/**
 * RAR (versions 1.5 to 4.x) through junrar, with password support.
 * RAR5 archives are detected and reported as unsupported: there is no pure-JVM RAR5 decoder yet.
 */
internal class RarArchiveReader(
    source: ArchiveSource,
    private val password: CharArray?,
) : ArchiveReader {
    override val format = ArchiveFormat.Rar

    private val archive: Archive = try {
        Archive(source.localFile(), password?.concatToString())
    } catch (e: ArchiveException) {
        throw e
    } catch (e: Exception) {
        throw translate(e)
    }

    private val headers: List<FileHeader> = archive.fileHeaders.filter { !it.fileName.isNullOrEmpty() }

    override val entries: List<ArchiveEntry> = headers.map { toEntry(it) }

    override val requiresPassword: Boolean = password == null && entries.any { it.isEncrypted }

    private fun toEntry(header: FileHeader): ArchiveEntry = ArchiveEntry(
        path = normalizeEntryPath(header.fileName),
        isDirectory = header.isDirectory,
        size = header.fullUnpackSize,
        packedSize = -1L,
        modifiedAt = header.mTime?.time ?: 0L,
        isEncrypted = header.isEncrypted,
    )

    override fun extract(wanted: (ArchiveEntry) -> Boolean, sink: EntrySink, cancel: () -> Unit) {
        var anyDone = false
        for (header in headers) {
            cancel()
            val entry = toEntry(header)
            if (entry.path.isEmpty() || !wanted(entry)) continue
            if (entry.isDirectory) {
                sink.directory(entry)
                continue
            }
            if (entry.isEncrypted && password == null) throw ArchiveException.PasswordRequired()
            val output = sink.openFile(entry) ?: continue
            var success = false
            try {
                archive.extractFile(header, CancellableOutputStream(output, cancel))
                output.close()
                success = true
                anyDone = true
            } catch (e: ArchiveException) {
                throw e
            } catch (e: Exception) {
                cancel()
                throw if (entry.isEncrypted && password != null && !anyDone) ArchiveException.WrongPassword() else translate(e)
            } finally {
                if (!success) runCatching { output.close() }
                sink.fileDone(entry, success)
            }
        }
    }

    private fun translate(error: Exception): ArchiveException {
        val kind = error.javaClass.simpleName
        return when {
            kind.contains("V5") -> ArchiveException.Unsupported("RAR5 archives are not supported yet")
            kind.contains("Encrypted") -> ArchiveException.PasswordRequired()
            else -> ArchiveException.Corrupt(error)
        }
    }

    override fun close() = archive.close()
}

/** junrar writes through this; it lets a cancelled job abort in the middle of a large file. */
private class CancellableOutputStream(
    private val delegate: java.io.OutputStream,
    private val cancel: () -> Unit,
) : java.io.OutputStream() {
    override fun write(b: Int) {
        cancel()
        delegate.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        cancel()
        delegate.write(b, off, len)
    }

    override fun flush() = delegate.flush()

    /** Closing is the caller's job; junrar must not close the sink on its own. */
    override fun close() = Unit
}
