package app.filemanagerx.data.fs

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.compose.runtime.Immutable

enum class VolumeKind { Internal, Removable }

@Immutable
data class VolumeInfo(
    val label: String,
    val path: String,
    val totalBytes: Long,
    val freeBytes: Long,
    val kind: VolumeKind,
) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0L)
    val usedFraction: Float get() = if (totalBytes <= 0L) 0f else (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

/** Reads mounted storage volumes and their free space. Cheap enough to call on every Home resume. */
class StorageVolumes(private val context: Context) {
    fun load(): List<VolumeInfo> {
        val manager = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        val volumes = mutableListOf<VolumeInfo>()
        for (volume in manager.storageVolumes) {
            val directory = volume.directory ?: continue
            val state = volume.state
            if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) continue
            val (total, free) = space(directory.path)
            volumes += VolumeInfo(
                label = volume.getDescription(context),
                path = directory.path,
                totalBytes = total,
                freeBytes = free,
                kind = if (volume.isPrimary) VolumeKind.Internal else VolumeKind.Removable,
            )
        }
        return volumes
    }

    /** Mount points of the mounted volumes; much cheaper than [load] because no free space is queried. */
    fun roots(): List<String> {
        val manager = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        return manager.storageVolumes.mapNotNull { volume ->
            val state = volume.state
            if (state == Environment.MEDIA_MOUNTED) volume.directory?.path else null
        }
    }

    /** (total, available) bytes of the file system that contains [path]; zeros when it cannot be read. */
    fun space(path: String): Pair<Long, Long> = runCatching {
        val stat = StatFs(path)
        stat.totalBytes to stat.availableBytes
    }.getOrDefault(0L to 0L)
}
