package com.example.filebrowser.upload

import android.content.ContentResolver
import android.net.Uri
import com.example.filebrowser.data.ConflictException
import com.example.filebrowser.data.FatalApiException
import com.example.filebrowser.data.FileBrowserRepository
import com.example.filebrowser.data.RemotePath
import com.example.filebrowser.data.SessionExpiredException
import com.example.filebrowser.data.SessionStore
import com.example.filebrowser.data.httpError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class TusJob(
    val uri: Uri,
    val remotePath: String,
    val size: Long,
    /** Substituir um ficheiro que já exista com o mesmo nome. */
    val override: Boolean,
    /** Já fizemos o POST de criação numa tentativa anterior. */
    val created: Boolean,
)

interface TusListener {
    /** O servidor registou o upload; a partir daqui é seguro retomar com HEAD. */
    fun onCreated()
    /** Bytes enviados (inclui o chunk em curso, ainda não confirmado). */
    fun onProgress(sent: Long)
    /** O servidor confirmou os dados até [offset]. */
    fun onChunkCommitted(offset: Long)
}

/**
 * Cliente TUS 1.0 para o endpoint `/api/tus` do File Browser.
 *
 * Particularidades do servidor (http/tus_handlers.go):
 *  - POST cria o ficheiro vazio e regista `Upload-Length` numa cache (memória ou Redis).
 *    Se o ficheiro já existir devolve 409, a menos que `?override=true`.
 *  - HEAD devolve `Upload-Offset` = tamanho actual do ficheiro. Devolve 404 se o upload
 *    já não estiver na cache (terminou, expirou, ou o servidor reiniciou).
 *  - PATCH exige `Content-Type: application/offset+octet-stream` exacto e devolve 409
 *    se o offset não bater certo com o tamanho do ficheiro no disco.
 */
class TusClient(
    private val session: SessionStore,
    private val client: OkHttpClient,
    private val resolver: ContentResolver,
    private val repository: FileBrowserRepository,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
) {
    private class State(var created: Boolean)

    suspend fun upload(job: TusJob, listener: TusListener): Unit = withContext(Dispatchers.IO) {
        val state = State(job.created)
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
                // Falhas de rede transitórias: tentamos aqui algumas vezes (rápido) antes de
                // devolver o controlo ao WorkManager, que tem backoff mais longo.
                attempt++
                if (attempt >= IN_PROCESS_RETRIES) throw e
                delay(2_000L * attempt)
            }
        }
    }

    /** Apaga um upload incompleto no servidor. */
    suspend fun abort(remotePath: String): Unit = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(tusUrl(remotePath))
            .delete()
            .header("Tus-Resumable", TUS_VERSION)
            .build()
        client.newCall(request).await().close()
    }

    private suspend fun runOnce(job: TusJob, state: State, listener: TusListener) {
        repository.renewIfNeeded()

        val start = prepare(job, state, listener)
        if (start == null || start >= job.size) {
            listener.onChunkCommitted(job.size)
            return
        }

        var offset: Long = start
        listener.onProgress(offset)

        openAt(job.uri, offset).use { input ->
            val buffer = ByteArray(minOf(chunkSize.toLong(), job.size - offset).toInt())
            while (offset < job.size) {
                currentCoroutineContext().ensureActive()
                repository.renewIfNeeded()

                val length = minOf(buffer.size.toLong(), job.size - offset).toInt()
                readFully(input, buffer, length)

                val chunkStart = offset
                val newOffset = patch(job.remotePath, chunkStart, buffer, length) { written ->
                    listener.onProgress(chunkStart + written)
                }
                if (newOffset != chunkStart + length) {
                    // O stream local já avançou; a próxima tentativa refaz o HEAD e reabre o ficheiro.
                    throw IOException("O servidor confirmou um offset inesperado ($newOffset).")
                }
                offset = newOffset
                listener.onChunkCommitted(offset)
            }
        }
    }

    /**
     * Garante que o upload existe no servidor e devolve o offset por onde continuar,
     * ou null se o ficheiro já estiver completo no servidor.
     */
    private suspend fun prepare(job: TusJob, state: State, listener: TusListener): Long? {
        if (state.created) {
            val remote = head(job.remotePath)
            if (remote != null && remote.length == job.size) return remote.offset

            if (remote == null) {
                // O servidor esqueceu o upload. Se o ficheiro já tem o tamanho todo, é porque o
                // último PATCH chegou mas a app morreu antes de o registar.
                val existing = repository.statOrNull(job.remotePath)
                if (existing != null && !existing.isDir && existing.size == job.size) return null
            }
            // Recomeçar: o ficheiro parcial é nosso, por isso podemos substituí-lo.
            if (!post(job.remotePath, job.size, override = true)) {
                throw ConflictException("Não foi possível reiniciar o upload de \"${RemotePath.name(job.remotePath)}\".")
            }
            return 0L
        }

        if (!post(job.remotePath, job.size, override = job.override)) {
            // 409. Pode ser um POST nosso que teve sucesso mas cuja resposta se perdeu na rede:
            // nesse caso o servidor tem um upload em curso com o mesmo tamanho.
            val remote = head(job.remotePath)
            if (remote == null || remote.length != job.size) {
                throw ConflictException("Já existe \"${RemotePath.name(job.remotePath)}\" nesta pasta.")
            }
            state.created = true
            listener.onCreated()
            return remote.offset
        }
        state.created = true
        listener.onCreated()
        return 0L
    }

    /** @return false se o servidor respondeu 409 (o ficheiro já existe). */
    private suspend fun post(path: String, size: Long, override: Boolean): Boolean {
        val request = Request.Builder()
            .url(tusUrl(path) { addQueryParameter("override", override.toString()) })
            .post(ByteArray(0).toRequestBody())
            .header("Tus-Resumable", TUS_VERSION)
            .header("Upload-Length", size.toString())
            .build()
        return client.newCall(request).await().use { response ->
            when {
                response.code == 409 -> false
                response.isSuccessful -> true
                else -> throw httpError(response.code)
            }
        }
    }

    private data class RemoteState(val offset: Long, val length: Long)

    private suspend fun head(path: String): RemoteState? {
        val request = Request.Builder()
            .url(tusUrl(path))
            .head()
            .header("Tus-Resumable", TUS_VERSION)
            .header("Cache-Control", "no-store")
            .build()
        return client.newCall(request).await().use { response ->
            when {
                response.code == 404 -> null
                !response.isSuccessful -> throw httpError(response.code)
                else -> RemoteState(
                    offset = response.header("Upload-Offset")?.toLongOrNull()
                        ?: throw IOException("Resposta TUS sem Upload-Offset."),
                    length = response.header("Upload-Length")?.toLongOrNull() ?: -1L,
                )
            }
        }
    }

    private suspend fun patch(
        path: String,
        offset: Long,
        buffer: ByteArray,
        length: Int,
        onBytes: (Long) -> Unit,
    ): Long {
        val request = Request.Builder()
            .url(tusUrl(path))
            .patch(ChunkBody(buffer, length, onBytes))
            .header("Tus-Resumable", TUS_VERSION)
            .header("Upload-Offset", offset.toString())
            .build()
        return client.newCall(request).await().use { response ->
            when (response.code) {
                200, 204 -> response.header("Upload-Offset")?.toLongOrNull() ?: (offset + length)
                // Offset dessincronizado ou upload esquecido pelo servidor: a nova tentativa resolve.
                404, 409 -> throw IOException("Upload dessincronizado (HTTP ${response.code}).")
                else -> throw httpError(response.code)
            }
        }
    }

    private fun tusUrl(path: String, query: HttpUrl.Builder.() -> Unit = {}): HttpUrl {
        val base = session.baseUrl.toHttpUrlOrNull() ?: throw SessionExpiredException()
        return base.newBuilder().apply {
            addPathSegment("api")
            addPathSegment("tus")
            // addPathSegment codifica cada nome (espaços, #, ?, %…).
            RemotePath.segments(path).forEach { addPathSegment(it) }
            query()
        }.build()
    }

    /** Abre o ficheiro local já posicionado em [offset], sem ler os bytes anteriores se possível. */
    private fun openAt(uri: Uri, offset: Long): InputStream {
        if (offset > 0) {
            try {
                val pfd = resolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    val stream = android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)
                    try {
                        stream.channel.position(offset)
                        return stream
                    } catch (e: Exception) {
                        stream.close() // Não é seekable (ex.: pipe) — cai para o skip.
                    }
                }
            } catch (e: SecurityException) {
                throw FatalApiException("Perdeu-se o acesso ao ficheiro local.")
            }
        }
        val input = try {
            resolver.openInputStream(uri)
        } catch (e: SecurityException) {
            null
        } ?: throw FatalApiException("Não foi possível abrir o ficheiro local.")
        var remaining = offset
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() == -1) {
                    input.close()
                    throw FatalApiException("O ficheiro local é mais pequeno do que o esperado.")
                }
                remaining--
            } else {
                remaining -= skipped
            }
        }
        return input
    }

    private fun readFully(input: InputStream, buffer: ByteArray, length: Int) {
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n == -1) throw FatalApiException("O ficheiro local mudou durante o upload.")
            read += n
        }
    }

    /**
     * Corpo de um chunk. Os dados estão em memória para o OkHttp poder repetir o pedido
     * em caso de falha de ligação (um InputStream só se consegue ler uma vez).
     */
    private class ChunkBody(
        private val buffer: ByteArray,
        private val length: Int,
        private val onBytes: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType(): MediaType = OFFSET_OCTET_STREAM
        override fun contentLength(): Long = length.toLong()
        override fun writeTo(sink: BufferedSink) {
            var written = 0
            while (written < length) {
                val n = minOf(WRITE_STEP, length - written)
                sink.write(buffer, written, n)
                written += n
                onBytes(written.toLong())
            }
        }
    }

    companion object {
        const val TUS_VERSION = "1.0.0"
        /** O mesmo valor por omissão que a interface web do File Browser. */
        const val DEFAULT_CHUNK_SIZE = 10 * 1024 * 1024
        private const val WRITE_STEP = 64 * 1024
        private const val IN_PROCESS_RETRIES = 3
        private val OFFSET_OCTET_STREAM = "application/offset+octet-stream".toMediaType()
    }
}

/** Executa a chamada sem bloquear e cancela o pedido HTTP se a coroutine for cancelada. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            // Se a coroutine já foi cancelada ninguém vai ler a resposta: fecha-a para
            // devolver a ligação ao pool do OkHttp.
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}
