package app.filemanagerx.data.fs

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import com.topjohnwu.superuser.nio.FileSystemManager
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class RootStatus {
    /** Nobody asked for root yet. The su binary is never touched in this state. */
    Unknown,
    Checking,
    Granted,

    /** No su binary, or the user denied the request. */
    Unavailable,
}

/**
 * Owns the superuser session: asks for root, binds the root-side file service and publishes the
 * resulting [FileSystemManager]. Root is requested only on explicit user action.
 */
class RootManager(private val appContext: Context) {
    private val _status = MutableStateFlow(RootStatus.Unknown)
    val status: StateFlow<RootStatus> = _status.asStateFlow()

    private val _remoteFs = MutableStateFlow<FileSystemManager?>(null)
    val remoteFs: StateFlow<FileSystemManager?> = _remoteFs.asStateFlow()

    private val mutex = Mutex()
    private var connection: ServiceConnection? = null

    @Volatile
    private var modeEnabled = false

    /** True when the user turned root mode on and a root file service is connected. */
    val isRootModeActive: Boolean
        get() = modeEnabled && _status.value == RootStatus.Granted && _remoteFs.value != null

    fun setModeEnabled(enabled: Boolean) {
        modeEnabled = enabled
    }

    /** Requests root (showing the manager's prompt if needed) and connects the file service. */
    suspend fun connect(): RootStatus = mutex.withLock {
        if (_status.value == RootStatus.Granted && _remoteFs.value != null) {
            return@withLock RootStatus.Granted
        }
        _status.value = RootStatus.Checking
        val granted = withContext(Dispatchers.IO) { requestRootBlocking() }
        if (!granted) {
            _status.value = RootStatus.Unavailable
            return@withLock RootStatus.Unavailable
        }
        val fileSystem = bindFileService()
        if (fileSystem == null) {
            _status.value = RootStatus.Unavailable
            return@withLock RootStatus.Unavailable
        }
        _remoteFs.value = fileSystem
        _status.value = RootStatus.Granted
        RootStatus.Granted
    }

    fun disconnect() {
        val current = connection ?: return
        connection = null
        _remoteFs.value = null
        _status.value = RootStatus.Unknown
        RootService.unbind(current)
    }

    private fun requestRootBlocking(): Boolean {
        // libsu caches a non-root shell after a denial; drop it so the user gets another chance.
        Shell.getCachedShell()?.let { cached -> if (!cached.isRoot) cached.close() }
        return Shell.getShell().isRoot
    }

    private suspend fun bindFileService(): FileSystemManager? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(BIND_TIMEOUT_SECONDS.seconds) {
            suspendCancellableCoroutine<FileSystemManager?> { continuation ->
                val serviceConnection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, service: IBinder) {
                        val fileSystem = runCatching { FileSystemManager.getRemote(service) }.getOrNull()
                        if (fileSystem != null) {
                            _remoteFs.value = fileSystem
                            _status.value = RootStatus.Granted
                        }
                        if (continuation.isActive) continuation.resume(fileSystem)
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                        _remoteFs.value = null
                        _status.value = RootStatus.Unknown
                    }
                }
                connection = serviceConnection
                RootService.bind(Intent(appContext, FileSystemService::class.java), serviceConnection)
                continuation.invokeOnCancellation { RootService.unbind(serviceConnection) }
            }
        }
    }

    private companion object {
        const val BIND_TIMEOUT_SECONDS = 20
    }
}
