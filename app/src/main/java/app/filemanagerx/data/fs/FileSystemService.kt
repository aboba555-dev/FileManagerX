package app.filemanagerx.data.fs

import android.content.Intent
import android.os.IBinder
import com.topjohnwu.superuser.ipc.RootService
import com.topjohnwu.superuser.nio.FileSystemManager

/**
 * Started by libsu inside a root process. It does nothing but hand out the binder of libsu's
 * file system server, so the app can use a `java.io.File`-like API that runs with uid 0.
 */
class FileSystemService : RootService() {
    override fun onBind(intent: Intent): IBinder = FileSystemManager.getService()
}
