package com.ai.assistance.operit.shell.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.application.ForegroundServiceCompat
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Foreground service that owns a live [ShellSessionManager] (Shell rebuild PR 3/N
 * follow-up).
 *
 * Owns the proot child process and the IPC server across activity death. The notification
 * surface is required for Android 14+ background-execution rules, and per the
 * sovereign-user-halt preview from `docs/SECURITY.md § 7`, it carries a Halt action that
 * stops the session immediately.
 *
 * Intent actions:
 *  - [ACTION_START] — start a session if one isn't running.
 *  - [ACTION_STOP]  — stop the active session and self-terminate.
 *
 * Components in the same process can bind via [LocalBinder] to observe
 * [sessionStateFlow]. Cross-process binding is not exposed.
 */
class ShellForegroundService : Service() {

    companion object {
        private const val TAG = "ShellForegroundService"

        const val ACTION_START = "com.ai.assistance.operit.shell.START"
        const val ACTION_STOP = "com.ai.assistance.operit.shell.STOP"

        private const val NOTIFICATION_ID = 4242
        private const val CHANNEL_ID = "operit_shell_session"
        private const val CHANNEL_NAME = "Shell session"

        /** Convenience: launch the service in foreground mode. */
        fun start(context: Context) {
            val intent = Intent(context, ShellForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ShellForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    inner class LocalBinder : Binder() {
        val sessionState: StateFlow<ShellSessionManager.State>
            get() = manager.state
    }

    private val binder = LocalBinder()
    private lateinit var manager: ShellSessionManager
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observerJob: Job? = null

    private val haltListener = com.ai.assistance.operit.core.halt.HaltController.Listener {
        // A halt from anywhere — chat halt button, plugin gate denial, whatever — stops
        // the proot session and tears down foreground state immediately.
        runCatching { manager.stop() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        manager = ShellSessionManager(applicationContext)
        ensureNotificationChannel()
        com.ai.assistance.operit.core.halt.HaltController.registerListener(haltListener)
        AppLogger.d(TAG, "created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // Halt button on the notification — record it as a sovereign halt event,
                // not just a service stop. The audit log + state propagation through
                // HaltController is what § 4.7 wants here.
                com.ai.assistance.operit.core.halt.HaltController.requestHalt(
                    by = "user:notification",
                    reason = "Halt action tapped on shell session notification",
                )
                manager.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // ACTION_START or null (system restart).
                startInForegroundIfNeeded(manager.state.value)
                if (manager.state.value !is ShellSessionManager.State.Running) {
                    val ok = manager.start()
                    if (!ok) {
                        AppLogger.w(TAG, "manager.start() returned false; staying foreground for failure surface")
                    }
                }
                observeManagerStateIfNeeded()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        com.ai.assistance.operit.core.halt.HaltController.unregisterListener(haltListener)
        observerJob?.cancel()
        manager.stop()
        scope.cancel()
        AppLogger.d(TAG, "destroyed")
        super.onDestroy()
    }

    private fun observeManagerStateIfNeeded() {
        if (observerJob?.isActive == true) return
        observerJob = scope.launch {
            manager.state.collect { state ->
                refreshNotification(state)
            }
        }
    }

    private fun startInForegroundIfNeeded(state: ShellSessionManager.State) {
        val notification = buildNotification(state)
        ForegroundServiceCompat.startForeground(
            service = this,
            notificationId = NOTIFICATION_ID,
            notification = notification,
            types = ForegroundServiceCompat.buildTypes(dataSync = true),
        )
    }

    private fun refreshNotification(state: ShellSessionManager.State) {
        val notification = buildNotification(state)
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Indicates the Operit shell session is active."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(state: ShellSessionManager.State): Notification {
        val title = when (state) {
            ShellSessionManager.State.Idle -> "Shell session idle"
            is ShellSessionManager.State.Starting -> "Shell session starting"
            is ShellSessionManager.State.Running -> "Shell session running"
            is ShellSessionManager.State.Failed -> "Shell session failed"
            ShellSessionManager.State.Stopped -> "Shell session stopped"
        }
        val text = when (state) {
            ShellSessionManager.State.Idle -> "No proot child."
            is ShellSessionManager.State.Starting -> state.message
            is ShellSessionManager.State.Running ->
                "proot child${state.pid?.let { " (pid $it)" } ?: ""}"
            is ShellSessionManager.State.Failed ->
                "${state.phase}: ${state.reason.take(120)}"
            ShellSessionManager.State.Stopped -> "Halted by user or system."
        }

        val haltIntent = Intent(this, ShellForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val haltPendingIntent = PendingIntent.getService(
            this,
            0,
            haltIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(state is ShellSessionManager.State.Running ||
                state is ShellSessionManager.State.Starting)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Halt", haltPendingIntent)
            .build()
    }
}
