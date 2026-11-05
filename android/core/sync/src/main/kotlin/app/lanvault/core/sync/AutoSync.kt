package app.lanvault.core.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.work.*
import app.lanvault.core.model.LibraryProgress
import app.lanvault.core.transport.ClientFailure
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

data class AutoSyncState(
    val running: Boolean = false,
    val phase: String = "IDLE",
    val progress: LibraryProgress = LibraryProgress(),
    val error: Throwable? = null,
    val verifying: Boolean = false,
)

object SyncRuntime {
    val runLock = Mutex()
    private val mutable = MutableStateFlow(AutoSyncState())
    val state = mutable.asStateFlow()

    fun update(value: AutoSyncState) {
        mutable.value = value
    }
}

class AutoSyncSettings(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences("lanvault-auto-sync", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = preferences.getBoolean("enabled", true)
        set(value) {
            check(preferences.edit().putBoolean("enabled", value).commit())
        }
}

object SyncScheduler {
    private const val PERIODIC = "lanvault-library-periodic"
    private const val IMMEDIATE = "lanvault-library-now"

    fun ensure(context: Context) {
        val manager = WorkManager.getInstance(context)
        if (!AutoSyncSettings(context).enabled) {
            manager.cancelUniqueWork(PERIODIC)
            manager.cancelUniqueWork(IMMEDIATE)
            return
        }
        val request =
            PeriodicWorkRequestBuilder<AutoSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.MINUTES)
                .build()
        manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun request(context: Context) {
        if (!AutoSyncSettings(context).enabled) return
        val request =
            OneTimeWorkRequestBuilder<AutoSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.MINUTES)
                .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE)
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }
}

class AutoSyncWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!AutoSyncSettings(applicationContext).enabled || BackupOperations.mutex.isLocked)
            return Result.success()
        if (!SyncRuntime.runLock.tryLock()) return Result.success()
        return try {
            val controller = ManualBackupController(applicationContext)
            if (
                controller.trust().trusted == null ||
                    !controller.media.access().canRead ||
                    !controller.media.access().originalMetadata
            )
                return Result.success()
            SyncRuntime.update(AutoSyncState(running = true, phase = "CONNECTING"))
            // Probe while this is still a short ordinary job. Start a visible long-running
            // worker only when the trusted PC is reachable, including large new videos.
            controller.check(forceDiscovery = false)
            setForeground(foreground("Синхронизируем всю доступную медиатеку"))
            var lastNotification = 0L
            val result =
                withTimeout(55 * 60_000L) {
                    controller.syncAll { progress ->
                        SyncRuntime.update(
                            AutoSyncState(
                                true,
                                if (progress.reconnecting) "CONNECTING" else "SYNCING",
                                progress,
                            )
                        )
                        val now = System.currentTimeMillis()
                        if (now - lastNotification > 1500) {
                            lastNotification = now
                            applicationContext
                                .getSystemService(NotificationManager::class.java)
                                .notify(
                                    1102,
                                    foreground(
                                            "Сохранено: ${progress.saved} · уже есть: ${progress.skipped} · просмотрено: ${progress.scanned}"
                                        )
                                        .notification,
                                )
                        }
                    }
                }
            SyncRuntime.update(AutoSyncState(phase = "COMPLETE", progress = result))
            Result.success()
        } catch (e: TimeoutCancellationException) {
            SyncRuntime.update(
                SyncRuntime.state.value.copy(running = false, phase = "WAITING", error = null)
            )
            Result.retry()
        } catch (e: CancellationException) {
            SyncRuntime.update(
                SyncRuntime.state.value.copy(running = false, phase = "PAUSED", error = null)
            )
            throw e
        } catch (e: Exception) {
            SyncRuntime.update(
                SyncRuntime.state.value.copy(running = false, phase = "WAITING", error = e)
            )
            if (
                e is ClientFailure &&
                    e.code in
                        listOf(
                            "NOT_PAIRED",
                            "WIFI_REQUIRED",
                            "LOCAL_NETWORK_PERMISSION_REQUIRED",
                            "ORIGINAL_METADATA_PERMISSION_REQUIRED",
                            "UNAUTHORIZED",
                        )
            )
                Result.success()
            else if (runAttemptCount < 4) Result.retry() else Result.success()
        } finally {
            SyncRuntime.runLock.unlock()
        }
    }

    private fun foreground(text: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                "background-library-sync",
                "Автоматическая синхронизация",
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        val cancel =
            PendingIntent.getBroadcast(
                applicationContext,
                3,
                Intent()
                    .setClassName(
                        applicationContext.packageName,
                        applicationContext.packageName + ".PauseSyncReceiver",
                    ),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val builder =
            Notification.Builder(applicationContext, "background-library-sync")
                .setSmallIcon(applicationContext.applicationInfo.icon)
                .setContentTitle("LanVault · автосинхронизация")
                .setContentText(text)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(Notification.Action.Builder(null, "Остановить", cancel).build())
        applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?.let {
                builder.setContentIntent(
                    PendingIntent.getActivity(
                        applicationContext,
                        2,
                        it,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                )
            }
        return ForegroundInfo(1102, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
