package com.toshgate.filebrowser.upload

import com.toshgate.filebrowser.transfer.TransferStatus
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.toshgate.filebrowser.data.FileBrowserRepository
import com.toshgate.filebrowser.data.RemotePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

data class EnqueueResult(val queued: Int, val skipped: List<String>)

data class FolderEnqueueResult(
    val folderName: String,
    val queued: Int,
    val totalBytes: Long,
    val emptyDirsCreated: Int,
    val emptyDirsFailed: Int,
    val skipped: Int,
)

/**
 * Todos os uploads passam por uma única fila (um worker único, com poucos uploads em
 * paralelo). Pausar/retomar/cancelar é só mudar o estado no [UploadStore]: o worker
 * observa-o e reage.
 */
class UploadManager(
    context: Context,
    private val store: UploadStore,
    private val tus: TusClient,
    private val repository: FileBrowserRepository,
) {
    private val context = context.applicationContext
    private val workManager = WorkManager.getInstance(this.context)

    // --- Enfileirar ---

    /** Ficheiros escolhidos com ACTION_OPEN_DOCUMENT. */
    suspend fun enqueueFiles(uris: List<Uri>, remoteDir: String): EnqueueResult = withContext(Dispatchers.IO) {
        val skipped = mutableListOf<String>()
        val now = System.currentTimeMillis()
        val records = uris.mapNotNull { uri ->
            val info = describe(uri)
            if (info == null) {
                skipped += uri.lastPathSegment ?: uri.toString()
                return@mapNotNull null
            }
            takeGrant(uri)
            UploadRecord(
                id = UUID.randomUUID().toString(),
                uri = uri.toString(),
                name = info.first,
                remoteDir = RemotePath.normalize(remoteDir),
                size = info.second,
                addedAt = now,
            )
        }
        store.add(records)
        ensureWorker()
        EnqueueResult(records.size, skipped)
    }

    /**
     * Pasta escolhida com ACTION_OPEN_DOCUMENT_TREE. É recriada no servidor dentro de
     * [remoteDir] com o mesmo nome; se já existir, o conteúdo é juntado (ficheiros com o
     * mesmo nome ficam em conflito e podem ser substituídos a partir do painel).
     */
    suspend fun enqueueFolder(treeUri: Uri, remoteDir: String): FolderEnqueueResult = withContext(Dispatchers.IO) {
        takeGrant(treeUri)
        val scan = try {
            FolderScanner.scan(context.contentResolver, treeUri)
        } catch (e: Exception) {
            releaseGrant(treeUri.toString())
            throw e
        }

        val root = RemotePath.join(remoteDir, scan.rootName)
        val batchId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val records = scan.files.map { file ->
            UploadRecord(
                id = UUID.randomUUID().toString(),
                uri = file.uri.toString(),
                name = file.name,
                remoteDir = RemotePath.join(root, file.relativeDir),
                size = file.size,
                addedAt = now,
                batchId = batchId,
                batchName = scan.rootName,
                treeUri = treeUri.toString(),
            )
        }

        // O POST do TUS cria as pastas-pai de cada ficheiro, mas pastas vazias têm de ser criadas à parte.
        var created = 0
        var failed = 0
        for (dir in scan.emptyDirs) {
            try {
                repository.mkdirs(RemotePath.join(root, dir))
                created++
            } catch (e: Exception) {
                failed++
            }
        }

        if (records.isEmpty()) releaseGrant(treeUri.toString())
        store.add(records)
        ensureWorker()

        FolderEnqueueResult(
            folderName = scan.rootName,
            queued = records.size,
            totalBytes = records.sumOf { it.size },
            emptyDirsCreated = created,
            emptyDirsFailed = failed,
            skipped = scan.skipped,
        )
    }

    /**
     * Garante que há um worker a consumir a fila. APPEND_OR_REPLACE evita a corrida em que
     * o worker actual está a terminar no momento em que chegam uploads novos: o próximo
     * corre a seguir e apanha-os (se não houver nada, termina logo).
     */
    fun ensureWorker() {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(QUEUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    // --- Acções sobre um upload ---

    fun pause(id: String) {
        store.update(id, flush = true) { if (it.isActive) it.copy(status = TransferStatus.PAUSED) else it }
    }

    fun resume(id: String) {
        store.update(id, flush = true) { it.resumed() }
        ensureWorker()
    }

    fun retryWithOverride(id: String) {
        store.update(id, flush = true) { it.copy(override = true).resumed() }
        ensureWorker()
    }

    suspend fun cancel(id: String) {
        val record = store.get(id) ?: return
        cancelRecords(listOf(record))
    }

    // --- Acções sobre uma pasta inteira ---

    fun pauseBatch(batchId: String) {
        store.updateWhere({ it.batchId == batchId && it.isActive }) { it.copy(status = TransferStatus.PAUSED) }
    }

    /** Retoma os pausados e volta a tentar os que falharam (excepto conflitos, que pedem decisão). */
    fun resumeBatch(batchId: String) {
        store.updateWhere({
            it.batchId == batchId &&
                (it.status == TransferStatus.PAUSED || (it.status == TransferStatus.FAILED && !it.conflict))
        }) { it.resumed() }
        ensureWorker()
    }

    fun overrideBatchConflicts(batchId: String) {
        store.updateWhere({ it.batchId == batchId && it.status == TransferStatus.FAILED && it.conflict }) {
            it.copy(override = true).resumed()
        }
        ensureWorker()
    }

    suspend fun cancelBatch(batchId: String) {
        cancelRecords(store.items.value.filter { it.batchId == batchId })
    }

    // --- Limpeza ---

    fun clearFinished() {
        val finished = store.items.value.filter { it.isFinished }
        store.remove(finished.map { it.id })
        finished.map { it.grantUri }.distinct().forEach { releaseGrantIfUnused(it) }
    }

    fun cancelAll() {
        val grants = store.items.value.map { it.grantUri }.distinct()
        store.updateWhere({ it.isActive }) { it.copy(status = TransferStatus.CANCELLED) }
        workManager.cancelAllWorkByTag(TAG)
        store.clear()
        grants.forEach { releaseGrant(it) }
    }

    /**
     * Ao abrir a app: uploads marcados como RUNNING sem worker vivo (ex.: app forçada a
     * parar) voltam à fila, e a fila é retomada.
     */
    suspend fun reconcile() = withContext(Dispatchers.IO) {
        // Os concluídos só interessam na sessão em que terminaram; não os arrastamos para a
        // seguinte. Os falhados ficam, porque precisam de uma decisão do utilizador.
        clearFinished()

        // Versões anteriores tinham um worker por upload; esses trabalhos já não são usados.
        workManager.cancelAllWorkByTag(LEGACY_TAG)

        val running = runCatching { workManager.getWorkInfosForUniqueWork(QUEUE_WORK).get() }
            .getOrDefault(emptyList())
            .any { it.state == WorkInfo.State.RUNNING }
        if (!running) store.requeueRunning()
        if (store.items.value.any { it.status == TransferStatus.QUEUED }) ensureWorker()
    }

    /** Chamado quando um upload termina: liberta a permissão se mais nenhum upload precisar dela. */
    fun releaseGrantIfUnused(grantUri: String) {
        val stillNeeded = store.items.value.any { !it.isFinished && it.grantUri == grantUri }
        if (!stillNeeded) releaseGrant(grantUri)
    }

    private suspend fun cancelRecords(records: List<UploadRecord>) {
        if (records.isEmpty()) return
        val ids = records.map { it.id }.toSet()
        // O worker vê CANCELLED e interrompe o upload em curso.
        store.updateWhere({ it.id in ids && it.status != TransferStatus.DONE }) {
            it.copy(status = TransferStatus.CANCELLED)
        }
        withContext(Dispatchers.IO) {
            records.filter { it.created && it.status != TransferStatus.DONE }.forEach { record ->
                runCatching { tus.abort(record.remotePath) }
            }
        }
        store.remove(ids)
        records.map { it.grantUri }.distinct().forEach { releaseGrantIfUnused(it) }
    }

    private fun UploadRecord.resumed() = when (status) {
        TransferStatus.PAUSED, TransferStatus.FAILED ->
            copy(status = TransferStatus.QUEUED, error = null, conflict = false, attempts = 0)
        else -> this
    }

    private fun takeGrant(uri: Uri) {
        // Sem isto o acesso ao Uri perde-se quando a app é morta e o worker não consegue retomar.
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // O fornecedor não suporta permissões persistentes; funciona enquanto o processo viver.
        }
    }

    private fun releaseGrant(uri: String) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    /** Nome e tamanho. O TUS precisa do tamanho à partida (Upload-Length). */
    private fun describe(uri: Uri): Pair<String, Long>? {
        var name: String? = null
        var size = -1L
        runCatching {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        if (size < 0) {
            size = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull() ?: -1L
        }
        if (size < 0) return null
        val safeName = name
            ?.replace('/', '_')
            ?.replace('\\', '_')
            ?.trim()
            ?.takeIf { RemotePath.nameError(it) == null }
            ?: "upload-${System.currentTimeMillis()}"
        return safeName to size
    }

    companion object {
        const val QUEUE_WORK = "upload-queue"
        const val TAG = "upload-queue"
        private const val LEGACY_TAG = "upload"
    }
}
