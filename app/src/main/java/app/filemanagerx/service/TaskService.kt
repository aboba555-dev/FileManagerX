package app.filemanagerx.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.filemanagerx.MainActivity
import app.filemanagerx.R
import app.filemanagerx.appContainer
import app.filemanagerx.data.tasks.TaskProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while [app.filemanagerx.data.tasks.TaskCenter] has running tasks and shows
 * them in a notification with a cancel action. The tasks themselves run on the application scope;
 * this service only pins the process, so it can be started and stopped freely.
 */
class TaskService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var lastNotified = 0L
    private var lastCount = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        // startForeground must follow startForegroundService within seconds, so do it before anything else.
        startForeground(
            NOTIFICATION_ID,
            buildNotification(emptyList()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (intent?.action == ACTION_CANCEL) {
            appContainer.tasks.cancel(intent.getLongExtra(EXTRA_TASK_ID, -1L))
        }
        if (watcher == null) {
            watcher = scope.launch {
                appContainer.tasks.tasks.collect { tasks ->
                    if (tasks.isEmpty()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        publish(tasks)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun publish(tasks: List<TaskProgress>) {
        val now = SystemClock.uptimeMillis()
        // Android rate-limits notification updates; 2 per second is plenty for a progress bar.
        if (tasks.size == lastCount && now - lastNotified < UPDATE_INTERVAL_MS) return
        lastNotified = now
        lastCount = tasks.size
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(tasks))
    }

    private fun buildNotification(tasks: List<TaskProgress>): Notification {
        val first = tasks.firstOrNull()
        val title = first?.title?.resolve(this) ?: getString(R.string.app_name)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_task)
            .setContentTitle(title)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openAppIntent())
        if (first != null) {
            if (first.detail.isNotEmpty()) builder.setContentText(first.detail)
            val fraction = first.fraction
            if (fraction == null) builder.setProgress(0, 0, true) else builder.setProgress(100, (fraction * 100).toInt(), false)
            if (tasks.size > 1) builder.setSubText(getString(R.string.notification_more_tasks, tasks.size - 1))
            builder.addAction(0, getString(R.string.action_cancel), cancelIntent(first.id))
        }
        return builder.build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancelIntent(taskId: Long): PendingIntent = PendingIntent.getService(
        this,
        taskId.toInt(),
        Intent(this, TaskService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_TASK_ID, taskId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_tasks), NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "tasks"
        private const val NOTIFICATION_ID = 1
        private const val UPDATE_INTERVAL_MS = 500L
        private const val ACTION_CANCEL = "app.filemanagerx.action.CANCEL_TASK"
        private const val EXTRA_TASK_ID = "task_id"

        /** Safe to call repeatedly; failures (background start restrictions) only cost the notification. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, TaskService::class.java)) }
        }
    }
}
