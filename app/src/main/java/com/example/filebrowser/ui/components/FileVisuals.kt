package com.example.filebrowser.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.filebrowser.data.Resource

enum class FileKind { FOLDER, IMAGE, VIDEO, AUDIO, PDF, DOCUMENT, SHEET, ARCHIVE, CODE, APK, OTHER }

private val EXTENSIONS: Map<String, FileKind> = buildMap {
    listOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg", "avif").forEach { put(it, FileKind.IMAGE) }
    listOf("mp4", "mkv", "mov", "avi", "webm", "m4v", "wmv", "3gp").forEach { put(it, FileKind.VIDEO) }
    listOf("mp3", "flac", "wav", "ogg", "opus", "m4a", "aac", "wma").forEach { put(it, FileKind.AUDIO) }
    put("pdf", FileKind.PDF)
    listOf("doc", "docx", "odt", "rtf", "txt", "md", "epub").forEach { put(it, FileKind.DOCUMENT) }
    listOf("xls", "xlsx", "ods", "csv", "tsv").forEach { put(it, FileKind.SHEET) }
    listOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst").forEach { put(it, FileKind.ARCHIVE) }
    listOf(
        "kt", "kts", "java", "js", "ts", "py", "go", "rs", "c", "cpp", "h", "cs", "rb", "php",
        "sh", "json", "yaml", "yml", "toml", "xml", "html", "css", "sql",
    ).forEach { put(it, FileKind.CODE) }
    put("apk", FileKind.APK)
}

fun fileKind(item: Resource): FileKind {
    if (item.isDir) return FileKind.FOLDER
    EXTENSIONS[item.name.substringAfterLast('.', "").lowercase()]?.let { return it }
    // O servidor detecta alguns tipos pelo conteúdo.
    return when (item.type) {
        "image" -> FileKind.IMAGE
        "video" -> FileKind.VIDEO
        "audio" -> FileKind.AUDIO
        "pdf" -> FileKind.PDF
        "text" -> FileKind.DOCUMENT
        else -> FileKind.OTHER
    }
}

val FileKind.icon: ImageVector
    get() = when (this) {
        FileKind.FOLDER -> Icons.Default.Folder
        FileKind.IMAGE -> Icons.Default.Image
        FileKind.VIDEO -> Icons.Default.Movie
        FileKind.AUDIO -> Icons.Default.AudioFile
        FileKind.PDF -> Icons.Default.PictureAsPdf
        FileKind.DOCUMENT -> Icons.Default.Description
        FileKind.SHEET -> Icons.Default.TableChart
        FileKind.ARCHIVE -> Icons.Default.FolderZip
        FileKind.CODE -> Icons.Default.Code
        FileKind.APK -> Icons.Default.Android
        FileKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
    }

/** Fundo e cor do ícone: tudo tirado do esquema de cores, para funcionar com cor dinâmica. */
@Composable
fun FileKind.tileColors(): Pair<Color, Color> {
    val c = MaterialTheme.colorScheme
    return when (this) {
        FileKind.FOLDER -> c.primaryContainer to c.onPrimaryContainer
        FileKind.IMAGE, FileKind.VIDEO -> c.tertiaryContainer to c.onTertiaryContainer
        FileKind.AUDIO -> c.secondaryContainer to c.onSecondaryContainer
        FileKind.PDF -> c.errorContainer to c.onErrorContainer
        FileKind.DOCUMENT, FileKind.SHEET, FileKind.CODE -> c.secondaryContainer to c.onSecondaryContainer
        FileKind.ARCHIVE, FileKind.APK, FileKind.OTHER -> c.surfaceContainerHighest to c.onSurfaceVariant
    }
}

/** Ícone sobre um quadrado tonal arredondado. */
@Composable
fun IconTile(
    icon: ImageVector,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    Box(
        modifier
            .size(size)
            .clip(MaterialTheme.shapes.medium)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(size * 0.55f))
    }
}

/**
 * Miniatura do ficheiro. Imagens mostram a pré-visualização do servidor por cima do ícone;
 * se falhar (miniaturas desligadas, formato não suportado) fica só o ícone.
 */
@Composable
fun FileTile(
    item: Resource,
    thumbnailUrl: String?,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val kind = fileKind(item)
    val (container, content) = kind.tileColors()
    Box(modifier.size(size).clip(MaterialTheme.shapes.medium)) {
        IconTile(kind.icon, container, content, size = size)
        if (thumbnailUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(thumbnailUrl)
                    .crossfade(true)
                    .build(),
                imageLoader = imageLoader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
