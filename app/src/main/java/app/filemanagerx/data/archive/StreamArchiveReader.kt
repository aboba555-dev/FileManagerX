package app.filemanagerx.data.archive

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.arj.ArjArchiveInputStream
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

/**
 * Formats that can only be read front to back: TAR (plain, gz, bz2, xz), CPIO, AR/DEB and ARJ.
 * Listing therefore reads the whole archive once; extraction reads it again.
 */
internal class StreamArchiveReader(
    override val format: ArchiveFormat,
    private val source: ArchiveSource,
) : ArchiveReader {
    override val entries: List<ArchiveEntry> = scan()

    override val requiresPassword: Boolean = false

    private fun open(): ArchiveInputStream<*> {
        val raw = BufferedInputStream(source.openInput(), STREAM_BUFFER)
        return try {
            when (format) {
                ArchiveFormat.Tar -> TarArchiveInputStream(raw)
                ArchiveFormat.TarGzip -> TarArchiveInputStream(BufferedInputStream(GzipCompressorInputStream(raw), STREAM_BUFFER))
                ArchiveFormat.TarBzip2 -> TarArchiveInputStream(BufferedInputStream(BZip2CompressorInputStream(raw), STREAM_BUFFER))
                ArchiveFormat.TarXz -> TarArchiveInputStream(BufferedInputStream(XZCompressorInputStream(raw), STREAM_BUFFER))
                ArchiveFormat.Cpio -> CpioArchiveInputStream(raw)
                ArchiveFormat.Ar -> ArArchiveInputStream(raw)
                ArchiveFormat.Arj -> ArjArchiveInputStream(raw)
                else -> throw ArchiveException.Unsupported("${format.displayName} is not a stream archive")
            }
        } catch (e: IOException) {
            raw.close()
            throw ArchiveException.Corrupt(e)
        }
    }

    private fun scan(): List<ArchiveEntry> {
        val result = ArrayList<ArchiveEntry>()
        try {
            open().use { stream ->
                while (true) {
                    val raw = stream.nextEntry ?: break
                    if (!isRegularOrDirectory(raw)) continue
                    result += toEntry(raw)
                }
            }
        } catch (e: IOException) {
            throw ArchiveException.Corrupt(e)
        }
        return result
    }

    private fun isRegularOrDirectory(entry: org.apache.commons.compress.archivers.ArchiveEntry): Boolean =
        if (entry is TarArchiveEntry) entry.isDirectory || entry.isFile else true

    private fun toEntry(raw: org.apache.commons.compress.archivers.ArchiveEntry): ArchiveEntry = ArchiveEntry(
        path = normalizeEntryPath(raw.name),
        isDirectory = raw.isDirectory,
        size = raw.size,
        packedSize = -1L,
        modifiedAt = raw.lastModifiedDate?.time ?: 0L,
        isEncrypted = false,
    )

    override fun extract(wanted: (ArchiveEntry) -> Boolean, sink: EntrySink, cancel: () -> Unit) {
        try {
            open().use { stream ->
                while (true) {
                    cancel()
                    val raw = stream.nextEntry ?: break
                    if (!isRegularOrDirectory(raw)) continue
                    val entry = toEntry(raw)
                    if (entry.path.isEmpty() || !wanted(entry)) continue
                    if (entry.isDirectory) {
                        sink.directory(entry)
                    } else {
                        pumpEntry(entry, stream, sink, cancel)
                    }
                }
            }
        } catch (e: IOException) {
            throw ArchiveException.Corrupt(e)
        }
    }

    override fun close() = Unit

    private companion object {
        const val STREAM_BUFFER = 64 * 1024
    }
}

/** `.gz`, `.bz2` and `.xz` files that are not tarballs: one virtual entry holding the decompressed bytes. */
internal class SingleFileArchiveReader(
    override val format: ArchiveFormat,
    private val source: ArchiveSource,
) : ArchiveReader {
    override val entries: List<ArchiveEntry> = listOf(
        ArchiveEntry(
            path = source.name.removeSuffix(format.extension).ifEmpty { source.name },
            isDirectory = false,
            size = -1L,
            packedSize = source.length(),
            modifiedAt = 0L,
            isEncrypted = false,
        ),
    )

    override val requiresPassword: Boolean = false

    private fun open(): InputStream {
        val raw = BufferedInputStream(source.openInput(), STREAM_BUFFER)
        return try {
            when (format) {
                ArchiveFormat.Gzip -> GzipCompressorInputStream(raw, true)
                ArchiveFormat.Bzip2 -> BZip2CompressorInputStream(raw, true)
                ArchiveFormat.Xz -> XZCompressorInputStream(raw, true)
                else -> throw ArchiveException.Unsupported("${format.displayName} is not a compressed stream")
            }
        } catch (e: IOException) {
            raw.close()
            throw ArchiveException.Corrupt(e)
        }
    }

    override fun extract(wanted: (ArchiveEntry) -> Boolean, sink: EntrySink, cancel: () -> Unit) {
        val entry = entries.first()
        if (!wanted(entry)) return
        try {
            open().use { input -> pumpEntry(entry, input, sink, cancel) }
        } catch (e: IOException) {
            throw ArchiveException.Corrupt(e)
        }
    }

    override fun close() = Unit

    private companion object {
        const val STREAM_BUFFER = 64 * 1024
    }
}
