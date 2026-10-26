package app.filemanagerx.data.fs

import com.topjohnwu.superuser.nio.ExtendedFile
import com.topjohnwu.superuser.nio.FileSystemManager

/**
 * Single entry point for obtaining files. Depending on whether root mode is active, the returned
 * [ExtendedFile]s run as the app (local) or as uid 0 (remote). Both are plain `java.io.File`s, so
 * the rest of the code does not care which one it got.
 */
class FileSystems(private val rootManager: RootManager) {
    private val local: FileSystemManager = FileSystemManager.getLocal()

    /** The file system manager that new operations should use right now. */
    val manager: FileSystemManager
        get() {
            val remote = rootManager.remoteFs.value
            return if (rootManager.isRootModeActive && remote != null) remote else local
        }

    val isRoot: Boolean get() = manager !== local

    fun file(path: String): ExtendedFile = manager.getFile(path)
}
