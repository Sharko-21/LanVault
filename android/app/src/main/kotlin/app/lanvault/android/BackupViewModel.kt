package app.lanvault.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lanvault.core.media.*
import app.lanvault.core.model.*
import app.lanvault.core.sync.*
import app.lanvault.core.sync.ManualBackupController
import app.lanvault.core.transport.*
import javax.net.ssl.SSLException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class BackupUiState(
    val initialized: Boolean = false,
    val busy: Boolean = false,
    val scanning: Boolean = false,
    val access: MediaAccess = MediaAccess(false, false, false, false),
    val media: List<MediaItem> = emptyList(),
    val selected: Set<String> = emptySet(),
    val hasMore: Boolean = false,
    val trusted: TrustedServer? = null,
    val pending: TrustEnvelope? = null,
    val verificationCode: String? = null,
    val progress: TransferProgress? = null,
    val history: List<TransferRecord> = emptyList(),
    val message: String = "",
    val error: String = "",
    val automaticEnabled: Boolean = true,
    val automatic: AutoSyncState = AutoSyncState(),
)

class BackupViewModel(application: Application) : AndroidViewModel(application) {
    private val backup = ManualBackupController(application)
    private val mutable = MutableStateFlow(BackupUiState(access = backup.media.access()))
    val state = mutable.asStateFlow()
    private var cursor: LibraryCursor? = null
    private var scan: Job? = null
    private var operation: Job? = null
    private var foregroundVisible = false
    private var automaticStarted = false
    private val settings = AutoSyncSettings(application)

    init {
        mutable.update { it.copy(automaticEnabled = settings.enabled) }
        viewModelScope.launch {
            SyncRuntime.state.collect { value ->
                mutable.update { it.copy(automatic = value, automaticEnabled = settings.enabled) }
            }
        }
        viewModelScope.launch {
            try {
                backup.recover()
                val trust = backup.trust()
                mutable.update {
                    it.copy(
                        initialized = true,
                        trusted = trust.trusted,
                        pending = trust.pending?.envelope,
                        message =
                            if (trust.pending != null && trust.trusted == null)
                                "Сопряжение прервано. Пока QR действует, можно повторить подключение."
                            else "",
                    )
                }
                maybeStartAutomatic()
            } catch (_: Exception) {
                mutable.update {
                    it.copy(
                        initialized = true,
                        error =
                            "Не удалось открыть защищённое подключение. Очистите подключение и повторите сопряжение.",
                    )
                }
            }
        }
        viewModelScope.launch {
            backup.history
                .catch {
                    mutable.update {
                        it.copy(error = "Не удалось прочитать локальный журнал передач.")
                    }
                }
                .collect { records -> mutable.update { it.copy(history = records) } }
        }
    }

    fun refreshAccess() {
        val access = backup.media.access()
        if (
            access != mutable.value.access || access.selected && !(access.images && access.videos)
        ) {
            scan?.cancel()
            cursor = null
            mutable.update {
                it.copy(
                    access = access,
                    media = emptyList(),
                    selected = emptySet(),
                    hasMore = false,
                )
            }
            if (access.canRead) load(reset = true)
        }
        maybeStartAutomatic()
    }

    fun foreground() {
        foregroundVisible = true
        refreshAccess()
        maybeStartAutomatic()
    }

    private fun maybeStartAutomatic() {
        val value = mutable.value
        if (
            !foregroundVisible ||
                automaticStarted ||
                !settings.enabled ||
                !value.initialized ||
                value.busy ||
                value.trusted == null ||
                !value.access.canRead ||
                !value.access.originalMetadata
        )
            return
        automaticStarted = true
        syncAll()
    }

    fun setAutomatic(enabled: Boolean) {
        settings.enabled = enabled
        mutable.update { it.copy(automaticEnabled = enabled) }
        if (enabled) {
            SyncScheduler.ensure(getApplication())
            automaticStarted = false
            maybeStartAutomatic()
        } else {
            SyncScheduler.cancel(getApplication())
            SyncService.stop(getApplication())
        }
    }

    fun syncAll(verifyCopies: Boolean = false) {
        if (mutable.value.trusted == null || mutable.value.busy || mutable.value.automatic.running)
            return
        try {
            SyncService.start(getApplication(), verifyCopies)
            SyncScheduler.ensure(getApplication())
        } catch (e: Exception) {
            mutable.update { it.copy(error = userError(e)) }
        }
    }

    fun stopAutomatic() {
        SyncScheduler.cancel(getApplication())
        SyncService.stop(getApplication())
        settings.enabled = false
        mutable.update { it.copy(automaticEnabled = false) }
    }

    fun load(reset: Boolean = false) {
        if (mutable.value.scanning || mutable.value.busy) return
        if (reset) {
            cursor = null
            mutable.update { it.copy(media = emptyList(), selected = emptySet()) }
        }
        mutable.update { it.copy(scanning = true, error = "", access = backup.media.access()) }
        scan = viewModelScope.launch {
            try {
                val page = backup.media.page(if (reset) null else cursor)
                cursor = page.next
                mutable.update {
                    it.copy(
                        media = (it.media + page.items).distinctBy(MediaItem::uri),
                        hasMore = page.next != null,
                        scanning = false,
                    )
                }
            } catch (e: CancellationException) {
                mutable.update { it.copy(scanning = false) }
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(scanning = false, error = userError(e)) }
            }
        }
    }

    fun toggle(uri: String) {
        if (mutable.value.busy) return
        mutable.update {
            val selected = it.selected.toMutableSet()
            if (!selected.remove(uri)) selected.add(uri)
            it.copy(selected = selected)
        }
    }

    fun importQr(raw: String) {
        if (mutable.value.busy) return
        try {
            val envelope = parseEnvelope(raw)
            mutable.update {
                it.copy(
                    pending = envelope,
                    verificationCode = null,
                    error = "",
                    message =
                        "QR проверен. Нажмите «Подключиться», затем сравните коды на телефоне и ПК.",
                )
            }
        } catch (e: Exception) {
            mutable.update { it.copy(error = userError(e)) }
        }
    }

    fun pair() = launchOperation {
        val envelope = mutable.value.pending ?: throw ClientFailure("INVALID_QR")
        backup.pair(envelope) { code ->
            mutable.update {
                it.copy(
                    verificationCode = code,
                    message = "Сравните код с Desktop и разрешите подключение на компьютере.",
                )
            }
        }
        val trust = backup.trust()
        mutable.update {
            it.copy(
                trusted = trust.trusted,
                pending = null,
                verificationCode = null,
                message = "Компьютер подключён. Можно копировать медиатеку.",
            )
        }
    }

    fun check() = launchOperation {
        backup.check()
        mutable.update { it.copy(message = "Компьютер доступен по Wi-Fi.") }
    }

    fun forget() = launchOperation {
        SyncScheduler.cancel(getApplication())
        SyncService.stop(getApplication())
        automaticStarted = false
        backup.forget()
        mutable.update {
            it.copy(
                trusted = null,
                pending = null,
                verificationCode = null,
                message =
                    "Подключение удалено с телефона. При необходимости отзовите старое устройство в Desktop.",
            )
        }
    }

    fun send() = launchOperation {
        sendItems(mutable.value.media.filter { it.uri in mutable.value.selected })
    }

    fun retry() = launchOperation { sendItems(backup.unfinished()) }

    private suspend fun sendItems(items: List<MediaItem>) {
        backup.send(
            items,
            { progress -> mutable.update { it.copy(progress = progress) } },
            { uri -> mutable.update { it.copy(selected = it.selected - uri) } },
        )
        mutable.update {
            it.copy(message = "Desktop подтвердил сохранение выбранных файлов.", progress = null)
        }
    }

    private fun launchOperation(action: suspend () -> Unit) {
        if (mutable.value.busy || !mutable.value.initialized) return
        mutable.update { it.copy(busy = true, error = "", message = "", progress = null) }
        operation = viewModelScope.launch {
            try {
                action()
            } catch (e: TimeoutCancellationException) {
                mutable.update { it.copy(error = userError(e), progress = null) }
            } catch (e: CancellationException) {
                mutable.update {
                    it.copy(
                        message =
                            "Операция остановлена. Незавершённую передачу можно повторить с нуля.",
                        progress = null,
                    )
                }
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(error = userError(e), progress = null) }
            } finally {
                withContext(NonCancellable) {
                    runCatching { backup.trust() }
                        .getOrNull()
                        ?.let { trust -> mutable.update { it.copy(trusted = trust.trusted) } }
                }
                mutable.update { it.copy(busy = false) }
                maybeStartAutomatic()
            }
        }
    }

    fun localNetworkDenied() {
        mutable.update {
            it.copy(error = userError(ClientFailure("LOCAL_NETWORK_PERMISSION_REQUIRED")))
        }
    }

    fun stop() {
        operation?.cancel()
    }

    fun background() {
        foregroundVisible = false
        if (mutable.value.busy) stop()
    }
}

fun userError(error: Throwable): String {
    val code = (error as? ClientFailure)?.code
    return when {
        error is MediaUnavailableException ->
            "Android не предоставил оригинал выбранного файла. Обновите медиатеку и выберите доступный на телефоне файл. Код: MEDIA_FILE_UNAVAILABLE."
        error is SecurityException || code == "ORIGINAL_METADATA_PERMISSION_REQUIRED" ->
            "Для оригиналов нужен доступ к выбранным медиа и исходным метаданным. Разрешите доступ и повторите отправку."
        error is SSLException ||
            code in listOf("CA_PIN_MISMATCH", "INVALID_CA", "SERVER_IDENTITY_MISMATCH") ->
            "Не удалось подтвердить подлинность Desktop. Проверьте дату и время на устройствах и создайте новый QR-код."
        code == "WIFI_REQUIRED" || code == "WIFI_LOST" || code == "DIRECT_LAN_REQUIRED" ->
            "Подключите телефон к Wi-Fi той же локальной сети, что и компьютер."
        code == "VPN_ACTIVE" ->
            "Активен VPN. Для локальной передачи выберите обычное подключение к домашней Wi-Fi сети."
        code == "LOCAL_NETWORK_PERMISSION_REQUIRED" ->
            "Разрешите приложению доступ к локальной сети."
        code == "TCP_CONNECTION_TIMEOUT" ->
            "Компьютер не ответил за 10 секунд. Проверьте приём в LanVault на ПК и доступ к домашней сети в VPN. Код: TCP_CONNECTION_TIMEOUT."
        code == "TCP_CONNECTION_FAILED" ->
            "Компьютер не отвечает. Откройте LanVault на ПК и включите приём файлов. Код: TCP_CONNECTION_FAILED."
        code == "TCP_NETWORK_FAILED" ->
            "Соединение с компьютером заблокировано или сеть недоступна. Разрешите локальную сеть в VPN на устройствах и проверьте домашний Wi-Fi. Трафик через VPN не отправляется. Код: TCP_NETWORK_FAILED."
        code == "TLS_HANDSHAKE_TIMEOUT" || code == "TLS_CONNECTION_CLOSED" ->
            "Связь с компьютером установлена, но защищённое соединение не завершено. Проверьте приём на Desktop. Код: $code."
        code == "DESKTOP_RESPONSE_TIMEOUT" || code == "DESKTOP_CONNECTION_LOST" ->
            "Защищённое соединение установлено, но ответ Desktop не получен. Проверьте приём на компьютере и повторите действие. Код: $code."
        code == "PAIRING_EXPIRED" || error is TimeoutCancellationException ->
            "Время сопряжения истекло. Создайте новый QR-код на Desktop."
        code == "PAIRING_REJECTED" -> "Подключение отклонено на компьютере."
        code == "UNAUTHORIZED" ->
            "Desktop отозвал доступ. Забудьте подключение и выполните новое сопряжение."
        code == "SERVER_PAUSED" -> "Приём на Desktop приостановлен. Продолжите его на компьютере."
        code == "INSUFFICIENT_STORAGE" -> "На диске Desktop недостаточно свободного места."
        code == "SOURCE_CHANGED" ->
            "Файл изменился во время чтения. Обновите медиатеку и отправьте его повторно."
        code == "INVALID_RECEIPT" ->
            "Desktop не прислал корректное подтверждение. Файл не помечен как сохранённый."
        code == "INVALID_SELECTION" -> "Выберите доступные файлы."
        code == "INVALID_QR" ||
            code == "QR_TOO_LARGE" ||
            code == "INVALID_LAN_ADDRESS" ||
            code == "PRIVATE_IPV4_REQUIRED" ||
            code == "INVALID_SERVER_IDENTITY" ||
            code == "UNSUPPORTED_PROTOCOL" ->
            "Это не действующий локальный QR-код LanVault. Создайте новый код на Desktop."
        else -> {
            // Never display exception messages: they can contain URLs or credentials.
            val diagnostic =
                code?.takeIf { it.matches(Regex("[A-Z_]{1,64}")) }
                    ?: error.javaClass.simpleName.take(64).replace(Regex("[^A-Za-z0-9_]"), "")
            "Не удалось завершить операцию. Проверьте доступ к файлам и приём на компьютере, затем повторите. Код: $diagnostic."
        }
    }
}
