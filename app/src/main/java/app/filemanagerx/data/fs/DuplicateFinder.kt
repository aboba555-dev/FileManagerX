package app.filemanagerx.data.fs

import com.topjohnwu.superuser.nio.ExtendedFile
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Files with identical content. [size] is the size of one copy. */
data class DuplicateGroup(val size: Long, val paths: List<String>) {
    /** Bytes that would be freed by keeping a single copy. */
    val wasted: Long get() = size * (paths.size - 1)
}

data class DuplicateScanProgress(val phase: Phase, val done: Int, val total: Int) {
    enum class Phase { Listing, Sampling, Hashing }
}

/**
 * Finds duplicates in three passes, each one discarding most candidates before the next, more
 * expensive one: identical size, identical first 64 KB, identical SHA-256 of the whole file.
 * All reads go through [FsIo], so root-only folders are scanned like any other.
 */
class DuplicateFinder(private val fileSystems: FileSystems) {

    suspend fun find(
        root: String,
        includeHidden: Boolean,
        minSize: Long = 1L,
        onProgress: (DuplicateScanProgress) -> Unit = {},
    ): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        val manager = fileSystems.manager
        val job = currentCoroutineContext()

        // Pass 1: walk the tree and bucket regular files by size.
        val bySize = HashMap<Long, MutableList<ExtendedFile>>()
        var seen = 0
        val pending = ArrayDeque<ExtendedFile>()
        pending.addLast(manager.getFile(root))
        while (pending.isNotEmpty()) {
            job.ensureActive()
            val folder = pending.removeLast()
            val children = folder.listFiles() ?: continue
            for (child in children) {
                if (!includeHidden && child.name.startsWith('.')) continue
                if (child.isSymlink) continue
                if (child.isDirectory) {
                    pending.addLast(child)
                } else {
                    val length = child.length()
                    if (length >= minSize) bySize.getOrPut(length) { ArrayList() }.add(child)
                    if (++seen % PROGRESS_STEP == 0) onProgress(DuplicateScanProgress(DuplicateScanProgress.Phase.Listing, seen, 0))
                }
            }
        }
        val sameSize = bySize.filterValues { it.size > 1 }

        // Pass 2: the first 64 KB tells most different files apart without reading them fully.
        val sampleTotal = sameSize.values.sumOf { it.size }
        var sampled = 0
        val sameHead = ArrayList<Pair<Long, List<ExtendedFile>>>()
        for ((size, files) in sameSize) {
            val byHead = HashMap<String, MutableList<ExtendedFile>>()
            for (file in files) {
                job.ensureActive()
                val key = digest(manager, file, limit = HEAD_BYTES)
                if (key != null) byHead.getOrPut(key) { ArrayList() }.add(file)
                onProgress(DuplicateScanProgress(DuplicateScanProgress.Phase.Sampling, ++sampled, sampleTotal))
            }
            byHead.values.filter { it.size > 1 }.forEach { sameHead += size to it }
        }

        // Pass 3: files that are small enough to fit the head are already fully hashed.
        val hashTotal = sameHead.sumOf { it.second.size }
        var hashed = 0
        val groups = ArrayList<DuplicateGroup>()
        for ((size, files) in sameHead) {
            if (size <= HEAD_BYTES) {
                groups += DuplicateGroup(size, files.map { it.path }.sorted())
                hashed += files.size
                continue
            }
            val byHash = HashMap<String, MutableList<ExtendedFile>>()
            for (file in files) {
                job.ensureActive()
                val key = digest(manager, file, limit = Long.MAX_VALUE)
                if (key != null) byHash.getOrPut(key) { ArrayList() }.add(file)
                onProgress(DuplicateScanProgress(DuplicateScanProgress.Phase.Hashing, ++hashed, hashTotal))
            }
            byHash.values.filter { it.size > 1 }.forEach { same ->
                groups += DuplicateGroup(size, same.map { it.path }.sorted())
            }
        }
        groups.sortedByDescending { it.wasted }
    }

    /** SHA-256 over at most [limit] bytes; null when the file cannot be read (permissions, vanished). */
    private suspend fun digest(manager: com.topjohnwu.superuser.nio.FileSystemManager, file: ExtendedFile, limit: Long): String? {
        val job = currentCoroutineContext()
        return try {
            val sha = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_BYTES)
            FsIo.openRead(manager, file).use { input ->
                var remaining = limit
                while (remaining > 0L) {
                    job.ensureActive()
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) break
                    sha.update(buffer, 0, read)
                    remaining -= read
                }
            }
            sha.digest().joinToString("") { "%02x".format(it) }
        } catch (e: java.io.IOException) {
            null
        }
    }

    private companion object {
        const val HEAD_BYTES = 64L * 1024L
        const val BUFFER_BYTES = 64 * 1024
        const val PROGRESS_STEP = 200
    }
}
