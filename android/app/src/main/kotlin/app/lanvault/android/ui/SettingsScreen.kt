package app.lanvault.android.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.lanvault.android.BackupUiState
import app.lanvault.android.BuildConfig

@Composable
internal fun AccessPrompt(state: BackupUiState, onAccess: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (!state.access.canRead) "Разрешите доступ к фото и видео"
            else "Нужен доступ к оригиналам",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            if (!state.access.canRead)
                "LanVault читает медиатеку, чтобы находить и копировать файлы. Ничего не удаляется."
            else "Разрешите доступ к исходным метаданным, чтобы сохранять оригиналы без изменений.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onAccess) { Text("Разрешить доступ") }
        OpenAppSettings()
    }
}

@Composable
private fun OpenAppSettings() {
    val context = LocalContext.current
    TextButton(
        onClick = {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    "package:${context.packageName}".toUri(),
                )
            )
        }
    ) {
        Text("Открыть разрешения Android")
    }
}

@Composable
internal fun SettingsScreen(
    state: BackupUiState,
    onAutomatic: (Boolean) -> Unit,
    onAccess: () -> Unit,
    onCheck: () -> Unit,
    onForget: () -> Unit,
) {
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item { SectionTitle("Компьютер") }
        item {
            if (state.trusted != null)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.trusted.hostname, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${state.trusted.address}:${state.trusted.port}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = onCheck,
                            enabled = !state.busy && !state.automatic.running,
                        ) {
                            Text("Проверить связь")
                        }
                        TextButton(
                            onClick = onForget,
                            enabled = !state.busy && !state.automatic.running,
                        ) {
                            Text("Отключить", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (state.automatic.running)
                        Text(
                            "Для отключения компьютера сначала приостановите копирование.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
            else
                Text(
                    "Компьютер ещё не подключён. Добавьте его в разделе «Копирование».",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
        item { HorizontalDivider() }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Копировать автоматически", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "При открытии приложения и периодически в фоне, когда доступна домашняя сеть. Android может откладывать фоновый запуск.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.automaticEnabled,
                    onCheckedChange = onAutomatic,
                    enabled = state.initialized && !state.busy,
                    modifier = Modifier.semanticsLabel("Автоматическое копирование"),
                )
            }
        }
        item { HorizontalDivider() }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle("Доступ к медиатеке")
                Text(state.access.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (state.access.originalMetadata) "Исходные метаданные доступны"
                    else "Нет доступа к исходным метаданным",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onAccess) { Text("Изменить доступ") }
                OpenAppSettings()
            }
        }
        item { HorizontalDivider() }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { diagnostics = !diagnostics }) {
                    Text(if (diagnostics) "Скрыть технические сведения" else "Технические сведения")
                }
                if (diagnostics) {
                    Text(
                        "Передача напрямую по Wi-Fi. Сертификат компьютера проверяется при каждом подключении.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.trusted?.let {
                        Text(
                            "ID компьютера: ${it.serverId}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text("Отпечаток CA", style = MaterialTheme.typography.titleSmall)
                        Text(
                            it.spkiSha256.chunked(8).joinToString(" "),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                Text(
                    "LanVault ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
