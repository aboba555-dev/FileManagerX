package app.filemanagerx.data.trash

import androidx.compose.runtime.Immutable
import app.filemanagerx.data.fs.FileOperations
import app.filemanagerx.data.fs.FileSystems
import app.filemanagerx.data.fs.FsIo
import app.filemanagerx.data.fs.ProgressSink
import app.filemanagerx.data.fs.StorageVolumes
import app.filemanagerx.domain.PathUtils
import app.filemanagerx.domain.model.ConflictPolicy
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Immutable
data class TrashEntry(
    val id: String,
    /** The `.FileManagerXTrash` folder this entry lives in. */
    val trashRoot: String,
    val originalPath: String,
    val name: String,
    val isDirectory: Boolean,
    val deletedAt: Long,
    val size: Long,
) {
    /** Where the deleted item currently is. */
    val storedPath: String get() = PathUtils.join(PathUtils.join(trashRoot, id), name)
    val originalFolder: String get() = PathUtils.parent(originalPath) ?: PathUtils.ROOT
}

/**
 * Per-volume trash. An item is moved into `<volume>/.FileManagerXTrash/<id>/` (a rename on the same
 * volume, so it is instant) and described by `<id>.info`. Paths outside shared storage, such as
 * `/system` or `/data`, cannot be trashed and are deleted for good.
 */
class TrashManager(
    private val fileSystems: FileSystems,
    private val volumes: StorageVolumes,
    private val operations: FileOperations,
) {
    fun volumeOf(path: String): String? {
        val normalized = PathUtils.normalize(path)
        return volumes.roots().filter { PathUtils.isSameOrDescendant(normalized, it) }.maxByOrNull { it.length }
    }

    fun canTrash(path: String): Boolean {
        val volume = volumeOf(path) ?: return false
        val normalized = PathUtils.normalize(path)
        return normalized != volume && !PathUtils.isSameOrDescendant(normalized, trashRootOf(volume))
    }

    private fun trashRootOf(volume: String) = PathUtils.join(volume, TRASH_DIR)

    /**
     * Deletes [paths]: to the trash when [useTrash] is on and the path lives on shared storage, for good
     * otherwise. Returns true when nothing was deleted permanently.
     */
    suspend fun delete(paths: List<String>, useTrash: Boolean, progress: ProgressSink): Boolean {
        val (trashable, permanent) = if (useTrash) paths.partition(::canTrash) else emptyList<String>() to paths
        if (trashable.isNotEmpty()) moveToTrash(trashable, progress)
        if (permanent.isNotEmpty()) operations.delete(permanent, progress)
        return permanent.isEmpty()
    }

    /** Moves every path to the trash of its volume; callers pass only paths for which [canTrash] is true. */
    suspend fun moveToTrash(paths: List<String>, progress: ProgressSink) {
        val job = currentCoroutineContext()
        withContext(Dispatchers.IO) {
            val manager = fileSystems.manager
            paths.forEachIndexed { index, path ->
                job.ensureActive()
                val volume = checkNotNull(volumeOf(path)) { "No storage volume for $path" }
                val root = trashRootOf(volume)
                manager.getFile(root).mkdirs()
                // Keeps the gallery and media scanner out of the trash.
                manager.getFile(PathUtils.join(root, ".nomedia")).apply { if (!exists()) createNewFile() }

                val id = UUID.randomUUID().toString().replace("-", "").take(12)
                val folder = PathUtils.join(root, id)
                manager.getFile(folder).mkdirs()
                val source = manager.getFile(path)
                val isDirectory = source.isDirectory
                progress.update(PathUtils.name(path), index.toLong(), paths.size.toLong())
                operations.move(listOf(path), folder, ConflictPolicy.KeepBoth, ProgressSink.None)
                writeInfo(root, id, path, isDirectory)
            }
        }
    }

    suspend fun list(): List<TrashEntry> = withContext(Dispatchers.IO) {
        val manager = fileSystems.manager
        volumes.roots().flatMap { volume ->
            val root = trashRootOf(volume)
            val files = manager.getFile(root).listFiles().orEmpty()
            files.filter { it.name.endsWith(INFO_SUFFIX) }.mapNotNull { info ->
                val id = info.name.removeSuffix(INFO_SUFFIX)
                val fields = runCatching { readInfo(info) }.getOrNull() ?: return@mapNotNull null
                val name = fields["name"] ?: return@mapNotNull null
                val stored = manager.getFile(PathUtils.join(PathUtils.join(root, id), name))
                if (!stored.exists()) {
                    runCatching { info.delete() } // content is gone; drop the orphaned description
                    return@mapNotNull null
                }
                TrashEntry(
                    id = id,
                    trashRoot = root,
                    originalPath = fields["path"] ?: return@mapNotNull null,
                    name = name,
                    isDirectory = fields["dir"] == "true",
                    deletedAt = fields["time"]?.toLongOrNull() ?: 0L,
                    size = if (stored.isDirectory) -1L else stored.length(),
                )
            }
        }.sortedByDescending { it.deletedAt }
    }

    /** Moves entries back to where they came from; a name clash keeps both. */
    suspend fun restore(entries: List<TrashEntry>, progress: ProgressSink) {
        withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()
            val manager = fileSystems.manager
            entries.forEachIndexed { index, entry ->
                job.ensureActive()
                progress.update(entry.name, index.toLong(), entries.size.toLong())
                manager.getFile(entry.originalFolder).mkdirs()
                operations.move(listOf(entry.storedPath), entry.originalFolder, ConflictPolicy.KeepBoth, ProgressSink.None)
                removeRecord(entry)
            }
        }
    }

    suspend fun purge(entries: List<TrashEntry>, progress: ProgressSink) {
        withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()
            entries.forEachIndexed { index, entry ->
                job.ensureActive()
                progress.update(entry.name, index.toLong(), entries.size.toLong())
                removeRecord(entry)
            }
        }
    }

    /** Called at startup: entries older than [maxAgeDays] are deleted for good. */
    suspend fun purgeExpired(maxAgeDays: Long = RETENTION_DAYS) {
        val limit = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(maxAgeDays)
        val expired = list().filter { it.deletedAt in 1 until limit }
        if (expired.isNotEmpty()) purge(expired, ProgressSink.None)
    }

    private suspend fun removeRecord(entry: TrashEntry) {
        operations.delete(
            listOf(PathUtils.join(entry.trashRoot, entry.id), PathUtils.join(entry.trashRoot, entry.id + INFO_SUFFIX)),
        )
    }

    private fun writeInfo(root: String, id: String, originalPath: String, isDirectory: Boolean) {
        val manager = fileSystems.manager
        val text = buildString {
            append("path=").append(encode(PathUtils.normalize(originalPath))).append('\n')
            append("name=").append(encode(PathUtils.name(originalPath))).append('\n')
            append("dir=").append(isDirectory).append('\n')
            append("time=").append(System.currentTimeMillis()).append('\n')
        }
        FsIo.openWrite(manager, manager.getFile(PathUtils.join(root, id + INFO_SUFFIX))).use {
            it.write(text.toByteArray(Charsets.UTF_8))
        }
    }

    private fun readInfo(info: java.io.File): Map<String, String> {
        val manager = fileSystems.manager
        val text = FsIo.openRead(manager, info).use { it.readBytes() }.toString(Charsets.UTF_8)
        return text.lineSequence().mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
        }.associate { (key, value) -> key to if (key == "path" || key == "name") decode(value) else value }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")

    companion object {
        const val TRASH_DIR = ".FileManagerXTrash"
        const val RETENTION_DAYS = 30L
        private const val INFO_SUFFIX = ".info"
    }
}
