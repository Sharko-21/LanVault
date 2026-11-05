package app.lanvault.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lanvault.android.BackupUiState

@Composable
internal fun CopyScreen(
    state: BackupUiState,
    onScan: () -> Unit,
    onInput: () -> Unit,
    onPair: () -> Unit,
    onSync: () -> Unit,
    onVerify: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onAccess: () -> Unit,
    onSettings: () -> Unit,
    onHistory: () -> Unit,
) {
    val automatic = state.automatic
    val progress = automatic.progress
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (state.trusted == null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    AppGlyph(
                        AppIcon.COMPUTER,
                        Modifier.size(40.dp),
                        MaterialTheme.colorScheme.primary,
                    )
                    Text("Подключите компьютер", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Копируйте оригиналы фото и видео по домашнему Wi-Fi. Файлы сохранятся в выбранной папке на ПК.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.pending == null) {
                        Button(
                            onClick = onScan,
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Сканировать QR-код", Modifier.padding(vertical = 5.dp))
                        }
                        TextButton(
                            onClick = onInput,
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Ввести код вручную")
                        }
                    } else {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    "Компьютер найден",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    "${state.pending.hostname} · ${state.pending.addresses.firstOrNull().orEmpty()}:${state.pending.port}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                state.verificationCode?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.headlineLarge,
                                        fontFamily = FontFamily.Monospace,
                                        letterSpacing = 5.sp,
                                    )
                                    Text(
                                        "Сравните этот код с кодом на ПК и подтвердите подключение на компьютере."
                                    )
                                }
                                if (state.busy) {
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                    TextButton(onClick = onStop) { Text("Отменить") }
                                } else {
                                    Button(onClick = onPair, modifier = Modifier.fillMaxWidth()) {
                                        Text("Подключиться")
                                    }
                                    TextButton(onClick = onScan) { Text("Сканировать другой код") }
                                }
                            }
                        }
                    }
                }
            }
            item { HorizontalDivider() }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("На компьютере", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "1. Откройте LanVault и выберите папку.\n2. Включите приём файлов.\n3. Нажмите «Подключить телефон».",
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 28.sp,
                    )
                }
            }
        } else {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    AppGlyph(AppIcon.COMPUTER, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.trusted.hostname,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "Компьютер подключён",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onSettings) { Text("Настроить") }
                }
            }
            if (!state.access.canRead || !state.access.originalMetadata)
                item { AccessPrompt(state, onAccess) }
            else {
                if (!state.access.images || !state.access.videos)
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    state.access.label,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    "Чтобы копировать всю медиатеку, разрешите доступ ко всем фото и видео.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                TextButton(onClick = onAccess) { Text("Изменить доступ") }
                            }
                        }
                    }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        val title =
                            when {
                                state.busy && state.progress != null -> "Отправляем выбранное"
                                state.busy -> "Проверяем связь"
                                automatic.running && automatic.phase == "CONNECTING" ->
                                    "Ищем компьютер"
                                automatic.running && automatic.verifying -> "Проверяем копии на ПК"
                                automatic.running -> "Копируем медиатеку"
                                automatic.phase == "ERROR" -> "Копирование прервано"
                                automatic.phase == "COMPLETE" && progress.unavailable > 0 ->
                                    "Есть недоступные файлы"
                                automatic.phase == "COMPLETE" && automatic.verifying ->
                                    "Проверка копий завершена"
                                automatic.phase == "COMPLETE" -> "Копирование завершено"
                                !state.automaticEnabled -> "Копирование на паузе"
                                else -> "Готово к копированию"
                            }
                        Text(title, style = MaterialTheme.typography.headlineMedium)
                        Text(
                            when {
                                state.busy ->
                                    "Не закрывайте этот экран до завершения ручной отправки."
                                automatic.running && automatic.verifying ->
                                    "Сверяем SHA-256 копий текущей медиатеки на компьютере. Утраченные и повреждённые копии отправим заново. Это может занять время."
                                automatic.running ->
                                    "Оригиналы остаются на телефоне. Можно свернуть приложение — передача продолжится."
                                automatic.phase == "COMPLETE" && automatic.verifying ->
                                    "Копии доступных файлов текущей медиатеки проверены на компьютере."
                                automatic.phase == "COMPLETE" ->
                                    "Новые файлы обработаны. Для ранее сохранённых копий используется кэш подтверждений до суток. Полную проверку можно запустить отдельно."
                                else ->
                                    "Новые фото и видео отправятся в папку на компьютере. Интернет для передачи не нужен."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        when {
                            state.busy ->
                                OutlinedButton(
                                    onClick = onStop,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Остановить операцию")
                                }
                            automatic.running ->
                                OutlinedButton(
                                    onClick = onPause,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Приостановить копирование")
                                }
                            else ->
                                Button(onClick = onSync, modifier = Modifier.fillMaxWidth()) {
                                    Text("Скопировать сейчас", Modifier.padding(vertical = 5.dp))
                                }
                        }
                        if (
                            automatic.running &&
                                progress.current == null &&
                                progress.activeTransfers.isEmpty()
                        )
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                if (!state.busy && !automatic.running)
                    item {
                        TextButton(onClick = onVerify, modifier = Modifier.fillMaxWidth()) {
                            Text("Проверить и восстановить копии")
                        }
                        Text(
                            "Проверяет копии текущей медиатеки по SHA-256 без кэша. Оригиналы остаются на телефоне.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                state.progress?.let { item { TransferRow(it) } }
                if (automatic.running) {
                    val transfers =
                        progress.activeTransfers.ifEmpty { listOfNotNull(progress.current) }
                    if (transfers.isNotEmpty())
                        item {
                            Column {
                                SectionTitle("Сейчас передаётся")
                                transfers.forEach { TransferRow(it) }
                            }
                        }
                }
                if (progress.scanned > 0)
                    item {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Text("За этот запуск", style = MaterialTheme.typography.titleSmall)
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    RunMetric(progress.saved, "Сохранено", Modifier.weight(1f))
                                    RunMetric(
                                        progress.skipped,
                                        "Не отправлялись",
                                        Modifier.weight(1f),
                                    )
                                    RunMetric(progress.scanned, "Проверено", Modifier.weight(1f))
                                }
                                if (progress.unavailable > 0)
                                    Text(
                                        "Недоступно: ${progress.unavailable}. Эти файлы могли быть удалены или доступны только в облаке.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                            }
                        }
                    }
            }
            item { HorizontalDivider() }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Автоматическое копирование",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            if (state.automaticEnabled) "Включено · по домашнему Wi-Fi"
                            else "Выключено · можно запускать вручную",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onSettings) { Text("Изменить") }
                }
            }
            if (state.history.isNotEmpty())
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionTitle("Последние передачи", "Все", onHistory)
                        state.history.take(3).forEach { record ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppGlyph(
                                    if (record.state == "COMMITTED") AppIcon.CHECK
                                    else AppIcon.HISTORY,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        record.descriptor.originalName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        stageLabel(record.state),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
        }
    }
}

@Composable
private fun RunMetric(count: Int, title: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(count.toString(), style = MaterialTheme.typography.titleLarge)
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
