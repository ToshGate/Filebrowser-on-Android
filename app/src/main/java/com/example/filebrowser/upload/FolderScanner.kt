package com.example.filebrowser.upload

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.example.filebrowser.data.RemotePath
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class ScannedFile(
    val uri: Uri,
    /** Caminho relativo da pasta que contém o ficheiro ("" = raiz da pasta escolhida). */
    val relativeDir: String,
    val name: String,
    val size: Long,
)

data class FolderScan(
    val rootName: String,
    val files: List<ScannedFile>,
    /** Pastas sem nenhum ficheiro por baixo (o TUS só cria as pastas dos ficheiros que envia). */
    val emptyDirs: List<String>,
    val skipped: Int,
)

/** Percorre uma árvore do Storage Access Framework (ACTION_OPEN_DOCUMENT_TREE). */
object FolderScanner {

    private val PROJECTION = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE,
    )

    suspend fun scan(resolver: ContentResolver, treeUri: Uri): FolderScan {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootName = queryName(resolver, DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId))
            ?.let(::sanitize) ?: "Pasta"

        val files = mutableListOf<ScannedFile>()
        val dirs = mutableListOf<String>()
        var skipped = 0

        // Percurso em largura, iterativo: pastas muito fundas não rebentam a stack.
        val queue = ArrayDeque<Pair<String, String>>()
        queue.add(rootId to "")
        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (docId, relative) = queue.removeFirst()
            dirs += relative
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            resolver.query(childrenUri, PROJECTION, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val childId: String? = cursor.getString(0)
                    val name: String? = cursor.getString(1)?.let(::sanitize)
                    val mime: String? = cursor.getString(2)
                    val size = if (cursor.isNull(3)) -1L else cursor.getLong(3)
                    if (childId == null || name == null) {
                        skipped++
                    } else if (mime == Document.MIME_TYPE_DIR) {
                        queue.add(Pair(childId, joinRelative(relative, name)))
                    } else if (size < 0) {
                        skipped++ // O TUS precisa do tamanho à partida.
                    } else {
                        files.add(
                            ScannedFile(
                                uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId),
                                relativeDir = relative,
                                name = name,
                                size = size,
                            )
                        )
                    }
                }
            }
        }

        // Uma pasta é "vazia" se nenhum ficheiro estiver dentro dela ou das subpastas.
        // Só criamos as folhas: o servidor usa MkdirAll e cria os pais.
        val dirsWithFiles = files.flatMap { ancestors(it.relativeDir) }.toSet()
        val empty = dirs.filter { it !in dirsWithFiles }
        val emptyLeaves = empty.filter { dir -> empty.none { other -> isDescendant(other, dir) } }

        return FolderScan(rootName, files, emptyLeaves, skipped)
    }

    private fun ancestors(relative: String): List<String> {
        val parts = relative.split('/').filter { it.isNotEmpty() }
        return listOf("") + parts.indices.map { i -> parts.subList(0, i + 1).joinToString("/") }
    }

    private fun isDescendant(candidate: String, dir: String): Boolean =
        if (dir.isEmpty()) candidate.isNotEmpty() else candidate.startsWith("$dir/")

    private fun joinRelative(parent: String, name: String) = if (parent.isEmpty()) name else "$parent/$name"

    private fun sanitize(raw: String): String? =
        raw.trim().takeIf { RemotePath.nameError(it) == null }

    private fun queryName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
