package com.example.filebrowser.upload

import com.example.filebrowser.transfer.TransferStatus
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.filebrowser.AppGraph
import com.example.filebrowser.MainActivity
import com.example.filebrowser.data.ConflictException
import com.example.filebrowser.data.FatalApiException
import com.example.filebrowser.data.SessionExpiredException
import com.example.filebrowser.ui.formatSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Consome a fila de uploads do [UploadStore] com [CONCURRENCY] uploads em paralelo, até
 * não haver mais nada em fila. Se a rede falhar, devolve `retry()` e o WorkManager volta
 * a correr este worker quando houver ligação.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    /** O utilizador pausou ou cancelou o upload em curso. */
    private class StoppedByUser : Exception()

    @Volatile private var networkDown = false
    @Volatile private var sessionExpired = false

    override suspend fun doWork(): Result {
        val graph = AppGraph.get(applicationContext)
        val store = graph.uploadStore
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
            // Parado pelo sistema (rede, bateria, ...): o WorkManager volta a agendar-nos.
            store.requeueRunning()
            throw e
        } finally {
            store.flush()
        }
    }

    private suspend fun lane(graph: AppGraph) {
        while (!networkDown && !sessionExpired) {
            val record = graph.uploadStore.claimNext() ?: return
            process(graph, record)
        }
    }

    private suspend fun process(graph: AppGraph, record: UploadRecord) {
        val store = graph.uploadStore
        val id = record.id
        var lastProgress = 0L

        try {
            coroutineScope {
                // Se o estado deixar de ser RUNNING (pausa/cancelamento), interrompe este upload.
                val watcher = launch {
                    store.items.first { list -> list.firstOrNull { it.id == id }?.status != TransferStatus.RUNNING }
                    throw StoppedByUser()
                }
                graph.tus.upload(
                    TusJob(
                        uri = Uri.parse(record.uri),
                        remotePath = record.remotePath,
                        size = record.size,
                        override = record.override,
                        created = record.created,
                    ),
                    object : TusListener {
                        override fun onCreated() = store.update(id, flush = true) { it.copy(created = true) }

                        override fun onProgress(sent: Long) {
                            // Limita as actualizações da UI (com milhares de registos, cada uma custa).
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastProgress < PROGRESS_INTERVAL_MS) return
                            lastProgress = now
                            store.update(id, persist = false) { it.copy(sent = sent) }
                        }

                        override fun onChunkCommitted(offset: Long) =
                            store.update(id) { it.copy(sent = offset, attempts = 0) }
                    },
                )
                watcher.cancel()
            }
            store.update(id, flush = true) {
                it.copy(status = TransferStatus.DONE, sent = it.size, error = null, attempts = 0)
            }
            graph.uploads.releaseGrantIfUnused(record.grantUri)
        } catch (e: StoppedByUser) {
            // Estado já foi mudado pelo UploadManager.
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionExpiredException) {
            sessionExpired = true
            fail(store, id, e.message)
        } catch (e: ConflictException) {
            store.update(id, flush = true) {
                it.copy(status = TransferStatus.FAILED, error = e.message, conflict = true)
            }
        } catch (e: FatalApiException) {
            fail(store, id, e.message)
        } catch (e: IOException) {
            // O TusClient já tentou algumas vezes: provavelmente não há rede.
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

    private fun fail(store: UploadStore, id: String, message: String?) {
        store.update(id, flush = true) { it.copy(status = TransferStatus.FAILED, error = message ?: "Falhou.") }
    }

    // --- Notificação ---

    private suspend fun showForeground(store: UploadStore) {
        try {
            setForeground(foregroundInfo(store.items.value))
        } catch (e: Exception) {
            // Android 12+ proíbe iniciar foreground services a partir de background
            // (ex.: retoma automática quando a rede volta). Os uploads continuam na mesma.
        }
    }

    private fun foregroundInfo(records: List<UploadRecord>): ForegroundInfo {
        ensureChannel()
        val running = records.filter { it.status == TransferStatus.RUNNING }
        val active = records.filter { it.isActive }
        val total = active.sumOf { it.size }
        val sent = active.sumOf { it.sent }
        val percent = if (total > 0) ((sent * 100) / total).toInt() else 0

        val title = when (running.size) {
            0 -> "A preparar uploads…"
            1 -> running.first().name
            else -> "A carregar ${running.size} ficheiros"
        }
        val queued = active.size - running.size
        val text = buildString {
            append("${formatSize(sent)} de ${formatSize(total)} · $percent%")
            if (queued > 0) append(" · $queued em fila")
        }

        val openApp = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, percent, total == 0L)
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
                NotificationChannel(CHANNEL_ID, "Uploads", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        /** Uploads em simultâneo. Mais do que isto raramente acelera e sobrecarrega o servidor. */
        private const val CONCURRENCY = 2
        private const val MAX_ATTEMPTS = 10
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val CHANNEL_ID = "uploads"
        private const val NOTIFICATION_ID = 1001
    }
}
