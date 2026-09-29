package com.example.filebrowser.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import com.example.filebrowser.data.Resource
import com.example.filebrowser.ui.formatModified
import com.example.filebrowser.ui.formatSize

/** Acções sobre um ficheiro ou pasta. Abre com o ⋮, com toque longo, ou ao tocar num ficheiro. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemActionsSheet(
    item: Resource,
    thumbnailUrl: String?,
    imageLoader: ImageLoader,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onDownloadZip: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ListItem(
            headlineContent = {
                Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = { Text(itemDetails(item)) },
            leadingContent = { FileTile(item, thumbnailUrl, imageLoader, size = 56.dp) },
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        if (item.isDir) {
            SheetAction(Icons.Default.FolderOpen, "Abrir", onOpen)
        }
        SheetAction(Icons.Default.FileDownload, if (item.isDir) "Descarregar pasta" else "Descarregar", onDownload)
        if (item.isDir) {
            SheetAction(Icons.Default.FolderZip, "Descarregar como ZIP", onDownloadZip)
        }
        SheetAction(Icons.Default.Edit, "Mudar o nome", onRename)
        SheetAction(Icons.Default.Delete, "Apagar", onDelete, tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
    }
}

/** O que o botão "Novo" pode criar nesta pasta. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewItemSheet(
    onDismiss: () -> Unit,
    onUploadFiles: () -> Unit,
    onUploadFolder: () -> Unit,
    onNewFolder: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Adicionar a esta pasta",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        val c = MaterialTheme.colorScheme
        NewItemOption(
            Icons.Default.UploadFile, c.primaryContainer, c.onPrimaryContainer,
            "Carregar ficheiros", "Escolhe um ou mais ficheiros do telemóvel.", onUploadFiles,
        )
        NewItemOption(
            Icons.Default.DriveFolderUpload, c.secondaryContainer, c.onSecondaryContainer,
            "Carregar pasta", "Envia uma pasta com todas as subpastas.", onUploadFolder,
        )
        NewItemOption(
            Icons.Default.CreateNewFolder, c.tertiaryContainer, c.onTertiaryContainer,
            "Nova pasta", "Cria uma pasta vazia aqui.", onNewFolder,
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color? = null) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(label, color = tint ?: Color.Unspecified) },
        leadingContent = { Icon(icon, null, tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun NewItemOption(
    icon: ImageVector,
    container: Color,
    content: Color,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        leadingContent = { IconTile(icon, container, content, size = 48.dp) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

fun itemDetails(item: Resource): String {
    val modified = formatModified(item.modified)
    val kind = if (item.isDir) "Pasta" else formatSize(item.size)
    return if (modified != null) "$kind, alterado ${modified.replaceFirstChar { it.lowercase() }}" else kind
}
