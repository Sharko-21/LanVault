package app.lanvault.android.ui

import android.content.ContentResolver
import android.graphics.Bitmap
import android.os.CancellationSignal
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.lanvault.android.BackupUiState
import app.lanvault.core.model.MediaItem
import app.lanvault.core.model.MediaType
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class ThumbnailLoader(private val resolver: ContentResolver) {
    private val slots = Semaphore(4)
    private val cache =
        object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }

    suspend fun load(item: MediaItem): Bitmap? {
        val key = "${item.uri}:${item.version}"
        cache.get(key)?.let {
            return it
        }
        return slots.withPermit {
            cache.get(key)?.let {
                return@withPermit it
            }
            val signal = CancellationSignal()
            try {
                coroutineScope {
                    val task =
                        async(Dispatchers.IO) {
                            try {
                                resolver.loadThumbnail(item.uri.toUri(), Size(256, 256), signal)
                            } catch (_: java.io.IOException) {
                                null
                            } catch (_: SecurityException) {
                                null
                            } catch (_: android.os.OperationCanceledException) {
                                null
                            }
                        }
                    try {
                        task.await()?.also { cache.put(key, it) }
                    } finally {
                        signal.cancel()
                    }
                }
            } finally {
                signal.cancel()
            }
        }
    }
}

@Composable
internal fun FilesScreen(
    state: BackupUiState,
    thumbnails: ThumbnailLoader,
    gridState: LazyGridState,
    onAccess: () -> Unit,
    onLoad: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
) {
    if (!state.access.canRead) {
        Column(Modifier.fillMaxSize().padding(20.dp)) { AccessPrompt(state, onAccess) }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        state = gridState,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(
                Modifier.padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Выберите файлы для отправки",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "Загружено для просмотра: ${state.media.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = { onLoad(true) },
                        enabled = !state.scanning && !state.busy,
                    ) {
                        Text("Обновить")
                    }
                }
                Text(
                    state.access.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.busy && state.progress != null) {
                    TransferRow(state.progress)
                    Text(
                        "Отправка продолжится, пока приложение открыто.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 4.dp))
            }
        }
        items(state.media, key = { "${it.uri}:${it.version}" }) { item ->
            MediaTile(item, item.uri in state.selected, !state.busy, thumbnails) {
                onSelect(item.uri)
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.scanning) CircularProgressIndicator(Modifier.size(24.dp))
                else if (state.hasMore)
                    OutlinedButton(onClick = { onLoad(false) }, enabled = !state.busy) {
                        Text("Показать ещё")
                    }
                else if (state.media.isEmpty())
                    Text(
                        "На телефоне нет доступных фото и видео.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                Text(
                    "Для копирования всей медиатеки используйте раздел «Копирование». Список здесь загружается частями.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MediaTile(
    item: MediaItem,
    selected: Boolean,
    enabled: Boolean,
    loader: ThumbnailLoader,
    onSelect: () -> Unit,
) {
    val bitmap by
        produceState<Bitmap?>(null, item.uri, item.version, loader) { value = loader.load(item) }
    Column(
        Modifier.clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Checkbox, onClick = onSelect)
            .semantics(mergeDescendants = true) {
                contentDescription =
                    "${item.name}, ${if (item.type == MediaType.video) "видео" else "фото"}, ${bytesLabel(item.size)}"
                this.selected = selected
                stateDescription = if (selected) "Выбрано" else "Не выбрано"
            }
    ) {
        Box(
            Modifier.fillMaxWidth()
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            if (bitmap != null)
                Image(
                    bitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            else
                AppGlyph(
                    AppIcon.FILES,
                    Modifier.align(Alignment.Center),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
            Surface(
                Modifier.align(Alignment.TopEnd).padding(5.dp),
                shape = RoundedCornerShape(20.dp),
                color =
                    if (selected) MaterialTheme.colorScheme.primary
                    else Color.Black.copy(alpha = .38f),
            ) {
                Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                    if (selected)
                        AppGlyph(
                            AppIcon.CHECK,
                            Modifier.size(18.dp),
                            MaterialTheme.colorScheme.onPrimary,
                        )
                    else
                        Box(
                            Modifier.size(14.dp)
                                .background(
                                    Color.White.copy(alpha = .65f),
                                    RoundedCornerShape(3.dp),
                                )
                        )
                }
            }
            if (item.type == MediaType.video)
                Surface(
                    Modifier.align(Alignment.BottomStart).padding(5.dp),
                    color = Color.Black.copy(alpha = .65f),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        "Видео",
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
        }
        Text(
            item.name,
            Modifier.padding(start = 3.dp, top = 5.dp, end = 3.dp),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            bytesLabel(item.size),
            Modifier.padding(horizontal = 3.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
