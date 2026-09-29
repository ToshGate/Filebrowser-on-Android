package com.example.filebrowser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.filebrowser.transfer.Transfer
import com.example.filebrowser.transfer.TransferStatus
import com.example.filebrowser.ui.components.IconTile

/** Acções de uma lista (uploads ou downloads). As opcionais só existem num dos lados. */
class TransferActions(
    val pause: (String) -> Unit,
    val resume: (String) -> Unit,
    val cancel: (String) -> Unit,
    val pauseBatch: (String) -> Unit,
    val resumeBatch: (String) -> Unit,
    val cancelBatch: (String) -> Unit,
    val clearFinished: () -> Unit,
    /** Uploads: substituir o ficheiro que já existe no servidor. */
    val override: ((String) -> Unit)? = null,
    val overrideBatch: ((String) -> Unit)? = null,
    /** Downloads: abrir o ficheiro descarregado. */
    val open: ((String) -> Unit)? = null,
)

const val TAB_UPLOADS = 0
const val TAB_DOWNLOADS = 1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersSheet(
    uploads: List<Transfer<*>>,
    downloads: List<Transfer<*>>,
    initialTab: Int,
    uploadActions: TransferActions,
    downloadActions: TransferActions,
    onDismiss: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    val items = if (tab == TAB_UPLOADS) uploads else downloads
    val actions = if (tab == TAB_UPLOADS) uploadActions else downloadActions

    ModalBottomSheet(onDismissRequest = onDismiss) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == TAB_UPLOADS,
                onClick = { tab = TAB_UPLOADS },
                text = { Text(tabLabel("Uploads", uploads)) },
                icon = { Icon(Icons.Default.CloudUpload, null) },
            )
            Tab(
                selected = tab == TAB_DOWNLOADS,
                onClick = { tab = TAB_DOWNLOADS },
                text = { Text(tabLabel("Downloads", downloads)) },
                icon = { Icon(Icons.Default.CloudDownload, null) },
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = actions.clearFinished, enabled = items.any { it.isFinished }) {
                Text("Limpar concluídos")
            }
        }
        if (items.isEmpty()) {
            Text(
                if (tab == TAB_UPLOADS) "Sem uploads." else "Sem downloads.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(24.dp),
            )
        }
        TransferList(items, actions)
    }
}

private fun tabLabel(title: String, items: List<Transfer<*>>): String {
    val active = items.count { it.isActive }
    return if (active > 0) "$title ($active)" else title
}

private sealed interface Entry {
    data class Single(val record: Transfer<*>) : Entry
    data class Batch(val id: String, val name: String, val records: List<Transfer<*>>) : Entry {
        val total = records.size
        val done = records.count { it.status == TransferStatus.DONE }
        val failed = records.count { it.status == TransferStatus.FAILED }
        val conflicts = records.count { it.status == TransferStatus.FAILED && it.conflict }
        val active = records.count { it.isActive }
        val paused = records.count { it.status == TransferStatus.PAUSED }
        val bytesTotal = records.sumOf { it.size.coerceAtLeast(0) }
        val bytesDone = records.sumOf { if (it.status == TransferStatus.DONE) it.size.coerceAtLeast(0) else it.transferred }
        val fraction = if (bytesTotal > 0) (bytesDone.toDouble() / bytesTotal).toFloat().coerceIn(0f, 1f) else 0f
        val isFinished = done == total
    }
}

/** Agrupa por pasta, mais recentes primeiro. */
private fun group(records: List<Transfer<*>>): List<Entry> {
    val entries = mutableListOf<Entry>()
    val batches = LinkedHashMap<String, MutableList<Transfer<*>>>()
    for (record in records.asReversed()) {
        val batchId = record.batchId
        if (batchId == null) {
            entries += Entry.Single(record)
        } else {
            val list = batches[batchId]
            if (list == null) {
                batches[batchId] = mutableListOf(record)
                entries += Entry.Batch(batchId, record.batchName ?: "Pasta", emptyList())
            } else {
                list += record
            }
        }
    }
    return entries.map { entry ->
        if (entry is Entry.Batch) entry.copy(records = batches.getValue(entry.id).asReversed().toList())
        else entry
    }
}

@Composable
private fun TransferList(records: List<Transfer<*>>, actions: TransferActions) {
    val entries = remember(records) { group(records) }
    val expanded = remember { mutableStateListOf<String>() }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        for (entry in entries) {
            when (entry) {
                is Entry.Single -> item(key = entry.record.id) {
                    TransferRow(entry.record, actions)
                }
                is Entry.Batch -> {
                    val isOpen = entry.id in expanded
                    item(key = "batch-${entry.id}") {
                        BatchRow(entry, isOpen, actions) {
                            if (isOpen) expanded.remove(entry.id) else expanded.add(entry.id)
                        }
                    }
                    if (isOpen) {
                        // Só os que ainda interessam: os concluídos seriam ruído numa pasta grande.
                        val visible = entry.records.filter { it.status != TransferStatus.DONE }
                        items(visible, key = { it.id }) { record ->
                            TransferRow(record, actions, indent = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BatchRow(batch: Entry.Batch, isOpen: Boolean, actions: TransferActions, onToggle: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onToggle),
        headlineContent = { Text(batch.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(
                    "${batch.done} de ${batch.total} ficheiros, " +
                        "${formatSize(batch.bytesDone)} de ${formatSize(batch.bytesTotal)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (batch.paused > 0 && batch.active == 0) {
                    Text("Em pausa", style = MaterialTheme.typography.bodySmall)
                }
                if (batch.failed > 0) {
                    Text(
                        "${batch.failed} falharam" + if (batch.conflicts > 0) " (${batch.conflicts} já existiam)" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (!batch.isFinished) {
                    LinearProgressIndicator(
                        progress = { batch.fraction },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
                val overrideBatch = actions.overrideBatch
                if (batch.conflicts > 0 && overrideBatch != null) {
                    TextButton(onClick = { overrideBatch(batch.id) }) {
                        Text("Substituir ${batch.conflicts} existente(s)")
                    }
                }
            }
        },
        leadingContent = {
            val c = MaterialTheme.colorScheme
            IconTile(
                if (batch.isFinished) Icons.Default.CheckCircle else Icons.Default.Folder,
                c.primaryContainer,
                c.onPrimaryContainer,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    batch.active > 0 ->
                        IconButton(onClick = { actions.pauseBatch(batch.id) }) { Icon(Icons.Default.Pause, "Pausar pasta") }
                    batch.paused > 0 || batch.failed > batch.conflicts ->
                        IconButton(onClick = { actions.resumeBatch(batch.id) }) { Icon(Icons.Default.PlayArrow, "Retomar pasta") }
                }
                if (!batch.isFinished) {
                    IconButton(onClick = { actions.cancelBatch(batch.id) }) { Icon(Icons.Default.Close, "Cancelar pasta") }
                }
                Icon(if (isOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
        },
    )
}

@Composable
private fun TransferRow(record: Transfer<*>, actions: TransferActions, indent: Boolean = false) {
    val open = actions.open
    val canOpen = open != null && record.status == TransferStatus.DONE
    var modifier = if (indent) Modifier.padding(start = 24.dp) else Modifier
    if (canOpen) modifier = modifier.clickable { open?.invoke(record.id) }

    ListItem(
        modifier = modifier,
        headlineContent = { Text(record.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(
                    statusText(record, showDestination = !indent, canOpen = canOpen),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (record.status == TransferStatus.FAILED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    record.status == TransferStatus.DONE -> Unit
                    // Tamanho desconhecido (ZIP gerado pelo servidor): barra indeterminada.
                    record.size < 0 && record.status == TransferStatus.RUNNING ->
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                    else -> LinearProgressIndicator(
                        progress = { record.fraction },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
                val override = actions.override
                if (record.status == TransferStatus.FAILED && record.conflict && override != null) {
                    TextButton(onClick = { override(record.id) }) { Text("Substituir o existente") }
                }
            }
        },
        leadingContent = {
            val c = MaterialTheme.colorScheme
            val icon = when (record.status) {
                TransferStatus.QUEUED -> Icons.Default.Schedule
                TransferStatus.RUNNING ->
                    if (actions.open != null) Icons.Default.CloudDownload else Icons.Default.CloudUpload
                TransferStatus.PAUSED -> Icons.Default.PauseCircle
                TransferStatus.DONE -> Icons.Default.CheckCircle
                TransferStatus.FAILED, TransferStatus.CANCELLED -> Icons.Default.ErrorOutline
            }
            val (container, content) = when (record.status) {
                TransferStatus.RUNNING -> c.primaryContainer to c.onPrimaryContainer
                TransferStatus.DONE -> c.secondaryContainer to c.onSecondaryContainer
                TransferStatus.FAILED -> c.errorContainer to c.onErrorContainer
                else -> c.surfaceContainerHighest to c.onSurfaceVariant
            }
            IconTile(icon, container, content)
        },
        trailingContent = {
            Row {
                when (record.status) {
                    TransferStatus.QUEUED, TransferStatus.RUNNING ->
                        IconButton(onClick = { actions.pause(record.id) }) { Icon(Icons.Default.Pause, "Pausar") }
                    TransferStatus.PAUSED ->
                        IconButton(onClick = { actions.resume(record.id) }) { Icon(Icons.Default.PlayArrow, "Retomar") }
                    TransferStatus.FAILED ->
                        IconButton(onClick = { actions.resume(record.id) }) { Icon(Icons.Default.Replay, "Tentar de novo") }
                    else -> Unit
                }
                if (record.status != TransferStatus.DONE) {
                    IconButton(onClick = { actions.cancel(record.id) }) { Icon(Icons.Default.Close, "Cancelar") }
                }
            }
        },
    )
}

private fun statusText(r: Transfer<*>, showDestination: Boolean, canOpen: Boolean): String {
    val percent = (r.fraction * 100).toInt()
    val bytes = if (r.size >= 0) "${formatSize(r.transferred)} de ${formatSize(r.size)}" else formatSize(r.transferred)
    val progress = if (r.size >= 0) "$bytes ($percent%)" else bytes
    return when (r.status) {
        TransferStatus.QUEUED -> r.error ?: if (r.transferred > 0) "Em fila, $progress" else "Em fila"
        TransferStatus.RUNNING -> progress
        TransferStatus.PAUSED -> "Em pausa, $progress"
        TransferStatus.DONE -> buildString {
            append(formatSize(r.size.coerceAtLeast(r.transferred)))
            val destination = r.destination
            if (showDestination && destination != null) append(" em $destination")
            if (canOpen) append(". Toca para abrir.")
        }
        TransferStatus.FAILED -> r.error ?: "Falhou."
        TransferStatus.CANCELLED -> "Cancelado."
    }
}
