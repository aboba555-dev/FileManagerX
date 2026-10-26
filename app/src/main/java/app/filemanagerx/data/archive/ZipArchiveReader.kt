package app.filemanagerx.data.archive

import java.nio.charset.Charset
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.FileHeader

/**
 * ZIP through zip4j: supports AES-128/192/256 and legacy ZipCrypto passwords, Zip64 and split
 * archives. [charset] applies to entry names without the UTF-8 flag (old Windows tools).
 */
internal class ZipArchiveReader(
    source: ArchiveSource,
    private val password: CharArray?,
    charset: Charset,
) : ArchiveReader {
    override val format = ArchiveFormat.Zip

    private val zip: ZipFile = run {
        val file = source.localFile()
        val archive = if (password != null) ZipFile(file, password) else ZipFile(file)
        archive.charset = charset
        archive
    }

    override val entries: List<ArchiveEntry>

    override val requiresPassword: Boolean

    init {
        try {
            if (!zip.isValidZipFile) throw ArchiveException.Unsupported("Not a valid ZIP archive")
            entries = zip.fileHeaders.mapNotNull { toEntry(it) }
            requiresPassword = password == null && entries.any { it.isEncrypted }
            if (password != null) verifyPassword()
        } catch (e: ArchiveException) {
            zip.close()
            throw e
        } catch (e: ZipException) {
            zip.close()
            throw ArchiveException.Corrupt(e)
        }
    }

    private fun toEntry(header: FileHeader): ArchiveEntry? {
        val path = normalizeEntryPath(header.fileName)
        if (path.isEmpty()) return null
        return ArchiveEntry(
            path = path,
            isDirectory = header.isDirectory,
            size = header.uncompressedSize,
            packedSize = header.compressedSize,
            modifiedAt = header.lastModifiedTimeEpoch,
            isEncrypted = header.isEncrypted,
        )
    }

    /** Reads the first byte of one encrypted file: AES checks a 2-byte verifier, ZipCrypto a check byte. */
    private fun verifyPassword() {
        val header = zip.fileHeaders.firstOrNull { it.isEncrypted && !it.isDirectory && it.uncompressedSize > 0 } ?: return
        try {
            zip.getInputStream(header).use { it.read() }
        } catch (e: ZipException) {
            throw translate(e, encrypted = true)
        }
    }

    override fun extract(wanted: (ArchiveEntry) -> Boolean, sink: EntrySink, cancel: () -> Unit) {
        for (header in zip.fileHeaders) {
            cancel()
            val entry = toEntry(header) ?: continue
            if (!wanted(entry)) continue
            if (entry.isDirectory) {
                sink.directory(entry)
                continue
            }
            if (entry.isEncrypted && password == null) throw ArchiveException.PasswordRequired()
            try {
                zip.getInputStream(header).use { input -> pumpEntry(entry, input, sink, cancel) }
            } catch (e: ZipException) {
                throw translate(e, entry.isEncrypted)
            }
        }
    }

    private fun translate(error: ZipException, encrypted: Boolean): ArchiveException = when {
        error.type == ZipException.Type.WRONG_PASSWORD -> ArchiveException.WrongPassword()
        // ZipCrypto has a 1-in-256 chance of accepting a wrong password; the CRC then fails at the end.
        encrypted && error.type == ZipException.Type.CHECKSUM_MISMATCH -> ArchiveException.WrongPassword()
        else -> ArchiveException.Corrupt(error)
    }

    override fun close() = zip.close()
}
