package app.filemanagerx.data.archive

import android.content.Context
import app.filemanagerx.data.fs.FileSystems
import app.filemanagerx.data.fs.FsIo
import app.filemanagerx.data.fs.ProgressSink
import app.filemanagerx.domain.PathUtils
import app.filemanagerx.domain.model.ConflictPolicy
import com.topjohnwu.superuser.nio.ExtendedFile
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.Charset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile

/** An opened archive plus everything needed to work on it. Close it when the screen goes away. */
class ArchiveSession internal constructor(
    val path: String,
    val format: ArchiveFormat,
    val entries: List<ArchiveEntry>,
    /** Entries are encrypted and the password given at open time was missing. */
    val requiresPassword: Boolean,
    internal val reader: ArchiveReader,
    internal val source: ArchiveSource,
) : Closeable {
    override fun close() {
        runCatching { reader.close() }
        source.close()
    }
}

/**
 * Facade over the format-specific readers and writers. All archive reads and writes go through
 * [FileSystems], so archives in root-only locations and extraction into them work in root mode.
 */
class ArchiveManager(
    private val context: Context,
    private val fileSystems: FileSystems,
    private val zipCharsetName: () -> String,
) {
    private val cacheDir: File get() = File(context.cacheDir, "archives").apply { mkdirs() }

    /** Cheap name-based check used by the file list to decide what a tap on a file does. */
    fun looksLikeArchive(name: String): Boolean = ArchiveFormat.fromName(name) != null

    /** Opens [path] and lists its entries. Throws [ArchiveException] for password, format and damage problems. */
    suspend fun open(path: String, password: CharArray? = null): ArchiveSession = withContext(Dispatchers.IO) {
        val source = ArchiveSource(fileSystems.manager, path, cacheDir)
        try {
            val format = detect(source) ?: throw ArchiveException.Unsupported("Unknown archive format")
            val reader = createReader(format, source, password)
            ArchiveSession(path, format, reader.entries, reader.requiresPassword, reader, source)
        } catch (e: Throwable) {
            source.close()
            throw when (e) {
                is CancellationException, is ArchiveException -> e
                else -> ArchiveException.Corrupt(e)
            }
        }
    }

    private fun createReader(format: ArchiveFormat, source: ArchiveSource, password: CharArray?): ArchiveReader =
        when (format) {
            ArchiveFormat.Zip -> ZipArchiveReader(source, password, zipCharset())
            ArchiveFormat.SevenZip -> SevenZipArchiveReader(source, password)
            ArchiveFormat.Rar -> RarArchiveReader(source, password)
            ArchiveFormat.Gzip, ArchiveFormat.Bzip2, ArchiveFormat.Xz -> SingleFileArchiveReader(format, source)
            else -> StreamArchiveReader(format, source)
        }

    private fun detect(source: ArchiveSource): ArchiveFormat? {
        ArchiveFormat.fromName(source.name)?.let { return it }
        val header = ByteArray(ArchiveFormat.MAGIC_BYTES)
        val read = source.openInput().use { input ->
            var total = 0
            while (total < header.size) {
                val count = input.read(header, total, header.size - total)
                if (count < 0) break
                total += count
            }
            total
        }
        return ArchiveFormat.fromMagic(header.copyOf(read))
    }

    private fun zipCharset(): Charset = runCatching { Charset.forName(zipCharsetName()) }.getOrDefault(Charsets.UTF_8)

    /** Extracts into [ExtractRequest.destinationDir]; returns the number of files written. */
    suspend fun extract(session: ArchiveSession, request: ExtractRequest, progress: ProgressSink): Int =
        withContext(Dispatchers.IO) {
            val job = currentCoroutineContext().job
            val manager = fileSystems.manager
            val selection = request.selection
            val wanted: (ArchiveEntry) -> Boolean = { entry ->
                selection == null || selection.any { entry.path == it || entry.path.startsWith("$it/") }
            }
            val total = session.entries.filter { !it.isDirectory && wanted(it) }.sumOf { it.size.coerceAtLeast(0L) }
            val destinationRoot = PathUtils.normalize(request.destinationDir)
            manager.getFile(destinationRoot).mkdirs()
            progress.update("", 0, if (total > 0L) total else 0L)

            var current: ExtendedFile? = null
            var extracted = 0
            var done = 0L
            val sink = object : EntrySink {
                override fun directory(entry: ArchiveEntry) {
                    val target = safeTargetPath(destinationRoot, entry.path) ?: return
                    manager.getFile(target).mkdirs()
                }

                override fun openFile(entry: ArchiveEntry): OutputStream? {
                    val targetPath = safeTargetPath(destinationRoot, entry.path) ?: return null
                    var file = manager.getFile(targetPath)
                    file.parentFile?.mkdirs()
                    if (file.exists()) {
                        when (request.conflictPolicy) {
                            ConflictPolicy.Skip -> return null
                            ConflictPolicy.Replace -> {
                                if (file.isDirectory || !file.delete()) throw IOException("Cannot replace ${file.path}")
                            }
                            ConflictPolicy.KeepBoth -> {
                                val parent = PathUtils.parent(targetPath) ?: destinationRoot
                                val unique = PathUtils.uniqueName(file.name) { name ->
                                    manager.getFile(PathUtils.join(parent, name)).exists()
                                }
                                file = manager.getFile(PathUtils.join(parent, unique))
                            }
                        }
                    }
                    current = file
                    return CountingOutputStream(FsIo.openWrite(manager, file)) { bytes ->
                        done += bytes
                        progress.update(entry.name, done, total)
                    }
                }

                override fun fileDone(entry: ArchiveEntry, success: Boolean) {
                    val file = current ?: return
                    current = null
                    if (success) {
                        extracted++
                        if (entry.modifiedAt > 0L) file.setLastModified(entry.modifiedAt)
                    } else {
                        runCatching { file.delete() } // never leave a half-written file behind
                    }
                }
            }
            session.reader.extract(wanted, sink) { job.ensureActive() }
            extracted
        }

    /** Reads every entry and lets the format's own checksums (CRC, MAC) catch damage. */
    suspend fun test(session: ArchiveSession, progress: ProgressSink) {
        withContext(Dispatchers.IO) {
            val job = currentCoroutineContext().job
            val total = session.entries.filter { !it.isDirectory }.sumOf { it.size.coerceAtLeast(0L) }
            var done = 0L
            val sink = object : EntrySink {
                override fun directory(entry: ArchiveEntry) = Unit

                override fun openFile(entry: ArchiveEntry): OutputStream = CountingOutputStream(NullOutputStream) { bytes ->
                    done += bytes
                    progress.update(entry.name, done, total)
                }

                override fun fileDone(entry: ArchiveEntry, success: Boolean) = Unit
            }
            session.reader.extract({ true }, sink) { job.ensureActive() }
        }
    }

    /** Creates a new archive; returns the path that was actually written (the name is made unique). */
    suspend fun create(request: CreateArchiveRequest, progress: ProgressSink): String = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext().job
        val manager = fileSystems.manager
        if (!request.format.canCreate) throw ArchiveException.Unsupported("Cannot create ${request.format.displayName}")
        if (request.password != null && !request.format.supportsPassword) {
            throw ArchiveException.Unsupported("${request.format.displayName} cannot be password protected")
        }
        val parent = PathUtils.parent(request.outputPath) ?: PathUtils.ROOT
        val name = PathUtils.uniqueName(PathUtils.name(request.outputPath)) { candidate ->
            manager.getFile(PathUtils.join(parent, candidate)).exists()
        }
        val destination = manager.getFile(PathUtils.join(parent, name))
        progress.update("", 0, 0)

        val writer = ArchiveWriter(manager, job, progress)
        val nodes = writer.collect(request.sources.map { manager.getFile(it) })
        try {
            writer.write(nodes, request, destination)
        } catch (e: Throwable) {
            runCatching { destination.delete() } // never leave a broken archive behind
            throw e
        }
        PathUtils.normalize(destination.path)
    }

    /**
     * Removes entries (and everything below selected folders) from a ZIP in place. Only ZIP can be
     * edited; the caller should reopen the archive afterwards.
     */
    suspend fun deleteEntries(session: ArchiveSession, paths: Set<String>) {
        withContext(Dispatchers.IO) {
            if (session.format != ArchiveFormat.Zip) throw ArchiveException.Unsupported("Only ZIP archives can be edited")
            val local = session.source.localFile()
            ZipFile(local).use { zip ->
                zip.charset = zipCharset()
                val targets = zip.fileHeaders.filter { header ->
                    val entryPath = normalizeEntryPath(header.fileName)
                    paths.any { entryPath == it || entryPath.startsWith("$it/") }
                }
                // Files first; removing a folder entry may already take its children with it.
                targets.filterNot { it.isDirectory }.map { it.fileName }.forEach { zip.removeFile(it) }
                targets.filter { it.isDirectory }.map { it.fileName }.forEach { name ->
                    zip.getFileHeader(name)?.let { zip.removeFile(it) }
                }
            }
            if (session.source.isStaged) session.source.writeBack(local)
        }
    }
}
