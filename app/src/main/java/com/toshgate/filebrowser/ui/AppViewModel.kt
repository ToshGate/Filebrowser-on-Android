package com.toshgate.filebrowser.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.toshgate.filebrowser.AppGraph
import com.toshgate.filebrowser.data.RemotePath
import com.toshgate.filebrowser.data.Resource
import com.toshgate.filebrowser.data.SessionExpiredException
import com.toshgate.filebrowser.download.DownloadRecord
import com.toshgate.filebrowser.upload.UploadRecord
import com.toshgate.filebrowser.transfer.TransferStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class SortBy(val label: String) { NAME("Nome"), SIZE("Tamanho"), MODIFIED("Data de alteração") }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = AppGraph.get(app)
    private val repo = graph.repository
    private val uploadManager = graph.uploads
    private val downloadManager = graph.downloads

    var loggedIn by mutableStateOf(repo.isLoggedIn)
        private set
    var path by mutableStateOf("/")
        private set
    var items by mutableStateOf<List<Resource>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val lastServerUrl: String get() = repo.serverUrl
    val lastUsername: String get() = repo.savedUsername

    /** "utilizador em ficheiros.exemplo.pt", para o menu de conta. */
    val accountLabel: String?
        get() {
            val host = repo.serverUrl.toHttpUrlOrNull()?.host ?: return null
            val user = repo.savedUsername
            return if (user.isNotBlank()) "$user em $host" else host
        }

    var sortBy by mutableStateOf(runCatching { SortBy.valueOf(graph.session.sortBy) }.getOrDefault(SortBy.NAME))
        private set
    var sortAscending by mutableStateOf(graph.session.sortAscending)
        private set

    /** Listagem tal como veio do servidor; [items] é esta lista ordenada. */
    private var rawItems: List<Resource> = emptyList()

    val imageLoader get() = graph.imageLoader
    fun thumbnailUrl(item: Resource): String? = repo.thumbnailUrl(item)

    val uploads: StateFlow<List<UploadRecord>> = graph.uploadStore.items
    val downloads: StateFlow<List<DownloadRecord>> = graph.downloadStore.items

    /**
     * Item à espera de o utilizador escolher o destino no selector do sistema. Fica no
     * ViewModel para sobreviver à rotação do ecrã enquanto o selector está aberto.
     */
    var pendingDownload: Resource? = null

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Mensagens curtas para a Snackbar. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var loadJob: Job? = null
    private val finishedSeen = mutableSetOf<String>()

    init {
        viewModelScope.launch { uploadManager.reconcile() }
        viewModelScope.launch { downloadManager.reconcile() }
        // Quando um upload para a pasta aberta termina, actualiza a listagem.
        viewModelScope.launch {
            graph.uploadStore.items.collect { list ->
                val newlyDone = list.filter { it.status == TransferStatus.DONE && finishedSeen.add(it.id) }
                if (loggedIn && newlyDone.any { it.remoteDir == path }) load()
            }
        }
        if (loggedIn) load("/")
    }

    fun login(url: String, user: String, password: String, remember: Boolean) {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                repo.login(url, user, password, remember)
                loggedIn = true
                load("/")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Falha no login."
            } finally {
                loading = false
            }
        }
    }

    fun load(newPath: String = path) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            loading = true
            error = null
            try {
                repo.renewIfNeeded()
                val listing = repo.list(newPath)
                path = RemotePath.normalize(listing.path.ifBlank { newPath })
                rawItems = listing.items.orEmpty()
                applySort()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionExpiredException) {
                sessionLost(e)
            } catch (e: Exception) {
                error = e.message ?: "Não foi possível carregar a pasta."
            } finally {
                if (loadJob === coroutineContext[Job]) loading = false
            }
        }
    }

    /** Escolher o critério actual inverte o sentido; escolher outro começa no sentido natural. */
    fun sort(by: SortBy) {
        if (by == sortBy) {
            sortAscending = !sortAscending
        } else {
            sortBy = by
            // Tamanho e data fazem mais sentido do maior/mais recente para o menor/mais antigo.
            sortAscending = by == SortBy.NAME
        }
        graph.session.sortBy = sortBy.name
        graph.session.sortAscending = sortAscending
        applySort()
    }

    private fun applySort() {
        val comparator: Comparator<Resource> = when (sortBy) {
            SortBy.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortBy.SIZE -> compareBy { it.size }
            SortBy.MODIFIED -> compareBy { parseInstant(it.modified) }
        }
        // As pastas ficam sempre primeiro, seja qual for a ordem.
        items = rawItems.sortedWith(
            compareByDescending<Resource> { it.isDir }
                .then(if (sortAscending) comparator else comparator.reversed())
        )
    }

    fun open(item: Resource) {
        if (item.isDir) load(item.path)
    }

    fun back() {
        if (path != "/") load(RemotePath.parent(path))
    }

    fun delete(item: Resource) = mutate("\"${item.name}\" apagado.") { repo.delete(item.path) }

    fun createFolder(name: String) = mutate("Pasta criada.") { repo.createFolder(path, name) }

    fun rename(item: Resource, newName: String) =
        mutate("Nome alterado para \"${newName.trim()}\".") { repo.rename(item.path, newName) }

    private fun mutate(success: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                repo.renewIfNeeded()
                block()
                _messages.emit(success)
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionExpiredException) {
                sessionLost(e)
            } catch (e: Exception) {
                _messages.emit(e.message ?: "A operação falhou.")
            }
        }
    }

    // --- Uploads ---

    fun upload(uris: List<Uri>) {
        val target = path
        viewModelScope.launch {
            val result = uploadManager.enqueueFiles(uris, target)
            val msg = buildString {
                if (result.queued > 0) append("${result.queued} ficheiro(s) em fila.")
                if (result.skipped.isNotEmpty()) {
                    if (isNotEmpty()) append(' ')
                    append("${result.skipped.size} ignorado(s): tamanho desconhecido.")
                }
            }
            if (msg.isNotEmpty()) _messages.emit(msg)
        }
    }

    fun uploadFolder(treeUri: Uri) {
        val target = path
        viewModelScope.launch {
            _messages.emit("A ler a pasta…")
            try {
                repo.renewIfNeeded()
                val r = uploadManager.enqueueFolder(treeUri, target)
                val msg = buildString {
                    append("\"${r.folderName}\": ")
                    if (r.queued > 0) append("${r.queued} ficheiro(s), ${formatSize(r.totalBytes)} em fila.")
                    else append("sem ficheiros para enviar.")
                    if (r.skipped > 0) append(" ${r.skipped} ignorado(s).")
                    if (r.emptyDirsFailed > 0) append(" ${r.emptyDirsFailed} pasta(s) vazia(s) não criada(s).")
                }
                _messages.emit(msg)
                // Pastas vazias já existem no servidor; a pasta nova aparece logo na listagem.
                if (path == target) load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionExpiredException) {
                sessionLost(e)
            } catch (e: Exception) {
                _messages.emit(e.message ?: "Não foi possível ler a pasta.")
            }
        }
    }

    fun pauseUpload(id: String) = uploadManager.pause(id)
    fun resumeUpload(id: String) = uploadManager.resume(id)
    fun overrideUpload(id: String) = uploadManager.retryWithOverride(id)
    fun cancelUpload(id: String) {
        viewModelScope.launch { uploadManager.cancel(id) }
    }

    fun pauseBatch(batchId: String) = uploadManager.pauseBatch(batchId)
    fun resumeBatch(batchId: String) = uploadManager.resumeBatch(batchId)
    fun overrideBatch(batchId: String) = uploadManager.overrideBatchConflicts(batchId)
    fun cancelBatch(batchId: String) {
        viewModelScope.launch { uploadManager.cancelBatch(batchId) }
    }

    fun clearFinishedUploads() = uploadManager.clearFinished()

    // --- Downloads ---

    fun downloadFile(item: Resource, target: Uri) {
        downloadManager.enqueueFile(item, target)
        _messages.tryEmit("A descarregar \"${item.name}\"…")
    }

    fun downloadArchive(folder: Resource, target: Uri) {
        downloadManager.enqueueArchive(folder, target)
        _messages.tryEmit("A descarregar \"${folder.name}\" como ZIP…")
    }

    fun downloadFolder(folder: Resource, treeUri: Uri) {
        viewModelScope.launch {
            _messages.emit("A preparar \"${folder.name}\"…")
            try {
                repo.renewIfNeeded()
                val r = downloadManager.enqueueFolder(folder, treeUri)
                val msg = buildString {
                    append("\"${r.folderName}\": ")
                    if (r.queued > 0) append("${r.queued} ficheiro(s), ${formatSize(r.totalBytes)} em fila.")
                    else append("pasta criada, sem ficheiros para descarregar.")
                    if (r.skipped > 0) append(" ${r.skipped} ligação(ões) simbólica(s) ignorada(s).")
                }
                _messages.emit(msg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionExpiredException) {
                sessionLost(e)
            } catch (e: Exception) {
                _messages.emit(e.message ?: "Não foi possível preparar o download.")
            }
        }
    }

    fun pauseDownload(id: String) = downloadManager.pause(id)
    fun resumeDownload(id: String) = downloadManager.resume(id)
    fun cancelDownload(id: String) {
        viewModelScope.launch { downloadManager.cancel(id) }
    }
    fun pauseDownloadBatch(batchId: String) = downloadManager.pauseBatch(batchId)
    fun resumeDownloadBatch(batchId: String) = downloadManager.resumeBatch(batchId)
    fun cancelDownloadBatch(batchId: String) {
        viewModelScope.launch { downloadManager.cancelBatch(batchId) }
    }
    fun clearFinishedDownloads() = downloadManager.clearFinished()

    fun showMessage(message: String) {
        _messages.tryEmit(message)
    }

    /** "Terminar sessão" escolhido pelo utilizador: esquece também a palavra-passe guardada. */
    fun logout() {
        repo.logout()
        leaveSession(reason = null)
    }

    /**
     * A sessão caiu sem o utilizador pedir. Se ainda houver credenciais guardadas, o login
     * automático só falhou por agora (ex.: sem rede): fica tudo como está e mostra-se o erro.
     * Se já não houver (a palavra-passe mudou no servidor), volta-se ao ecrã de login.
     */
    private fun sessionLost(e: SessionExpiredException) {
        repo.dropToken()
        if (repo.isLoggedIn) {
            error = "Não foi possível voltar a entrar no servidor. Puxa a lista para tentar de novo."
        } else {
            leaveSession(reason = e.message)
        }
    }

    private fun leaveSession(reason: String?) {
        loadJob?.cancel()
        uploadManager.cancelAll()
        viewModelScope.launch { downloadManager.cancelAll() }
        loggedIn = false
        items = emptyList()
        rawItems = emptyList()
        path = "/"
        error = reason
    }
}
