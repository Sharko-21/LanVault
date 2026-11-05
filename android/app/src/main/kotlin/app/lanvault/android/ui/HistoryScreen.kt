package app.lanvault.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lanvault.android.BackupUiState

@Composable
internal fun HistoryScreen(state: BackupUiState, onNetworkAction: (String) -> Unit) {
    val retryable = state.history.any { it.state in listOf("INTERRUPTED", "PREPARED", "FAILED") }
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                "Локальный журнал последних передач с этого телефона.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (retryable)
            item {
                OutlinedButton(
                    onClick = { onNetworkAction("retry") },
                    enabled = !state.busy && !state.automatic.running && state.trusted != null,
                ) {
                    Text("Повторить незавершённые")
                }
            }
        if (state.history.isEmpty())
            item {
                Text("Передач пока нет. После копирования здесь появятся подтверждения сохранения.")
            }
        items(state.history, key = { it.id }) { record ->
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(record.descriptor.originalName, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${stageLabel(record.state)} · ${timeLabel(record.updatedAtMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (record.state == "COMMITTED")
                    record.receipt?.let {
                        Text(
                            "Размер: ${bytesLabel(it.size.toLongOrNull() ?: 0)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                HorizontalDivider(Modifier.padding(top = 10.dp))
            }
        }
    }
}
