package app.filemanagerx

import android.app.Application
import android.content.Context
import app.filemanagerx.di.AppContainer
import com.topjohnwu.superuser.Shell

class FileManagerXApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // MOUNT_MASTER puts the root shell in the global mount namespace, so /system, /data and
        // other app-visible mounts behave the same no matter which app asked for root.
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(20),
        )
        container = AppContainer(this)
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as FileManagerXApp).container
