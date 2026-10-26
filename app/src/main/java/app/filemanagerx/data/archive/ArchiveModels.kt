package app.filemanagerx.data.archive

import androidx.compose.runtime.Immutable
import app.filemanagerx.domain.PathUtils
import app.filemanagerx.domain.model.ConflictPolicy

/** Archive and compressed-stream formats the app can open. Not every one can also be written. */
enum class ArchiveFormat(
    val displayName: String,
    val canCreate: Boolean,
    val supportsPassword: Boolean,
    /** Preferred file extension when creating, including the leading dot. */
    val extension: String,
) {
    Zip("ZIP", canCreate = true, supportsPassword = true, extension = ".zip"),
    SevenZip("7z", canCreate = true, supportsPassword = false, extension = ".7z"),
    Tar("TAR", canCreate = true, supportsPassword = false, extension = ".tar"),
    TarGzip("TAR.GZ", canCreate = true, supportsPassword = false, extension = ".tar.gz"),
    TarBzip2("TAR.BZ2", canCreate = true, supportsPassword = false, extension = ".tar.bz2"),
    TarXz("TAR.XZ", canCreate = true, supportsPassword = false, extension = ".tar.xz"),
    Rar("RAR", canCreate = false, supportsPassword = true, extension = ".rar"),
    Gzip("GZ", canCreate = false, supportsPassword = false, extension = ".gz"),
    Bzip2("BZ2", canCreate = false, supportsPassword = false, extension = ".bz2"),
    Xz("XZ", canCreate = false, supportsPassword = false, extension = ".xz"),
    Cpio("CPIO", canCreate = false, supportsPassword = false, extension = ".cpio"),
    Ar("AR", canCreate = false, supportsPassword = false, extension = ".ar"),
    Arj("ARJ", canCreate = false, supportsPassword = false, extension = ".arj"),
    ;

    companion object {
        val creatable: List<ArchiveFormat> = entries.filter { it.canCreate }

        /** Guesses the format from a file name alone; `null` when the name is not a known archive. */
        fun fromName(name: String): ArchiveFormat? {
            val lower = name.lowercase()
            return when {
                lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> TarGzip
                lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") || lower.endsWith(".tbz") -> TarBzip2
                lower.endsWith(".tar.xz") || lower.endsWith(".txz") -> TarXz
                lower.endsWith(".tar") -> Tar
                lower.endsWith(".zip") || lower.endsWith(".jar") || lower.endsWith(".apk") || lower.endsWith(".aar") ||
                    lower.endsWith(".war") || lower.endsWith(".xapk") || lower.endsWith(".apks") || lower.endsWith(".apkm") ||
                    lower.endsWith(".epub") || lower.endsWith(".docx") || lower.endsWith(".xlsx") || lower.endsWith(".pptx") ||
                    lower.endsWith(".odt") -> Zip
                lower.endsWith(".7z") -> SevenZip
                lower.endsWith(".rar") -> Rar
                lower.endsWith(".gz") -> Gzip
                lower.endsWith(".bz2") -> Bzip2
                lower.endsWith(".xz") -> Xz
                lower.endsWith(".cpio") -> Cpio
                lower.endsWith(".ar") || lower.endsWith(".a") || lower.endsWith(".deb") -> Ar
                lower.endsWith(".arj") -> Arj
                else -> null
            }
        }

        /** Guesses the format from the first bytes of a file. */
        fun fromMagic(header: ByteArray): ArchiveFormat? {
            fun starts(vararg bytes: Int): Boolean =
                header.size >= bytes.size && bytes.indices.all { (header[it].toInt() and 0xFF) == bytes[it] }
            return when {
                starts(0x50, 0x4B, 0x03, 0x04) || starts(0x50, 0x4B, 0x05, 0x06) -> Zip
                starts(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> SevenZip
                starts(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07) -> Rar
                starts(0x1F, 0x8B) -> Gzip
                starts(0x42, 0x5A, 0x68) -> Bzip2
                starts(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) -> Xz
                starts(0x60, 0xEA) -> Arj
                starts(0x21, 0x3C, 0x61, 0x72, 0x63, 0x68, 0x3E) -> Ar
                header.size > TAR_MAGIC_OFFSET + 5 &&
                    String(header, TAR_MAGIC_OFFSET, 5, Charsets.ISO_8859_1) == "ustar" -> Tar
                else -> null
            }
        }

        /** Number of leading bytes [fromMagic] wants to look at. */
        const val MAGIC_BYTES = 512
        private const val TAR_MAGIC_OFFSET = 257
    }
}

/** One entry of an opened archive. [path] uses `/` separators and has no leading or trailing slash. */
@Immutable
data class ArchiveEntry(
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    /** Compressed size, or -1 when the format does not expose it. */
    val packedSize: Long,
    val modifiedAt: Long,
    val isEncrypted: Boolean,
) {
    val name: String get() = path.substringAfterLast('/')
    val parentPath: String get() = path.substringBeforeLast('/', missingDelimiterValue = "")
}

/** Where one extraction ends up and how clashes are handled. */
data class ExtractRequest(
    val destinationDir: String,
    /** Paths (files or folders) to extract; `null` means everything. */
    val selection: Set<String>? = null,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.KeepBoth,
)

enum class CompressionLevel(val zip4jLevel: Int, val xzPreset: Int, val gzipLevel: Int) {
    Store(0, 0, 0),
    Fast(1, 1, 1),
    Normal(5, 6, 6),
    Maximum(9, 9, 9),
}

data class CreateArchiveRequest(
    val sources: List<String>,
    val outputPath: String,
    val format: ArchiveFormat,
    val level: CompressionLevel = CompressionLevel.Normal,
    val password: CharArray? = null,
)

/** Failures that the UI treats specially. */
sealed class ArchiveException(message: String?, cause: Throwable? = null) : Exception(message, cause) {
    /** The archive is encrypted and no password was supplied. */
    class PasswordRequired : ArchiveException("Password required")

    /** A password was supplied but does not decrypt the archive. */
    class WrongPassword : ArchiveException("Wrong password")

    class Unsupported(reason: String) : ArchiveException(reason)

    class Corrupt(cause: Throwable?) : ArchiveException(cause?.message ?: "Archive is damaged", cause)
}

internal fun normalizeEntryPath(raw: String): String = raw.replace('\\', '/').trim('/')

/**
 * Resolves an archive entry name to a path below [root], or `null` when the name tries to escape
 * it (the classic "zip slip": `../../evil`) or is empty. Every extractor must go through this.
 */
internal fun safeTargetPath(root: String, entryPath: String): String? {
    val segments = normalizeEntryPath(entryPath).split('/').filter { it.isNotEmpty() && it != "." }
    if (segments.isEmpty() || segments.any { it == ".." }) return null
    return PathUtils.join(root, segments.joinToString("/"))
}
