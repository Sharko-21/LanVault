package app.lanvault.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lanvault.core.model.TransferProgress

internal enum class AppIcon {
    COPY,
    FILES,
    SETTINGS,
    COMPUTER,
    CHECK,
    ARROW,
    HISTORY,
}

internal fun Modifier.semanticsLabel(label: String): Modifier = semantics {
    contentDescription = label
}

// A small shared outline set; navigation labels provide the accessible names.
@Composable
internal fun AppGlyph(
    icon: AppIcon,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
) {
    Canvas(modifier.size(24.dp)) {
        val factor = size.width / 24f
        scale(factor, factor, pivot = Offset.Zero) {
            val stroke = Stroke(width = 1.7f)
            fun line(x: Float, y: Float, ex: Float, ey: Float) =
                drawLine(color, Offset(x, y), Offset(ex, ey), strokeWidth = 1.7f)
            when (icon) {
                AppIcon.COPY -> {
                    drawRect(color, Offset(4f, 5f), Size(16f, 14f), style = stroke)
                    line(4f, 9f, 20f, 9f)
                    line(9f, 13f, 15f, 13f)
                }
                AppIcon.FILES -> {
                    drawRect(color, Offset(3f, 3f), Size(18f, 18f), style = stroke)
                    line(3f, 12f, 21f, 12f)
                    line(12f, 3f, 12f, 21f)
                }
                AppIcon.SETTINGS -> {
                    for ((y, x) in listOf(6f to 8f, 12f to 16f, 18f to 10f)) {
                        line(3f, y, 21f, y)
                        drawCircle(color, 2.4f, Offset(x, y), style = stroke)
                    }
                }
                AppIcon.COMPUTER -> {
                    drawRect(color, Offset(3f, 4f), Size(18f, 12f), style = stroke)
                    line(12f, 16f, 12f, 21f)
                    line(7f, 21f, 17f, 21f)
                }
                AppIcon.CHECK -> {
                    val p =
                        Path().apply {
                            moveTo(5f, 12f)
                            lineTo(10f, 17f)
                            lineTo(20f, 6f)
                        }
                    drawPath(p, color, style = stroke)
                }
                AppIcon.ARROW -> {
                    line(5f, 12f, 19f, 12f)
                    line(13f, 6f, 19f, 12f)
                    line(19f, 12f, 13f, 18f)
                }
                AppIcon.HISTORY -> {
                    drawCircle(color, 8f, Offset(12f, 12f), style = stroke)
                    line(12f, 7f, 12f, 12f)
                    line(12f, 12f, 16f, 14f)
                }
            }
        }
    }
}

@Composable
internal fun SectionTitle(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
internal fun TransferRow(progress: TransferProgress) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            progress.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (progress.stage == "UPLOADING" && progress.size > 0) {
            LinearProgressIndicator(
                progress = {
                    (progress.bytes.toDouble() / progress.size).toFloat().coerceIn(0f, 1f)
                },
                modifier =
                    Modifier.fillMaxWidth().semantics {
                        contentDescription = "Передача ${progress.name}"
                    },
            )
        } else if (progress.stage != "COMMITTED")
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (progress.stage == "UPLOADING")
                    "${bytesLabel(progress.bytes)} / ${bytesLabel(progress.size)}"
                else stageLabel(progress.stage),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (progress.bytesPerSecond > 0)
                Text(
                    "${bytesLabel(progress.bytesPerSecond)}/с",
                    style = MaterialTheme.typography.bodySmall,
                )
        }
    }
}
