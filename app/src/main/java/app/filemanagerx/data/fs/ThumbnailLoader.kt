package app.filemanagerx.data.fs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.filemanagerx.domain.model.FileItem
import app.filemanagerx.domain.model.FileKind
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Decodes small previews of images and videos. Every byte is read through [FsIo], so files that only
 * root can read get thumbnails too, without any staging copy.
 */
class ThumbnailLoader(private val fileSystems: FileSystems) {
    private val cache = object : LruCache<String, ImageBitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    /** Decoding is CPU and IO heavy; a few at a time keeps scrolling smooth. */
    private val limiter = Semaphore(PARALLEL_DECODES)

    fun supports(item: FileItem): Boolean =
        !item.isDirectory && (item.kind == FileKind.Image || item.kind == FileKind.Video) && item.size in 1..MAX_SOURCE_BYTES

    /** Returns a preview no larger than [sizePx] on its longer side, or `null` when none can be made. */
    suspend fun load(item: FileItem, sizePx: Int): ImageBitmap? {
        if (!supports(item)) return null
        val key = "${item.path}|${item.modifiedAt}|${item.size}|$sizePx"
        cache.get(key)?.let { return it }
        val decoded = limiter.withPermit {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (item.kind == FileKind.Image) decodeImage(item.path, sizePx) else decodeVideo(item.path, sizePx)
                }.getOrNull()
            }
        } ?: return null
        val image = decoded.asImageBitmap()
        cache.put(key, image)
        return image
    }

    private fun decodeImage(path: String, sizePx: Int): Bitmap? {
        val manager = fileSystems.manager
        val file = manager.getFile(path)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        FsIo.openRead(manager, file).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return FsIo.openRead(manager, file).use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun decodeVideo(path: String, sizePx: Int): Bitmap? {
        val manager = fileSystems.manager
        val channel = FsIo.openChannelRead(manager, manager.getFile(path))
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(ChannelDataSource(channel))
            return retriever.getScaledFrameAtTime(FRAME_TIME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, sizePx, sizePx)
        } finally {
            runCatching { retriever.release() }
            runCatching { channel.close() }
        }
    }

    private companion object {
        const val CACHE_BYTES = 24 * 1024 * 1024
        const val PARALLEL_DECODES = 3
        const val MAX_SOURCE_BYTES = 256L * 1024 * 1024
        const val FRAME_TIME_US = 1_000_000L
    }
}

/** Lets [MediaMetadataRetriever] read from any [FileChannel], including one opened in the root process. */
private class ChannelDataSource(private val channel: FileChannel) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        return try {
            channel.read(ByteBuffer.wrap(buffer, offset, size), position)
        } catch (e: IOException) {
            -1
        }
    }

    override fun getSize(): Long = channel.size()

    override fun close() = channel.close()
}
