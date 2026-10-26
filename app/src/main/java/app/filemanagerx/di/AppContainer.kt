package app.filemanagerx.di

import android.content.Context
import app.filemanagerx.R
import app.filemanagerx.core.UiText
import app.filemanagerx.data.archive.ArchiveManager
import app.filemanagerx.data.fs.ClipboardRepository
import app.filemanagerx.data.fs.DuplicateFinder
import app.filemanagerx.data.fs.FileOpener
import app.filemanagerx.data.fs.FileOperations
import app.filemanagerx.data.fs.FileSystems
import app.filemanagerx.data.fs.RootManager
import app.filemanagerx.data.fs.RootStatus
import app.filemanagerx.data.fs.ShellRunner
import app.filemanagerx.data.fs.StorageVolumes
import app.filemanagerx.data.fs.ThumbnailLoader
import app.filemanagerx.data.settings.AppSettings
import app.filemanagerx.data.settings.SettingsRepository
import app.filemanagerx.data.tasks.TaskCenter
import app.filemanagerx.data.trash.TrashManager
import app.filemanagerx.service.TaskService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Hand-wired object graph. The app is small enough that a DI framework would only add build time. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(appContext)
    val rootManager = RootManager(appContext)
    val fileSystems = FileSystems(rootManager)
    val shell = ShellRunner(rootManager)
    val fileOperations = FileOperations(fileSystems, shell)
    val clipboard = ClipboardRepository()
    val fileOpener = FileOpener(appContext, fileSystems)
    val volumes = StorageVolumes(appContext)
    val thumbnails = ThumbnailLoader(fileSystems)
    val trash = TrashManager(fileSystems, volumes, fileOperations)
    val duplicates = DuplicateFinder(fileSystems)
    val tasks = TaskCenter(appScope)

    @Volatile
    private var zipCharset = AppSettings.DEFAULT_ZIP_CHARSET

    val archives = ArchiveManager(appContext, fileSystems) { zipCharset }

    init {
        appScope.launch(Dispatchers.IO) { fileOpener.clearStaging() }
        appScope.launch(Dispatchers.IO) { runCatching { trash.purgeExpired() } }
        appScope.launch { followSettings() }
        appScope.launch { keepAliveWhileBusy() }
    }

    /** Starts the foreground service whenever a task appears; the service stops itself when none are left. */
    private suspend fun keepAliveWhileBusy() {
        tasks.tasks
            .map { it.isNotEmpty() }
            .distinctUntilChanged()
            .collect { busy -> if (busy) TaskService.start(appContext) }
    }

    /** Keeps root mode and the ZIP charset in sync with the stored settings. */
    private suspend fun followSettings() {
        settings.settings.collect { current ->
            zipCharset = current.zipCharset
            rootManager.setModeEnabled(current.rootModeEnabled)
            if (current.rootModeEnabled && rootManager.status.value != RootStatus.Granted) {
                if (rootManager.connect() != RootStatus.Granted) {
                    // Denied or no su: switch the toggle back so the UI tells the truth.
                    settings.setRootModeEnabled(false)
                    tasks.post(UiText.Res(R.string.root_unavailable))
                }
            }
        }
    }
}
