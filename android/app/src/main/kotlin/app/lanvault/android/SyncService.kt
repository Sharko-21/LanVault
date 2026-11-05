package app.lanvault.android

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import app.lanvault.core.sync.*
import kotlinx.coroutines.*

/** User-visible full-library run; never started from BOOT_COMPLETED. */
class SyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var lastNotification = 0L
    private var ownsRun = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            AutoSyncSettings(this).enabled = false
            SyncScheduler.cancel(this)
            job?.cancel()
            stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY
        if (!SyncRuntime.runLock.tryLock()) {
            stopSelf()
            return START_NOT_STICKY
        }
        ownsRun = true
        val verify = intent?.getBooleanExtra("verifyCopies", false) ?: false
        val manager = getSystemService(NotificationManager::class.java)
        try {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "Синхронизация медиатеки",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
            startForeground(
                ID,
                notification("Подключаемся к вашему компьютеру"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            wakeLock =
                getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LanVault:LibrarySync")
                    .apply {
                        setReferenceCounted(false)
                        acquire(6 * 60 * 60_000L)
                    }
        } catch (e: Exception) {
            SyncRuntime.update(AutoSyncState(phase = "WAITING", error = e))
            releaseResources()
            stopSelf()
            return START_NOT_STICKY
        }
        job = scope.launch {
            try {
                SyncRuntime.update(
                    AutoSyncState(running = true, phase = "CONNECTING", verifying = verify)
                )
                val result =
                    ManualBackupController(applicationContext).syncAll(
                        forceDiscovery = true,
                        verifyCopies = verify,
                    ) { progress ->
                        SyncRuntime.update(
                            AutoSyncState(
                                true,
                                if (progress.reconnecting) "CONNECTING" else "SYNCING",
                                progress,
                                verifying = verify,
                            )
                        )
                        val now = System.currentTimeMillis()
                        if (now - lastNotification > 1000) {
                            lastNotification = now
                            manager.notify(
                                ID,
                                notification(
                                    "Сохранено: ${progress.saved}, уже есть: ${progress.skipped}, просмотрено: ${progress.scanned}"
                                ),
                            )
                        }
                    }
                SyncRuntime.update(
                    AutoSyncState(phase = "COMPLETE", progress = result, verifying = verify)
                )
            } catch (e: CancellationException) {
                SyncRuntime.update(
                    SyncRuntime.state.value.copy(running = false, phase = "PAUSED", error = null)
                )
                throw e
            } catch (e: Exception) {
                SyncRuntime.update(
                    SyncRuntime.state.value.copy(running = false, phase = "WAITING", error = e)
                )
                SyncScheduler.request(applicationContext)
            } finally {
                releaseResources()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        job?.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        releaseResources()
        super.onDestroy()
    }

    @Synchronized
    private fun releaseResources() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (ownsRun) {
            ownsRun = false
            SyncRuntime.runLock.unlock()
        }
    }

    private fun notification(text: String): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val stop =
            PendingIntent.getBroadcast(
                this,
                1,
                Intent(this, PauseSyncReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_lanvault)
            .setContentTitle("LanVault · вся медиатека")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, "Остановить", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "library-sync"
        private const val ID = 1101
        const val STOP = "app.lanvault.STOP_SYNC"

        fun start(context: Context, verifyCopies: Boolean = false) {
            context.startForegroundService(
                Intent(context, SyncService::class.java).putExtra("verifyCopies", verifyCopies)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncService::class.java))
        }
    }
}
