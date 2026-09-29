package com.example.filebrowser.ui

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = -1
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[index])
}

/** O File Browser devolve datas em RFC 3339 (Go `time.Time`). */
fun parseInstant(iso: String?): Instant? =
    iso?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

/** "Hoje, 14:32", "Ontem, 09:10", "3 set." ou "3 set. 2024". */
fun formatModified(iso: String?): String? {
    val instant = parseInstant(iso) ?: return null
    val date = instant.atZone(ZoneId.systemDefault())
    val now = ZonedDateTime.now()
    val locale = Locale.getDefault()
    val time = date.format(DateTimeFormatter.ofPattern("HH:mm", locale))
    return when (date.toLocalDate()) {
        now.toLocalDate() -> "Hoje, $time"
        now.toLocalDate().minusDays(1) -> "Ontem, $time"
        else -> date.format(
            DateTimeFormatter.ofPattern(if (date.year == now.year) "d MMM" else "d MMM yyyy", locale)
        )
    }
}
