package app.filemanagerx.data.fs

import android.system.Os
import app.filemanagerx.domain.FileKinds
import app.filemanagerx.domain.PathUtils
import app.filemanagerx.domain.model.ConflictPolicy
import app.filemanagerx.domain.model.FileItem
import app.filemanagerx.domain.model.ListResult
import app.filemanagerx.domain.model.MountInfo
import app.filemanagerx.domain.model.PermissionInfo
import app.filemanagerx.domain.model.SizeInfo
import com.topjohnwu.superuser.nio.ExtendedFile
import com.topjohnwu.superuser.nio.FileSystemManager
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

class AlreadyExistsException(val path: String) : IOException("Already exists: $path")

class FileTooLargeException(val size: Long, val limit: Long) : IOException("File is $size bytes, limit is $limit")

/**
 * All file system work of the app. Every function switches to [Dispatchers.IO] itself, is safe to
 * cancel and goes through [FileSystems], so it transparently runs as root when root mode is on.
 */
class FileOperations(
    private val fileSystems: FileSystems,
    private val shell: ShellRunner,
) {
    // region Reading

    suspend fun list(path: String): ListResult = withContext(Dispatchers.IO) {
        try {
            val directory = fileSystems.file(path)
            when {
                !directory.exists() -> ListResult.NotFound
                !directory.isDirectory -> ListResult.NotADirectory
                else -> {
                    val children = directory.listFiles() ?: return@withContext ListResult.AccessDenied
                    ListResult.Success(children.map { toItem(it) })
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            ListResult.AccessDenied
        } catch (e: Exception) {
            ListResult.Failure(e.message)
        }
    }

    suspend fun stat(path: String): FileItem? = withContext(Dispatchers.IO) {
        runCatching {
            val file = fileSystems.file(path)
            if (!file.exists() && !file.isSymlink) null else toItem(file)
        }.getOrNull()
    }

    suspend fun isWritable(path: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { fileSystems.file(path).canWrite() }.getOrDefault(false)
    }

    private fun toItem(file: ExtendedFile): FileItem {
        val name = file.name
        val symlink = runCatching { file.isSymlink }.getOrDefault(false)
        val directory = runCatching { file.isDirectory }.getOrDefault(false)
        return FileItem(
            path = PathUtils.normalize(file.path),
            name = name,
            isDirectory = directory,
            isSymlink = symlink,
            size = if (directory) 0L else runCatching { file.length() }.getOrDefault(0L),
            modifiedAt = runCatching { file.lastModified() }.getOrDefault(0L),
            kind = FileKinds.of(name, directory),
            linkTarget = if (symlink) runCatching { file.canonicalPath }.getOrNull() else null,
        )
    }

    // endregion

    // region Create / rename

    suspend fun createDirectory(parent: String, name: String): Result<String> = io {
        val target = fileSystems.file(PathUtils.join(parent, name))
        if (target.exists()) throw AlreadyExistsException(target.path)
        if (!target.mkdirs()) throw IOException("Cannot create folder ${target.path}")
        PathUtils.normalize(target.path)
    }

    suspend fun createFile(parent: String, name: String): Result<String> = io {
        val target = fileSystems.file(PathUtils.join(parent, name))
        if (target.exists()) throw AlreadyExistsException(target.path)
        if (!target.createNewFile()) throw IOException("Cannot create file ${target.path}")
        PathUtils.normalize(target.path)
    }

    suspend fun rename(path: String, newName: String): Result<String> = io {
        val parent = PathUtils.parent(path) ?: throw IOException("The root cannot be renamed")
        val source = fileSystems.file(path)
        val target = fileSystems.file(PathUtils.join(parent, newName))
        if (target.exists()) throw AlreadyExistsException(target.path)
        if (!source.renameTo(target)) throw IOException("Cannot rename ${source.path}")
        PathUtils.normalize(target.path)
    }

    // endregion

    // region Delete / copy / move

    suspend fun delete(paths: List<String>, progress: ProgressSink = ProgressSink.None) {
        withContext(Dispatchers.IO) {
            val job = currentCoroutineContext().job
            paths.forEachIndexed { index, path ->
                job.ensureActive()
                progress.update(PathUtils.name(path), index.toLong(), paths.size.toLong())
                if (shell.isRootActive && shell.execute("rm -rf -- ${shell.quote(path)}").success) {
                    return@forEachIndexed
                }
                if (!deleteRecursive(fileSystems.file(path), job)) throw IOException("Cannot delete $path")
            }
        }
    }

    /** Names in [paths] that already exist inside [destDir]. */
    suspend fun findConflicts(paths: List<String>, destDir: String): List<String> = withContext(Dispatchers.IO) {
        paths.map(PathUtils::name).filter { name ->
            val target = fileSystems.file(PathUtils.join(destDir, name))
            target.exists() || runCatching { target.isSymlink }.getOrDefault(false)
        }
    }

    suspend fun copy(paths: List<String>, destDir: String, policy: ConflictPolicy, progress: ProgressSink) =
        transfer(paths, destDir, policy, progress, move = false)

    suspend fun move(paths: List<String>, destDir: String, policy: ConflictPolicy, progress: ProgressSink) =
        transfer(paths, destDir, policy, progress, move = true)

    private data class Transfer(val source: ExtendedFile, val target: ExtendedFile, val bytes: Long)

    private class TransferState(
        val job: Job,
        val manager: FileSystemManager,
        private val progress: ProgressSink,
        private val total: Long,
    ) {
        private var done = 0L

        fun advance(name: String, bytes: Long) {
            done += bytes
            progress.update(name, done, total)
        }
    }

    private suspend fun transfer(
        paths: List<String>,
        destDir: String,
        policy: ConflictPolicy,
        progress: ProgressSink,
        move: Boolean,
    ) {
        withContext(Dispatchers.IO) {
            val job = currentCoroutineContext().job
            val manager = fileSystems.manager
            val destination = manager.getFile(destDir)
            if (!destination.isDirectory) throw IOException("Destination is not a folder: $destDir")

            val sources = paths.map { manager.getFile(it) }
            for (source in sources) {
                if (isRealDirectory(source) && PathUtils.isSameOrDescendant(destDir, source.path)) {
                    throw IOException("Cannot put a folder inside itself: ${source.path}")
                }
            }
            progress.update("", 0, 0)

            // Cheap path first: a rename is atomic and needs no data copy. Whatever is left needs copying.
            val needsCopy = ArrayList<Pair<ExtendedFile, ExtendedFile>>()
            val normalizedDest = PathUtils.normalize(destDir)
            sources.forEachIndexed { index, source ->
                job.ensureActive()
                if (move && PathUtils.parent(source.path) == normalizedDest) return@forEachIndexed
                val target = resolveTarget(manager, source, destDir, policy, job) ?: return@forEachIndexed
                if (move) {
                    progress.update(source.name, index.toLong(), sources.size.toLong())
                    if (runCatching { source.renameTo(target) }.getOrDefault(false)) return@forEachIndexed
                }
                needsCopy.add(source to target)
            }
            if (needsCopy.isEmpty()) return@withContext

            val queue = needsCopy.map { (source, target) -> Transfer(source, target, measure(source, job)) }
            val state = TransferState(job, manager, progress, queue.sumOf { it.bytes })
            for (item in queue) {
                job.ensureActive()
                if (rootFastPath(item, move, state)) continue
                copyTree(item.source, item.target, state)
                if (move && !deleteRecursive(item.source, job)) {
                    throw IOException("Copied, but cannot remove ${item.source.path}")
                }
            }
        }
    }

    /**
     * In root mode on real file systems `cp -a` / `mv` keep owner, mode, xattrs and SELinux
     * context, which a plain stream copy cannot. FUSE-backed shared storage ignores those anyway.
     */
    private fun rootFastPath(item: Transfer, move: Boolean, state: TransferState): Boolean {
        if (!shell.isRootActive || !carriesMetadata(item.source.path) || !carriesMetadata(item.target.path)) return false
        val tool = if (move) "mv -f" else "cp -a"
        val command = "$tool -- ${shell.quote(item.source.path)} ${shell.quote(item.target.path)}"
        val success = shell.execute(command).success
        if (success) state.advance(item.source.name, item.bytes)
        return success
    }

    private fun carriesMetadata(path: String): Boolean =
        !(path.startsWith("/storage/") || path.startsWith("/sdcard") || path.startsWith("/mnt/") || path.startsWith("/data/media/"))

    /** Returns where [source] should end up, applying [policy] to an existing entry; `null` means skip. */
    private fun resolveTarget(
        manager: FileSystemManager,
        source: ExtendedFile,
        destDir: String,
        policy: ConflictPolicy,
        job: Job,
    ): ExtendedFile? {
        val name = source.name
        val target = manager.getFile(PathUtils.join(destDir, name))
        val occupied = target.exists() || runCatching { target.isSymlink }.getOrDefault(false)
        if (!occupied) return target

        val sameLocation = PathUtils.normalize(source.path) == PathUtils.normalize(target.path)
        return when {
            sameLocation || policy == ConflictPolicy.KeepBoth -> {
                val unique = PathUtils.uniqueName(name) { candidate ->
                    manager.getFile(PathUtils.join(destDir, candidate)).exists()
                }
                manager.getFile(PathUtils.join(destDir, unique))
            }
            policy == ConflictPolicy.Skip -> null
            else -> {
                if (!deleteRecursive(target, job)) throw IOException("Cannot replace ${target.path}")
                target
            }
        }
    }

    private fun copyTree(source: ExtendedFile, target: ExtendedFile, state: TransferState) {
        state.job.ensureActive()
        when {
            source.isSymlink -> copySymlink(source, target)
            source.isDirectory -> {
                if (!target.exists() && !target.mkdirs()) throw IOException("Cannot create ${target.path}")
                source.listFiles()?.forEach { child ->
                    copyTree(child, state.manager.getFile(PathUtils.join(target.path, child.name)), state)
                }
                target.setLastModified(source.lastModified())
            }
            else -> copyFile(source, target, state)
        }
    }

    private fun copyFile(source: ExtendedFile, target: ExtendedFile, state: TransferState) {
        try {
            FsIo.openRead(state.manager, source).use { input ->
                FsIo.openWrite(state.manager, target).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        state.job.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        state.advance(source.name, read.toLong())
                    }
                }
            }
            target.setLastModified(source.lastModified())
        } catch (e: Throwable) {
            runCatching { target.delete() } // never leave a half-written file behind
            throw e
        }
    }

    private fun copySymlink(source: ExtendedFile, target: ExtendedFile) {
        val destination = runCatching { source.canonicalPath }.getOrNull() ?: return
        if (shell.isRootActive) {
            shell.execute("ln -s -- ${shell.quote(destination)} ${shell.quote(target.path)}")
        } else {
            runCatching { Os.symlink(destination, target.path) }
        }
    }

    private fun isRealDirectory(file: ExtendedFile): Boolean = file.isDirectory && !file.isSymlink

    private fun deleteRecursive(file: ExtendedFile, job: Job): Boolean {
        job.ensureActive()
        if (isRealDirectory(file)) {
            for (child in file.listFiles().orEmpty()) {
                if (!deleteRecursive(child, job)) return false
            }
        }
        return file.delete()
    }

    private fun measure(file: ExtendedFile, job: Job): Long {
        job.ensureActive()
        if (file.isSymlink) return 0L
        if (!file.isDirectory) return file.length()
        var total = 0L
        file.listFiles()?.forEach { child -> total += measure(child, job) }
        return total
    }

    // endregion

    // region Analysis

    /** Recursive size of a file or folder; [onProgress] is called now and then while walking. */
    suspend fun computeSize(path: String, onProgress: (SizeInfo) -> Unit = {}): SizeInfo = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext().job
        var bytes = 0L
        var files = 0
        var folders = 0

        fun walk(file: ExtendedFile) {
            job.ensureActive()
            when {
                file.isSymlink -> files++
                file.isDirectory -> {
                    folders++
                    file.listFiles()?.forEach { walk(it) }
                }
                else -> {
                    files++
                    bytes += file.length()
                }
            }
            if ((files + folders) % PROGRESS_EVERY == 0) onProgress(SizeInfo(bytes, files, folders))
        }

        walk(fileSystems.file(path))
        SizeInfo(bytes, files, folders)
    }

    suspend fun checksum(path: String, algorithm: String, onProgress: (Float) -> Unit = {}): String =
        withContext(Dispatchers.IO) {
            val job = currentCoroutineContext().job
            val manager = fileSystems.manager
            val file = manager.getFile(path)
            val total = file.length().coerceAtLeast(1L)
            val digest = MessageDigest.getInstance(algorithm)
            var done = 0L
            FsIo.openRead(manager, file).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    job.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    done += read
                    onProgress(done.toFloat() / total)
                }
            }
            digest.digest().joinToString(separator = "") { "%02x".format(it) }
        }

    suspend fun permissions(path: String): PermissionInfo? = withContext(Dispatchers.IO) {
        if (shell.isRootActive) {
            val output = shell.execute("stat -c '%a|%U|%G|%C' ${shell.quote(path)}")
            val fields = output.stdout.firstOrNull()?.split('|')
            if (output.success && fields != null && fields.size >= 3) {
                PermissionInfo(octal = fields[0], owner = fields[1], group = fields[2], seContext = fields.getOrNull(3))
            } else {
                statWithOs(path)
            }
        } else {
            statWithOs(path)
        }
    }

    // Numeric ids only: the plain Android API cannot resolve names, and we must not spawn su just for a stat.
    private fun statWithOs(path: String): PermissionInfo? = runCatching {
        val stat = Os.lstat(path)
        PermissionInfo(
            octal = "%o".format(stat.st_mode and PERMISSION_MASK),
            owner = stat.st_uid.toString(),
            group = stat.st_gid.toString(),
        )
    }.getOrNull()

    suspend fun setPermissions(path: String, octal: String, recursive: Boolean): Result<Unit> = io {
        check(shell.isRootActive) { "Root mode is off" }
        require(OCTAL_MODE.matches(octal)) { "Invalid mode: $octal" }
        val flag = if (recursive) "-R " else ""
        val output = shell.execute("chmod $flag$octal -- ${shell.quote(path)}")
        if (!output.success) throw IOException(output.stderr.joinToString("\n").ifBlank { "chmod failed" })
    }

    /** The mount backing [path], from `/proc/mounts` (the longest matching mount point wins). */
    suspend fun mountInfo(path: String): MountInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val manager = fileSystems.manager
            val lines = FsIo.openRead(manager, manager.getFile("/proc/mounts")).bufferedReader().use { it.readLines() }
            val normalized = PathUtils.normalize(path)
            lines.mapNotNull { line ->
                val fields = line.split(' ')
                if (fields.size < 4) {
                    null
                } else {
                    MountInfo(
                        device = fields[0],
                        mountPoint = fields[1].replace("\\040", " "),
                        fileSystem = fields[2],
                        readOnly = fields[3].split(',').contains("ro"),
                    )
                }
            }.filter { PathUtils.isSameOrDescendant(normalized, it.mountPoint) }
                .maxByOrNull { it.mountPoint.length }
        }.getOrNull()
    }

    suspend fun remount(mount: MountInfo, readWrite: Boolean): Result<Unit> = io {
        check(shell.isRootActive) { "Root mode is off" }
        val mode = if (readWrite) "rw" else "ro"
        val mountPoint = shell.quote(mount.mountPoint)
        var output = shell.execute("mount -o remount,$mode $mountPoint")
        if (!output.success) output = shell.execute("mount -o remount,$mode ${shell.quote(mount.device)} $mountPoint")
        if (!output.success) throw IOException(output.stderr.joinToString("\n").ifBlank { "remount failed" })
    }

    /**
     * Remounts read/write and schedules the way back to read-only in a detached root process, so the
     * partition is protected again even if the app is killed in between.
     */
    suspend fun remountReadWriteFor(mount: MountInfo, minutes: Int): Result<Unit> {
        require(minutes > 0) { "minutes must be positive" }
        val result = remount(mount, readWrite = true)
        if (result.isFailure) return result
        return io {
            val mountPoint = shell.quote(mount.mountPoint)
            val script = shell.quote("sleep ${minutes * SECONDS_PER_MINUTE}; mount -o remount,ro $mountPoint")
            val output = shell.execute("nohup sh -c $script >/dev/null 2>&1 &")
            if (!output.success) throw IOException(output.stderr.joinToString("\n").ifBlank { "cannot schedule remount" })
        }
    }

    // endregion

    // region Text

    suspend fun readText(path: String, maxBytes: Long = MAX_TEXT_BYTES): Result<String> = io {
        val manager = fileSystems.manager
        val file = manager.getFile(path)
        val length = file.length()
        if (length > maxBytes) throw FileTooLargeException(length, maxBytes)
        FsIo.openRead(manager, file).use { it.readBytes() }.toString(Charsets.UTF_8)
    }

    suspend fun writeText(path: String, text: String): Result<Unit> = io {
        val manager = fileSystems.manager
        FsIo.openWrite(manager, manager.getFile(path)).use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    // endregion

    // region Search

    /** Breadth-first name search below [root]. Emits matches as they are found. */
    fun search(root: String, query: String, showHidden: Boolean): Flow<FileItem> = flow {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return@flow
        val queue = ArrayDeque<ExtendedFile>()
        queue.addLast(fileSystems.file(root))
        var found = 0
        while (queue.isNotEmpty() && found < MAX_SEARCH_RESULTS) {
            currentCoroutineContext().ensureActive()
            val directory = queue.removeFirst()
            if (PathUtils.normalize(directory.path) in SEARCH_SKIPPED_DIRECTORIES) continue
            val children = runCatching { directory.listFiles() }.getOrNull() ?: continue
            for (child in children) {
                val name = child.name
                if (!showHidden && name.startsWith('.')) continue
                if (name.lowercase().contains(needle)) {
                    emit(toItem(child))
                    found++
                }
                if (isRealDirectory(child)) queue.addLast(child)
            }
        }
    }.flowOn(Dispatchers.IO)

    // endregion

    private suspend fun <T> io(block: () -> T): Result<T> = withContext(Dispatchers.IO) { runCatching(block) }

    private companion object {
        const val BUFFER_SIZE = 256 * 1024
        const val PROGRESS_EVERY = 200
        const val SECONDS_PER_MINUTE = 60
        const val PERMISSION_MASK = 0xFFF
        const val MAX_TEXT_BYTES = 5L * 1024 * 1024
        const val MAX_SEARCH_RESULTS = 2_000
        val OCTAL_MODE = Regex("[0-7]{3,4}")
        val SEARCH_SKIPPED_DIRECTORIES = setOf("/proc", "/sys", "/dev", "/apex")
    }
}
