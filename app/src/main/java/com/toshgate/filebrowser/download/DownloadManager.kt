package com.toshgate.filebrowser.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.toshgate.filebrowser.data.FatalApiException
import com.toshgate.filebrowser.data.FileBrowserRepository
import com.toshgate.filebrowser.data.Resource
import com.toshgate.filebrowser.transfer.TransferStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

data class FolderDownloadResult(
    val folderName: String,
    val queued: Int,
    val totalBytes: Long,
    val skipped: Int,
)

/**
 * Fila de downloads, com o mesmo modelo dos uploads: um worker único e as acções do
 * utilizador são mudanças de estado no [DownloadStore].
 */
class DownloadManager(
    context: Context,
    private val store: DownloadStore,
    private val repository: FileBrowserRepository,
) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val workManager = WorkManager.getInstance(this.context)

    // --- Enfileirar ---

    /** [target] vem de ACTION_CREATE_DOCUMENT: o utilizador escolheu nome e sítio. */
    fun enqueueFile(item: Resource, target: Uri) {
        takeGrant(target)
        store.add(
            listOf(
                DownloadRecord(
                    id = UUID.randomUUID().toString(),
                    remotePath = item.path,
                    name = item.name,
                    size = item.size,
                    kind = DownloadKind.FILE,
                    localUri = target.toString(),
                    addedAt = System.currentTimeMillis(),
                )
            )
        )
        ensureWorker()
    }

    /** Pasta como um único ZIP gerado pelo servidor. */
    fun enqueueArchive(folder: Resource, target: Uri) {
        takeGrant(target)
        store.add(
            listOf(
                DownloadRecord(
                    id = UUID.randomUUID().toString(),
                    remotePath = folder.path,
                    name = "${folder.name}.zip",
                    size = -1,
                    kind = DownloadKind.ARCHIVE,
                    localUri = target.toString(),
                    addedAt = System.currentTimeMillis(),
                )
            )
        )
        ensureWorker()
    }

    /**
     * Recria a pasta remota dentro de [treeUri] (ACTION_OPEN_DOCUMENT_TREE), com subpastas.
     * As pastas locais são criadas já; os ficheiros só quando o download de cada um começa.
     * Se já existir uma pasta com o mesmo nome no destino, o sistema cria "Nome (1)".
     */
    suspend fun enqueueFolder(folder: Resource, treeUri: Uri): FolderDownloadResult = withContext(Dispatchers.IO) {
        takeGrant(treeUri)
        try {
            val treeDoc = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
            val rootDoc = createDirectory(treeDoc, folder.name)
            val batchId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val records = mutableListOf<DownloadRecord>()
            var skipped = 0

            val queue = ArrayDeque<Pair<String, Uri>>()
            queue.add(folder.path to rootDoc)
            while (queue.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val (remoteDir, localDir) = queue.removeFirst()
                for (item in repository.list(remoteDir).items.orEmpty()) {
                    if (item.isDir) {
                        // Ligações simbólicas para pastas podem criar ciclos.
                        if (item.isSymlink) {
                            skipped++
                        } else {
                            queue.add(item.path to createDirectory(localDir, item.name))
                        }
                    } else {
                        records += DownloadRecord(
                            id = UUID.randomUUID().toString(),
                            remotePath = item.path,
                            name = item.name,
                            size = item.size,
                            kind = DownloadKind.FILE,
                            localParentUri = localDir.toString(),
                            treeUri = treeUri.toString(),
                            addedAt = now,
                            batchId = batchId,
                            batchName = folder.name,
                        )
                    }
                }
            }

            store.add(records)
            if (records.isEmpty()) releaseGrantIfUnused(treeUri.toString()) else ensureWorker()
            FolderDownloadResult(folder.name, records.size, records.sumOf { it.size }, skipped)
        } catch (e: Exception) {
            releaseGrantIfUnused(treeUri.toString())
            throw e
        }
    }

    fun ensureWorker() {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(QUEUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    // --- Acções ---

    fun pause(id: String) {
        store.update(id, flush = true) { if (it.isActive) it.copy(status = TransferStatus.PAUSED) else it }
    }

    fun resume(id: String) {
        store.update(id, flush = true) { it.resumed() }
        ensureWorker()
    }

    suspend fun cancel(id: String) {
        val record = store.get(id) ?: return
        cancelRecords(listOf(record))
    }

    fun pauseBatch(batchId: String) {
        store.updateWhere({ it.batchId == batchId && it.isActive }) { it.copy(status = TransferStatus.PAUSED) }
    }

    fun resumeBatch(batchId: String) {
        store.updateWhere({
            it.batchId == batchId && (it.status == TransferStatus.PAUSED || it.status == TransferStatus.FAILED)
        }) { it.resumed() }
        ensureWorker()
    }

    suspend fun cancelBatch(batchId: String) {
        cancelRecords(store.items.value.filter { it.batchId == batchId })
    }

    fun clearFinished() {
        val finished = store.items.value.filter { it.isFinished }
        store.remove(finished.map { it.id })
        finished.map { it.grantUri }.distinct().forEach { releaseGrantIfUnused(it) }
    }

    suspend fun cancelAll() {
        val all = store.items.value
        store.updateWhere({ it.isActive }) { it.copy(status = TransferStatus.CANCELLED) }
        workManager.cancelAllWorkByTag(TAG)
        withContext(Dispatchers.IO) { all.forEach { deletePartial(it) } }
        store.clear()
        all.map { it.grantUri }.distinct().forEach { releaseGrant(it) }
    }

    /** Ao abrir a app: limpa os concluídos da sessão anterior e retoma a fila. */
    suspend fun reconcile() = withContext(Dispatchers.IO) {
        clearFinished()
        val running = runCatching { workManager.getWorkInfosForUniqueWork(QUEUE_WORK).get() }
            .getOrDefault(emptyList())
            .any { it.state == WorkInfo.State.RUNNING }
        if (!running) store.requeueRunning()
        if (store.items.value.any { it.status == TransferStatus.QUEUED }) ensureWorker()
    }

    private suspend fun cancelRecords(records: List<DownloadRecord>) {
        if (records.isEmpty()) return
        val ids = records.map { it.id }.toSet()
        // O worker vê CANCELLED e interrompe o download em curso.
        store.updateWhere({ it.id in ids && it.status != TransferStatus.DONE }) {
            it.copy(status = TransferStatus.CANCELLED)
        }
        withContext(Dispatchers.IO) { records.forEach { deletePartial(it) } }
        store.remove(ids)
        records.map { it.grantUri }.distinct().forEach { releaseGrantIfUnused(it) }
    }

    /** Um download cancelado não deve deixar um ficheiro meio escrito no telemóvel. */
    private fun deletePartial(record: DownloadRecord) {
        if (record.status == TransferStatus.DONE) return
        val uri = record.localUri ?: return
        runCatching { DocumentsContract.deleteDocument(resolver, Uri.parse(uri)) }
    }

    private fun createDirectory(parent: Uri, name: String): Uri = try {
        DocumentsContract.createDocument(resolver, parent, Document.MIME_TYPE_DIR, name)
    } catch (e: Exception) {
        null
    } ?: throw FatalApiException("Não foi possível criar a pasta \"$name\" no destino.")

    private fun DownloadRecord.resumed() = when (status) {
        TransferStatus.PAUSED, TransferStatus.FAILED ->
            copy(status = TransferStatus.QUEUED, error = null, attempts = 0)
        else -> this
    }

    /**
     * Ao contrário dos uploads, a permissão só é libertada quando o registo sai da lista:
     * é ela que permite abrir o ficheiro descarregado a partir do painel.
     */
    private fun releaseGrantIfUnused(grantUri: String) {
        if (grantUri.isEmpty()) return
        if (store.items.value.none { it.grantUri == grantUri }) releaseGrant(grantUri)
    }

    private fun takeGrant(uri: Uri) {
        try {
            resolver.takePersistableUriPermission(uri, GRANT_FLAGS)
        } catch (e: SecurityException) {
            // O fornecedor não suporta permissões persistentes; funciona enquanto o processo viver.
        }
    }

    private fun releaseGrant(uri: String) {
        if (uri.isEmpty()) return
        runCatching { resolver.releasePersistableUriPermission(Uri.parse(uri), GRANT_FLAGS) }
    }

    companion object {
        const val QUEUE_WORK = "download-queue"
        const val TAG = "download-queue"
        private const val GRANT_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
