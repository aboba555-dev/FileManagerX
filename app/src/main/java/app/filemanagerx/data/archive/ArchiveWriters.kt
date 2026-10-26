package app.filemanagerx.data.archive

import app.filemanagerx.data.fs.FsIo
import app.filemanagerx.data.fs.ProgressSink
import com.topjohnwu.superuser.nio.ExtendedFile
import com.topjohnwu.superuser.nio.FileSystemManager
import java.io.OutputStream
import java.util.Date
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel as ZipLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipParameters
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream

/** One file or folder to be stored. Folder entry names end with `/`. */
internal data class ArchiveNode(val file: ExtendedFile, val entryName: String, val isDirectory: Boolean)

/** Streams a set of [ArchiveNode]s into a new archive. Writes straight to the destination, no temp copy. */
internal class ArchiveWriter(
    private val manager: FileSystemManager,
    private val job: Job,
    private val progress: ProgressSink,
) {
    private var done = 0L
    private var total = 0L

    /** Flattens [sources] into nodes (folders first, then their children, sorted by name). */
    fun collect(sources: List<ExtendedFile>): List<ArchiveNode> {
        val nodes = ArrayList<ArchiveNode>()

        fun walk(file: ExtendedFile, name: String) {
            job.ensureActive()
            when {
                file.isSymlink -> Unit // links are not stored; their targets are archived on their own if selected
                file.isDirectory -> {
                    nodes += ArchiveNode(file, "$name/", isDirectory = true)
                    file.listFiles()?.sortedBy { it.name }?.forEach { child -> walk(child, "$name/${child.name}") }
                }
                else -> nodes += ArchiveNode(file, name, isDirectory = false)
            }
        }

        for (source in sources) walk(source, source.name)
        total = nodes.filterNot { it.isDirectory }.sumOf { it.file.length() }
        return nodes
    }

    fun write(nodes: List<ArchiveNode>, request: CreateArchiveRequest, destination: ExtendedFile) {
        when (request.format) {
            ArchiveFormat.Zip -> FsIo.openWrite(manager, destination).use { writeZip(nodes, request, it) }
            ArchiveFormat.SevenZip -> writeSevenZip(nodes, request, destination)
            ArchiveFormat.Tar,
            ArchiveFormat.TarGzip,
            ArchiveFormat.TarBzip2,
            ArchiveFormat.TarXz,
            -> FsIo.openWrite(manager, destination).use { writeTar(nodes, request, it) }
            else -> throw ArchiveException.Unsupported("Creating ${request.format.displayName} is not supported")
        }
    }

    private fun writeZip(nodes: List<ArchiveNode>, request: CreateArchiveRequest, output: OutputStream) {
        val encrypt = request.password != null
        ZipOutputStream(output, request.password).use { zip ->
            for (node in nodes) {
                job.ensureActive()
                val parameters = ZipParameters().apply {
                    fileNameInZip = node.entryName
                    lastModifiedFileTime = node.file.lastModified()
                    if (request.level == CompressionLevel.Store) {
                        compressionMethod = CompressionMethod.STORE
                    } else {
                        compressionMethod = CompressionMethod.DEFLATE
                        compressionLevel = zipLevel(request.level)
                    }
                    if (encrypt && !node.isDirectory) {
                        isEncryptFiles = true
                        encryptionMethod = EncryptionMethod.AES
                        aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                    }
                }
                zip.putNextEntry(parameters)
                if (!node.isDirectory) {
                    FsIo.openRead(manager, node.file).use { input -> copyCounting(input, zip, node.file.name) }
                }
                zip.closeEntry()
            }
        }
    }

    private fun zipLevel(level: CompressionLevel): ZipLevel = when (level) {
        CompressionLevel.Store, CompressionLevel.Fast -> ZipLevel.FASTEST
        CompressionLevel.Normal -> ZipLevel.NORMAL
        CompressionLevel.Maximum -> ZipLevel.ULTRA
    }

    private fun writeTar(nodes: List<ArchiveNode>, request: CreateArchiveRequest, output: OutputStream) {
        val compressed: OutputStream = when (request.format) {
            ArchiveFormat.TarGzip -> GzipCompressorOutputStream(
                output,
                GzipParameters().apply { compressionLevel = request.level.gzipLevel },
            )
            ArchiveFormat.TarBzip2 -> BZip2CompressorOutputStream(output)
            ArchiveFormat.TarXz -> XZCompressorOutputStream(output, request.level.xzPreset)
            else -> output
        }
        TarArchiveOutputStream(compressed).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
            for (node in nodes) {
                job.ensureActive()
                val entry = TarArchiveEntry(node.entryName)
                entry.setModTime(node.file.lastModified())
                if (node.isDirectory) {
                    tar.putArchiveEntry(entry)
                } else {
                    entry.size = node.file.length()
                    tar.putArchiveEntry(entry)
                    FsIo.openRead(manager, node.file).use { input -> copyCounting(input, tar, node.file.name) }
                }
                tar.closeArchiveEntry()
            }
            tar.finish()
        }
    }

    private fun writeSevenZip(nodes: List<ArchiveNode>, request: CreateArchiveRequest, destination: ExtendedFile) {
        SevenZOutputFile(FsIo.openChannelWrite(manager, destination)).use { archive ->
            archive.setContentCompression(if (request.level == CompressionLevel.Store) SevenZMethod.COPY else SevenZMethod.LZMA2)
            for (node in nodes) {
                job.ensureActive()
                val length = if (node.isDirectory) 0L else node.file.length()
                val entry = SevenZArchiveEntry()
                entry.name = node.entryName.trimEnd('/')
                entry.isDirectory = node.isDirectory
                val hasStream = !node.isDirectory && length > 0L
                entry.setHasStream(hasStream)
                entry.lastModifiedDate = Date(node.file.lastModified())
                archive.putArchiveEntry(entry)
                if (hasStream) {
                    FsIo.openRead(manager, node.file).use { input ->
                        val buffer = ByteArray(COPY_BUFFER_SIZE)
                        while (true) {
                            job.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            archive.write(buffer, 0, read)
                            advance(node.file.name, read)
                        }
                    }
                }
                archive.closeArchiveEntry()
            }
            archive.finish()
        }
    }

    private fun copyCounting(input: java.io.InputStream, output: OutputStream, name: String) {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        while (true) {
            job.ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            advance(name, read)
        }
    }

    private fun advance(name: String, bytes: Int) {
        done += bytes
        progress.update(name, done, total)
    }
}
