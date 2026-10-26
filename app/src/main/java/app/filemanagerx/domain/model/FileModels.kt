package app.filemanagerx.domain.model

import androidx.compose.runtime.Immutable
import app.filemanagerx.domain.NaturalOrder
import app.filemanagerx.domain.PathUtils

/** Coarse classification used for icons, colours and "open with" decisions. */
enum class FileKind { Folder, Image, Video, Audio, Archive, Document, Text, Code, Apk, Other }

/** One row in a directory listing. Immutable so Compose can skip unchanged rows. */
@Immutable
data class FileItem(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val size: Long,
    val modifiedAt: Long,
    val kind: FileKind,
    val linkTarget: String? = null,
) {
    val isHidden: Boolean get() = name.startsWith('.')
    val extension: String get() = PathUtils.extension(name)
}

enum class SortField { Name, Size, Modified, Type }

enum class ViewMode { List, Grid }

enum class ThemeMode { System, Light, Dark }

/** What to do when a copy/move/extract target already exists. */
enum class ConflictPolicy { KeepBoth, Replace, Skip }

@Immutable
data class SortSpec(
    val field: SortField = SortField.Name,
    val ascending: Boolean = true,
    val foldersFirst: Boolean = true,
) {
    fun comparator(): Comparator<FileItem> {
        val byName = Comparator<FileItem> { a, b -> NaturalOrder.compare(a.name, b.name) }
        val byField: Comparator<FileItem> = when (field) {
            SortField.Name -> byName
            SortField.Size -> compareBy<FileItem> { it.size }.then(byName)
            SortField.Modified -> compareBy<FileItem> { it.modifiedAt }.then(byName)
            SortField.Type -> compareBy<FileItem> { it.extension }.then(byName)
        }
        val directed = if (ascending) byField else byField.reversed()
        if (!foldersFirst) return directed
        return Comparator { a, b ->
            val byFolder = b.isDirectory.compareTo(a.isDirectory)
            if (byFolder != 0) byFolder else directed.compare(a, b)
        }
    }
}

/** Result of listing one directory. */
sealed interface ListResult {
    data class Success(val items: List<FileItem>) : ListResult
    data object AccessDenied : ListResult
    data object NotFound : ListResult
    data object NotADirectory : ListResult
    data class Failure(val message: String?) : ListResult
}

@Immutable
data class SizeInfo(val bytes: Long, val files: Int, val folders: Int)

/** POSIX permission bits plus ownership. [seContext] is only known when running as root. */
@Immutable
data class PermissionInfo(
    val octal: String,
    val owner: String,
    val group: String,
    val seContext: String? = null,
)

/** The mount that backs a path; used to offer "remount read/write" for system partitions. */
@Immutable
data class MountInfo(
    val device: String,
    val mountPoint: String,
    val fileSystem: String,
    val readOnly: Boolean,
)

enum class ClipMode { Copy, Cut }

@Immutable
data class ClipboardContent(val paths: List<String>, val mode: ClipMode)
