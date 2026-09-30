package com.toshgate.filebrowser.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.toshgate.filebrowser.transfer.Transfer
import com.toshgate.filebrowser.ui.formatSize

/** Resumo das transferências em curso, no topo da lista. Tocar abre o painel. */
@Composable
fun TransfersBanner(
    uploads: List<Transfer<*>>,
    downloads: List<Transfer<*>>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeUp = uploads.filter { it.isActive }
    val activeDown = downloads.filter { it.isActive }
    val active = activeUp + activeDown
    if (active.isEmpty()) return

    val title = when {
        activeDown.isEmpty() -> if (activeUp.size == 1) "A enviar 1 ficheiro" else "A enviar ${activeUp.size} ficheiros"
        activeUp.isEmpty() -> if (activeDown.size == 1) "A descarregar 1 ficheiro" else "A descarregar ${activeDown.size} ficheiros"
        else -> "${active.size} transferências em curso"
    }
    val icon = when {
        activeDown.isEmpty() -> Icons.Default.CloudUpload
        activeUp.isEmpty() -> Icons.Default.CloudDownload
        else -> Icons.Default.SwapVert
    }
    val sizeKnown = active.all { it.size >= 0 }
    val total = active.sumOf { it.size.coerceAtLeast(0) }
    val done = active.sumOf { it.transferred }
    val fraction = if (sizeKnown && total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else null

    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (fraction != null) "${formatSize(done)} de ${formatSize(total)}" else formatSize(done),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Ver transferências")
        }
    }
}
