package app.filemanagerx.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Turns a VIEW intent URI into a path the archive code can read. */
object IncomingFiles {
    suspend fun resolve(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") return@withContext uri.path
        runCatching {
            val name = displayName(context, uri) ?: "archive"
            val folder = File(context.cacheDir, "incoming/${UUID.randomUUID().toString().take(8)}").apply { mkdirs() }
            val target = File(folder, name.replace('/', '_'))
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching null
            target.path
        }.getOrNull()
    }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/')
}
