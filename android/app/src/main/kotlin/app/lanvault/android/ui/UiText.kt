package app.lanvault.android.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun bytesLabel(bytes: Long): String =
    when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f ГиБ".format(bytes.toDouble() / (1024L * 1024 * 1024))
        bytes >= 1024 * 1024 -> "%.1f МиБ".format(bytes.toDouble() / (1024 * 1024))
        bytes >= 1024 -> "%.1f КиБ".format(bytes.toDouble() / 1024)
        else -> "$bytes Б"
    }

internal fun stageLabel(stage: String): String =
    when (stage) {
        "HASHING" -> "Проверка файла"
        "UPLOADING" -> "Передача"
        "RETRYING" -> "Восстанавливаем соединение"
        "VERIFIED",
        "COMMITTING" -> "Сохранение на компьютере"
        "COMMITTED" -> "Сохранён"
        "SUPERSEDED" -> "Заменён новой передачей"
        "PREPARED",
        "INTERRUPTED" -> "Не завершён"
        else -> "Нужно повторить"
    }

internal fun timeLabel(millis: Long): String =
    DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.forLanguageTag("ru-RU"))
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(millis))
