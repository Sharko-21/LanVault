package app.lanvault.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.lanvault.android.BackupUiState
import app.lanvault.android.userError

private enum class Destination(val title: String, val icon: AppIcon) {
    COPY("Копирование", AppIcon.COPY),
    FILES("Файлы", AppIcon.FILES),
    SETTINGS("Настройки", AppIcon.SETTINGS),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanVaultApp(
    state: BackupUiState,
    onScan: () -> Unit,
    onImport: (String) -> Unit,
    onNetworkAction: (String) -> Unit,
    onSync: () -> Unit,
    onVerify: () -> Unit,
    onMediaAccess: () -> Unit,
    onAutomatic: (Boolean) -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onLoad: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onForget: () -> Unit,
) {
    var destination by rememberSaveable { mutableStateOf(Destination.COPY) }
    var history by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf(false) }
    var forget by remember { mutableStateOf(false) }
    // Pairing material is intentionally not persisted in saved instance state.
    var rawCode by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val gridState = rememberLazyGridState()
    val appContext = LocalContext.current
    val thumbnails = remember(state.access) { ThumbnailLoader(appContext.contentResolver) }
    LaunchedEffect(state.message) {
        if (state.message.isNotBlank()) snackbar.showSnackbar(state.message)
    }
    LaunchedEffect(destination, state.access, state.initialized) {
        if (
            destination == Destination.FILES &&
                state.initialized &&
                state.access.canRead &&
                state.media.isEmpty()
        )
            onLoad(true)
    }
    BackHandler(history || destination != Destination.COPY) {
        if (history) history = false else destination = Destination.COPY
    }
    val running = state.automatic.running
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (history) "История" else destination.title,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        if (destination == Destination.COPY && !history)
                            Text(
                                "LanVault",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                    }
                },
                navigationIcon = {
                    if (history) TextButton(onClick = { history = false }) { Text("Назад") }
                },
                actions = {
                    if (!history && destination == Destination.COPY && state.trusted != null)
                        IconButton(onClick = { history = true }) {
                            AppGlyph(
                                AppIcon.HISTORY,
                                Modifier.semanticsLabel("История копирования"),
                            )
                        }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    ),
            )
        },
        bottomBar = {
            Column {
                if (destination == Destination.FILES && !history && state.selected.isNotEmpty()) {
                    Surface(tonalElevation = 2.dp) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    "Выбрано: ${state.selected.size}",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Button(
                                    onClick = { onNetworkAction("send") },
                                    enabled =
                                        state.initialized &&
                                            !state.busy &&
                                            !running &&
                                            state.trusted != null &&
                                            state.access.originalMetadata,
                                ) {
                                    Text("Отправить")
                                }
                            }
                            Text(
                                when {
                                    running ->
                                        "Идёт копирование медиатеки. Дождитесь окончания или приостановите его."
                                    state.trusted == null ->
                                        "Сначала подключите компьютер в разделе «Копирование»."
                                    !state.access.originalMetadata ->
                                        "Разрешите доступ к исходным метаданным в настройках приложения."
                                    state.busy -> "Дождитесь завершения текущей операции."
                                    else -> "Для ручной отправки оставьте приложение открытым."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 0.dp,
                ) {
                    Destination.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = destination == entry,
                            onClick = {
                                destination = entry
                                history = false
                            },
                            icon = { AppGlyph(entry.icon) },
                            label = { Text(entry.title) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            val error = state.error.ifBlank { state.automatic.error?.let(::userError).orEmpty() }
            if (error.isNotBlank()) ErrorNotice(error)
            when {
                !state.initialized ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                history -> HistoryScreen(state, onNetworkAction)
                destination == Destination.FILES ->
                    FilesScreen(state, thumbnails, gridState, onMediaAccess, onLoad, onSelect)
                destination == Destination.SETTINGS ->
                    SettingsScreen(
                        state,
                        onAutomatic,
                        onMediaAccess,
                        { onNetworkAction("check") },
                        { forget = true },
                    )
                else ->
                    CopyScreen(
                        state,
                        onScan,
                        { input = true },
                        { onNetworkAction("pair") },
                        onSync,
                        onVerify,
                        onPause,
                        onStop,
                        onMediaAccess,
                        { destination = Destination.SETTINGS },
                        { history = true },
                    )
            }
        }
    }
    if (input)
        AlertDialog(
            onDismissRequest = {
                input = false
                rawCode = ""
            },
            title = { Text("Код подключения") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Вставьте содержимое QR-кода с компьютера.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        rawCode,
                        { if (it.length <= 32768) rawCode = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Код LanVault") },
                        visualTransformation = PasswordVisualTransformation(),
                        minLines = 3,
                        maxLines = 5,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = rawCode.isNotBlank(),
                    onClick = {
                        onImport(rawCode)
                        rawCode = ""
                        input = false
                    },
                ) {
                    Text("Продолжить")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        input = false
                        rawCode = ""
                    }
                ) {
                    Text("Отмена")
                }
            },
        )
    if (forget)
        AlertDialog(
            onDismissRequest = { forget = false },
            title = { Text("Отключить компьютер?") },
            text = {
                Text(
                    "Сохранённые файлы останутся на компьютере. Для повторного подключения понадобится QR-код. Доступ этого телефона на ПК можно отозвать в разделе «Устройства»."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        forget = false
                        onForget()
                    }
                ) {
                    Text("Отключить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { forget = false }) { Text("Отмена") } },
        )
}

@Composable
private fun ErrorNotice(error: String) {
    var dismissed by remember(error) { mutableStateOf(false) }
    var details by remember(error) { mutableStateOf(false) }
    if (dismissed) return
    val summary = error.substringBefore(" Код:")
    val code = error.substringAfter(" Код:", "").trim()
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (code.isNotEmpty())
                    TextButton(onClick = { details = !details }) {
                        Text(if (details) "Скрыть код" else "Подробности")
                    }
                TextButton(onClick = { dismissed = true }) { Text("Закрыть") }
            }
            if (details)
                Text(
                    code,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
        }
    }
}
