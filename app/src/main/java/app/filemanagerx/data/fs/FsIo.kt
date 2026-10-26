package app.filemanagerx.data.fs

import com.topjohnwu.superuser.nio.FileSystemManager
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel

/**
 * The only place that opens byte streams and channels on files. With a remote (root)
 * [FileSystemManager] the channel wraps a descriptor opened in the root process, so reads and
 * writes go straight to the kernel instead of through Binder.
 */
internal object FsIo {
    fun openRead(manager: FileSystemManager, file: File): InputStream =
        Channels.newInputStream(openChannelRead(manager, file))

    fun openWrite(manager: FileSystemManager, file: File): OutputStream =
        Channels.newOutputStream(openChannelWrite(manager, file))

    fun openChannelRead(manager: FileSystemManager, file: File): FileChannel =
        manager.openChannel(file.path, FileSystemManager.MODE_READ_ONLY)

    /** Creates or truncates [file]. The channel is seekable, which 7z needs. */
    fun openChannelWrite(manager: FileSystemManager, file: File): FileChannel =
        manager.openChannel(
            file.path,
            FileSystemManager.MODE_READ_WRITE or FileSystemManager.MODE_CREATE or FileSystemManager.MODE_TRUNCATE,
        )
}
