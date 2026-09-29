package com.example.filebrowser.download

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.example.filebrowser.data.FatalApiException
import com.example.filebrowser.data.FileBrowserRepository
import com.example.filebrowser.data.RemotePath
import com.example.filebrowser.data.SessionExpiredException
import com.example.filebrowser.data.SessionStore
import com.example.filebrowser.data.httpError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

data class DownloadJob(
    val remotePath: String,
    val archive: Boolean,
    /** Tamanho esperado (-1 se desconhecido). */
    val expectedSize: Long,
    val lastModified: String?,
)

interface DownloadListener {
    /** Uri local onde escrever. Pode criar o documento na primeira chamada. */
    fun target(): Uri
    /** Cabeçalhos recebidos: Last-Modified e tamanho total (-1 se desconhecido). */
    fun onResponse(lastModified: String?, totalSize: Long)
    fun onProgress(received: Long)
}

/**
 * Downloads a partir de `GET /api/raw/<caminho>` (http/raw.go no File Browser).
 *
 *  - Ficheiros são servidos com `http.ServeContent`: suporta `Range` e `If-Range`, por isso
 *    um download interrompido continua a partir do tamanho do ficheiro local.
 *  - Pastas com `?algo=zip` são geradas em streaming: sem Content-Length e sem retoma.
 *  - Sem permissão de download o servidor responde 202 com corpo vazio (não 403).
 */
class DownloadClient(
    private val session: SessionStore,
    private val client: OkHttpClient,
    private val resolver: ContentResolver,
    private val repository: FileBrowserRepository,
) {
    private class State(var restartFromZero: Boolean = false)

    suspend fun download(job: DownloadJob, listener: DownloadListener): Unit = withContext(Dispatchers.IO) {
        val state = State()
        var attempt = 0
        while (true) {
            try {
                runOnce(job, state, listener)
                return@withContext
            } catch (e: CancellationException) {
                throw e
            } catch (e: FatalApiException) {
                throw e
            } catch (e: IOException) {
                // Ao cancelar, fechamos a ligação e a leitura falha com IOException: não é para repetir.
                currentCoroutineContext().ensureActive()
                attempt++
                if (attempt >= IN_PROCESS_RETRIES) throw e
                delay(2_000L * attempt)
            }
        }
    }

    private suspend fun runOnce(job: DownloadJob, state: State, listener: DownloadListener) {
        repository.renewIfNeeded()
        val target = listener.target()

        var offset = if (job.archive || state.restartFromZero) 0L else localLength(target)
        if (job.expectedSize in 0 until offset) offset = 0L // Local maior que o remoto: recomeçar.
        if (!job.archive && offset > 0 && offset == job.expectedSize) {
            // A app morreu depois de escrever tudo mas antes de marcar como concluído.
            listener.onProgress(offset)
            return
        }

        openOutput(target, offset).use { output ->
            val request = Request.Builder()
                .url(rawUrl(job))
                .get()
                // Sem compressão transparente: os offsets têm de corresponder a bytes do ficheiro.
                .header("Accept-Encoding", "identity")
                .apply {
                    if (output.offset > 0) {
                        header("Range", "bytes=${output.offset}-")
                        // Se o ficheiro mudou no servidor, recebemos 200 com o ficheiro inteiro.
                        job.lastModified?.let { header("If-Range", it) }
                    }
                }
                .build()

            val call = client.newCall(request)
            coroutineScope {
                // A leitura do corpo é bloqueante: se a coroutine for cancelada (pausa), cancelar
                // a chamada fecha o socket e desbloqueia a leitura.
                val canceller = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
                try {
                    call.execute().use { response ->
                        val start = when (response.code) {
                            206 -> {
                                val range = response.header("Content-Range").orEmpty()
                                if (!range.startsWith("bytes ${output.offset}-")) {
                                    state.restartFromZero = true
                                    throw IOException("Content-Range inesperado: $range")
                                }
                                output.offset
                            }
                            200 -> {
                                output.resetToStart()
                                0L
                            }
                            202 -> throw FatalApiException("Não tens permissão para descarregar neste servidor.")
                            416 -> {
                                // O ficheiro no servidor ficou mais pequeno do que o que já temos.
                                state.restartFromZero = true
                                throw IOException("O ficheiro mudou no servidor; a recomeçar.")
                            }
                            else -> throw httpError(response.code)
                        }

                        val body = response.body ?: throw IOException("Resposta sem corpo.")
                        val total = when (response.code) {
                            206 -> response.header("Content-Range")
                                ?.substringAfterLast('/')
                                ?.toLongOrNull() ?: -1L
                            else -> body.contentLength()
                        }
                        listener.onResponse(response.header("Last-Modified"), total)

                        val received = copy(body.byteStream(), output.stream, start, listener)
                        if (total >= 0 && received != total) {
                            throw IOException("Transferência incompleta ($received de $total bytes).")
                        }
                        output.sync()
                    }
                } finally {
                    canceller.cancel()
                }
            }
        }
    }

    private suspend fun copy(input: InputStream, out: OutputStream, start: Long, listener: DownloadListener): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var received = start
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer)
            if (n == -1) break
            out.write(buffer, 0, n)
            received += n
            listener.onProgress(received)
        }
        out.flush()
        return received
    }

    private fun rawUrl(job: DownloadJob): HttpUrl {
        val base = session.baseUrl.toHttpUrlOrNull() ?: throw SessionExpiredException()
        return base.newBuilder().apply {
            addPathSegment("api")
            addPathSegment("raw")
            RemotePath.segments(job.remotePath).forEach { addPathSegment(it) }
            if (job.archive) addQueryParameter("algo", "zip")
        }.build()
    }

    /** Tamanho actual do ficheiro local; é daqui que se retoma. */
    private fun localLength(uri: Uri): Long = try {
        resolver.openFileDescriptor(uri, "r")?.use { it.statSize.coerceAtLeast(0L) } ?: 0L
    } catch (e: SecurityException) {
        throw FatalApiException("Perdeu-se o acesso ao destino.")
    } catch (e: Exception) {
        0L
    }

    private class LocalOutput(val stream: OutputStream, var offset: Long) : Closeable {
        private val channel get() = (stream as? FileOutputStream)?.channel

        /** O servidor enviou o ficheiro inteiro: apagar o que já tínhamos. */
        fun resetToStart() {
            if (offset == 0L) return
            val ch = channel ?: throw IOException("Não é possível recomeçar a escrita no destino.")
            ch.truncate(0)
            ch.position(0)
            offset = 0
        }

        fun sync() {
            runCatching { (stream as? FileOutputStream)?.fd?.sync() }
        }

        override fun close() = stream.close()
    }

    /**
     * Abre o destino posicionado em [offset]. Retomar exige o modo "rw" com seek, que nem todos
     * os DocumentsProviders suportam (ex.: alguns de cloud); nesses recomeça-se do zero.
     */
    private fun openOutput(uri: Uri, offset: Long): LocalOutput {
        if (offset > 0) {
            try {
                val pfd = resolver.openFileDescriptor(uri, "rw")
                if (pfd != null) {
                    val out = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
                    try {
                        out.channel.truncate(offset)
                        out.channel.position(offset)
                        return LocalOutput(out, offset)
                    } catch (e: Exception) {
                        out.close()
                    }
                }
            } catch (e: SecurityException) {
                throw FatalApiException("Perdeu-se o acesso ao destino.")
            } catch (e: Exception) {
                // Modo "rw" não suportado: recomeça do zero abaixo.
            }
        }
        val stream = openTruncating(uri, "wt") ?: openTruncating(uri, "w")
            ?: throw FatalApiException("Não foi possível escrever no destino.")
        return LocalOutput(stream, 0L)
    }

    private fun openTruncating(uri: Uri, mode: String): OutputStream? = try {
        resolver.openOutputStream(uri, mode)
    } catch (e: SecurityException) {
        throw FatalApiException("Perdeu-se o acesso ao destino.")
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val IN_PROCESS_RETRIES = 3
        const val BUFFER_SIZE = 64 * 1024
    }
}
