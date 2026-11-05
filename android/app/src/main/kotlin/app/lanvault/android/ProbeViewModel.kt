package app.lanvault.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.lanvault.core.database.ProbeDatabase
import app.lanvault.core.database.ProbeRecord
import app.lanvault.core.media.MediaProbe
import app.lanvault.core.sync.NetworkProbeWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProbeUiState(
    val records: List<ProbeRecord> = emptyList(),
    val readingMedia: Boolean = false,
    val networkState: String = "Не запускалась",
)

class ProbeViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = ProbeDatabase.get(application).probes()
    private val workManager = WorkManager.getInstance(application)
    private val readingMedia = MutableStateFlow(false)
    val state =
        combine(
                dao.observeLatest(),
                readingMedia,
                workManager.getWorkInfosForUniqueWorkFlow("local-network-probe"),
            ) { records, reading, work ->
                ProbeUiState(records, reading, work.firstOrNull()?.state?.name ?: "Не запускалась")
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProbeUiState())

    fun probeMedia() {
        if (readingMedia.value) return
        readingMedia.value = true
        viewModelScope.launch {
            try {
                val detail = MediaProbe(getApplication()).run()
                dao.insert(ProbeRecord(kind = "Медиатека", status = "Завершено", detail = detail))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                dao.insert(
                    ProbeRecord(
                        kind = "Медиатека",
                        status = "Нужно разрешение",
                        detail =
                            "Предоставьте доступ к фотографиям, видео и оригинальным метаданным.",
                    )
                )
            } catch (_: Exception) {
                dao.insert(
                    ProbeRecord(
                        kind = "Медиатека",
                        status = "Ошибка",
                        detail =
                            "Не удалось прочитать оригинал. Файл мог измениться или стать недоступным.",
                    )
                )
            } finally {
                readingMedia.value = false
            }
        }
    }

    fun probeNetwork() {
        // No CONNECTED constraint: the probe must also execute on a LAN with no WAN.
        workManager.enqueueUniqueWork(
            "local-network-probe",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<NetworkProbeWorker>().build(),
        )
    }
}
