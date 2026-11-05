package app.lanvault.core.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.lanvault.core.database.ProbeDatabase
import app.lanvault.core.database.ProbeRecord
import app.lanvault.core.network.LocalNetworkProbe
import kotlinx.coroutines.CancellationException

/** An explicit five-second diagnostic, not the automatic backup scheduler. */
class NetworkProbeWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val dao = ProbeDatabase.get(applicationContext).probes()
        return try {
            dao.insert(
                ProbeRecord(
                    kind = "Локальная сеть",
                    status = "Завершено",
                    detail = LocalNetworkProbe(applicationContext).run(),
                )
            )
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            dao.insert(
                ProbeRecord(
                    kind = "Локальная сеть",
                    status = "Нужно разрешение",
                    detail = "Предоставьте доступ к локальной сети в настройках приложения.",
                )
            )
            Result.failure()
        } catch (_: Exception) {
            dao.insert(
                ProbeRecord(
                    kind = "Локальная сеть",
                    status = "Ошибка",
                    detail = "Сетевая проверка не завершилась. Проверьте Wi-Fi и разрешения.",
                )
            )
            Result.failure()
        }
    }
}
