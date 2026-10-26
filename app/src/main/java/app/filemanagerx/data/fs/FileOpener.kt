package app.filemanagerx.data.fs

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import app.filemanagerx.domain.FileKinds
import app.filemanagerx.domain.PathUtils
import app.filemanagerx.domain.model.FileKind
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds the intents that hand a file to another app. Files the app process cannot read itself
 * (typically root-only locations) are first copied into the cache, because another app can only be
 * given a URI that the FileProvider, running as the app, is able to open.
 */
class FileOpener(
    private val context: Context,
    private val fileSystems: FileSystems,
) {
    private val authority = "${context.packageName}.fileprovider"
    private val stagingRoot = File(context.cacheDir, STAGING_DIR)

    suspend fun viewIntent(path: String): Intent = withContext(Dispatchers.IO) {
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(path), mimeTypeOf(path))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    suspend fun shareIntent(paths: List<String>): Intent = withContext(Dispatchers.IO) {
        val uris = ArrayList(paths.map { uriFor(it) })
        val mime = paths.map { mimeTypeOf(it) }.distinct().singleOrNull() ?: "*/*"
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        intent.setType(mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Local, app-readable copy of [path] (the file itself when the app can already read it). */
    suspend fun localCopy(path: String): File = withContext(Dispatchers.IO) { readableFile(path) }

    /** Removes everything staged for other apps; called at start-up. */
    fun clearStaging() {
        stagingRoot.deleteRecursively()
    }

    private fun uriFor(path: String): Uri = FileProvider.getUriForFile(context, authority, readableFile(path))

    private fun readableFile(path: String): File {
        val direct = File(path)
        if (direct.canRead()) return direct
        val folder = File(stagingRoot, UUID.randomUUID().toString().take(STAGING_ID_LENGTH)).apply { mkdirs() }
        val target = File(folder, PathUtils.name(path))
        val manager = fileSystems.manager
        FsIo.openRead(manager, manager.getFile(path)).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    private fun mimeTypeOf(path: String): String {
        val name = PathUtils.name(path)
        return when (FileKinds.of(name, isDirectory = false)) {
            FileKind.Apk -> "application/vnd.android.package-archive"
            FileKind.Text, FileKind.Code -> "text/plain"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(PathUtils.extension(name)) ?: "*/*"
        }
    }

    private companion object {
        const val STAGING_DIR = "external"
        const val STAGING_ID_LENGTH = 8
    }
}
