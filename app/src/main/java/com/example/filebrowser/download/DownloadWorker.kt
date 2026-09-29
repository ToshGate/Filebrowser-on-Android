package com.example.filebrowser.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.filebrowser.AppGraph
import com.example.filebrowser.MainActivity
import com.example.filebrowser.data.FatalApiException
import com.example.filebrowser.data.SessionExpiredException
import com.example.filebrowser.transfer.TransferStatus
import com.example.filebrowser.transfer.mimeTypeFor
import com.example.filebrowser.ui.formatSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.io.FileNotFoundException
import java.io.IOException

/** Consome a fila de downloads, com o mesmo esquema do UploadWorker. */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private class StoppedByUser : Exception()

    @Volatile private var networkDown = false
    @Volatile private var sessionExpired = false

    override suspend fun doWork(): Result {
        val graph = AppGraph.get(applicationContext)
        val store = graph.downloadStore
        if (store.items.value.none { it.status == TransferStatus.QUEUED }) return Result.success()

        showForeground(store)
        return try {
            coroutineScope {
                val ticker = launch {
                    while (isActive) {
                        delay(1_000)
                        showForeground(store)
                    }
                }
                List(CONCURRENCY) { launch { lane(graph) } }.joinAll()
                ticker.cancel()
            }
            when {
                sessionExpired -> Result.failure()
                networkDown -> Result.retry()
                else -> Result.success()
            }
        } catch (e: CancellationException) {
            store.requeueRunning()
            throw e
        } finally {
            store.flush()
        }
    }

    private suspend fun lane(graph: AppGraph) {
        while (!networkDown && !sessionExpired) {
            val record = graph.downloadStore.claimNext() ?: return
            process(graph, record)
        }
    }

    private suspend fun process(graph: AppGraph, record: DownloadRecord) {
        val store = graph.downloadStore
        val id = record.id
        var lastProgress = 0L

        try {
            coroutineScope {
                val watcher = launch {
                    store.items.first { list -> list.firstOrNull { it.id == id }?.status != TransferStatus.RUNNING }
                    throw StoppedByUser()
                }
                graph.downloadClient.download(
                    DownloadJob(
                        remotePath = record.remotePath,
                        archive = record.kind == DownloadKind.ARCHIVE,
                        expectedSize = record.size,
                        lastModified = record.lastModified,
                    ),
                    object : DownloadListener {
                        override fun target(): Uri = targetFor(store, id)

                        override fun onResponse(lastModified: String?, totalSize: Long) {
                            store.update(id, flush = true) {
                                it.copy(
                                    lastModified = lastModified ?: it.lastModified,
                                    size = if (totalSize >= 0) totalSize else it.size,
                                )
                            }
                        }

                        override fun onProgress(received: Long) {
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastProgress < PROGRESS_INTERVAL_MS) return
                            lastProgress = now
                            store.update(id, persist = false) { it.copy(received = received) }
                        }
                    },
                )
                watcher.cancel()
            }
            store.update(id, flush = true) {
                it.copy(
                    status = TransferStatus.DONE,
                    received = if (it.size >= 0) it.size else it.received,
                    size = if (it.size >= 0) it.size else it.received,
                    error = null,
                    attempts = 0,
                )
            }
        } catch (e: StoppedByUser) {
            // Estado já foi mudado pelo DownloadManager.
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionExpiredException) {
            sessionExpired = true
            fail(store, id, e.message)
        } catch (e: FatalApiException) {
            fail(store, id, e.message)
        } catch (e: IOException) {
            val attempts = record.attempts + 1
            if (attempts >= MAX_ATTEMPTS) {
                fail(store, id, e.message ?: "Falha de rede.")
            } else {
                networkDown = true
                store.update(id, flush = true) {
                    it.copy(
                        status = TransferStatus.QUEUED,
                        attempts = attempts,
                        error = "Ligação interrompida — a tentar de novo…",
                    )
                }
            }
        } catch (e: Exception) {
            fail(store, id, e.message ?: "Erro inesperado.")
        }
    }

    /**
     * Nos downloads de pasta o ficheiro local só é criado aqui, e o Uri fica guardado logo,
     * para que a retoma escreva no mesmo ficheiro em vez de criar "nome (1)".
     */
    private fun targetFor(store: DownloadStore, id: String): Uri {
        val current = store.get(id) ?: throw FatalApiException("Download removido.")
        current.localUri?.let { return Uri.parse(it) }
        val parent = current.localParentUri ?: throw FatalApiException("Destino desconhecido.")
        val created = try {
            DocumentsContract.createDocument(
                applicationContext.contentResolver, Uri.parse(parent), mimeTypeFor(current.name), current.name
            )
        } catch (e: FileNotFoundException) {
            throw FatalApiException("A pasta de destino já não existe.")
        } catch (e: SecurityException) {
            throw FatalApiException("Perdeu-se o acesso à pasta de destino.")
        } ?: throw FatalApiException("Não foi possível criar \"${current.name}\".")
        store.update(id, flush = true) { it.copy(localUri = created.toString()) }
        return created
    }

    private fun fail(store: DownloadStore, id: String, message: String?) {
        store.update(id, flush = true) { it.copy(status = TransferStatus.FAILED, error = message ?: "Falhou.") }
    }

    // --- Notificação ---

    private suspend fun showForeground(store: DownloadStore) {
        try {
            setForeground(foregroundInfo(store.items.value))
        } catch (e: Exception) {
            // Android 12+ pode proibir iniciar o foreground service em background; continua na mesma.
        }
    }

    private fun foregroundInfo(records: List<DownloadRecord>): ForegroundInfo {
        ensureChannel()
        val running = records.filter { it.status == TransferStatus.RUNNING }
        val active = records.filter { it.isActive }
        val sizeKnown = active.all { it.size >= 0 }
        val total = active.sumOf { it.size.coerceAtLeast(0) }
        val received = active.sumOf { it.received }
        val percent = if (sizeKnown && total > 0) ((received * 100) / total).toInt() else 0

        val title = when (running.size) {
            0 -> "A preparar downloads…"
            1 -> running.first().name
            else -> "A descarregar ${running.size} ficheiros"
        }
        val queued = active.size - running.size
        val text = buildString {
            append(if (sizeKnown) "${formatSize(received)} de ${formatSize(total)} · $percent%" else formatSize(received))
            if (queued > 0) append(" · $queued em fila")
        }

        val openApp = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, percent, !sizeKnown || total == 0L)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        private const val CONCURRENCY = 2
        private const val MAX_ATTEMPTS = 10
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 1002
    }
}
