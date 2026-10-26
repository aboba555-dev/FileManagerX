package app.filemanagerx.data.archive

import java.io.IOException
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile

/**
 * 7z through Commons Compress. Handles AES-256 encrypted content and encrypted headers.
 * The listing instance is closed right after the entries are read; every extraction opens a fresh
 * one so the strictly sequential decoder always starts at the first entry.
 */
internal class SevenZipArchiveReader(
    private val source: ArchiveSource,
    private val password: CharArray?,
) : ArchiveReader {
    override val format = ArchiveFormat.SevenZip

    override val entries: List<ArchiveEntry>

    override val requiresPassword: Boolean

    init {
        val listing = openFile()
        try {
            entries = listing.entries.map { toEntry(it) }.filter { it.path.isNotEmpty() }
            requiresPassword = password == null && probeEncryption(listing)
            if (password != null) probePassword(listing)
        } finally {
            listing.close()
        }
    }

    private fun openFile(): SevenZFile {
        val builder = SevenZFile.builder().setSeekableByteChannel(source.openChannel())
        if (password != null) builder.setPassword(password)
        try {
            return builder.get()
        } catch (e: PasswordRequiredException) {
            throw ArchiveException.PasswordRequired()
        } catch (e: IOException) {
            // With a password, a failure while reading the (encrypted) header means the password is wrong.
            throw if (password != null) ArchiveException.WrongPassword() else ArchiveException.Corrupt(e)
        }
    }

    private fun toEntry(entry: SevenZArchiveEntry): ArchiveEntry = ArchiveEntry(
        path = normalizeEntryPath(entry.name.orEmpty()),
        isDirectory = entry.isDirectory,
        size = entry.size,
        packedSize = -1L,
        modifiedAt = if (entry.hasLastModifiedDate) entry.lastModifiedDate.time else 0L,
        isEncrypted = false,
    )

    /** Content encryption is only discovered by trying to read; do that on the first non-empty file. */
    private fun probeEncryption(file: SevenZFile): Boolean {
        val first = file.entries.firstOrNull { !it.isDirectory && it.size > 0L } ?: return false
        return try {
            file.getInputStream(first).use { it.read() }
            false
        } catch (e: PasswordRequiredException) {
            true
        } catch (e: IOException) {
            false
        }
    }

    private fun probePassword(file: SevenZFile) {
        val first = file.entries.firstOrNull { !it.isDirectory && it.size > 0L } ?: return
        try {
            file.getInputStream(first).use { it.read() }
        } catch (e: IOException) {
            throw ArchiveException.WrongPassword()
        }
    }

    override fun extract(wanted: (ArchiveEntry) -> Boolean, sink: EntrySink, cancel: () -> Unit) {
        val file = openFile()
        var anyDone = false
        try {
            while (true) {
                cancel()
                val raw = file.nextEntry ?: break
                val entry = toEntry(raw)
                if (entry.path.isEmpty() || !wanted(entry)) continue
                if (entry.isDirectory) {
                    sink.directory(entry)
                    continue
                }
                pumpEntry(entry, SevenZEntryInputStream(file), sink, cancel)
                anyDone = true
            }
        } catch (e: PasswordRequiredException) {
            throw ArchiveException.PasswordRequired()
        } catch (e: IOException) {
            throw if (password != null && !anyDone) ArchiveException.WrongPassword() else ArchiveException.Corrupt(e)
        } finally {
            file.close()
        }
    }

    override fun close() = Unit
}

/** Reads the current entry of a sequentially positioned [SevenZFile]. */
private class SevenZEntryInputStream(private val file: SevenZFile) : java.io.InputStream() {
    override fun read(): Int = file.read()

    override fun read(b: ByteArray, off: Int, len: Int): Int = file.read(b, off, len)
}
