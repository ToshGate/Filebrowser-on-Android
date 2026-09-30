package com.toshgate.filebrowser.transfer

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class TransferStatus { QUEUED, RUNNING, PAUSED, DONE, FAILED, CANCELLED }

/** O que uploads e downloads têm em comum — é o que a fila e o painel precisam de saber. */
interface Transfer<T : Transfer<T>> {
    val id: String
    val name: String
    /** Tamanho total em bytes, ou -1 se desconhecido (ex.: ZIP gerado pelo servidor). */
    val size: Long
    val transferred: Long
    val status: TransferStatus
    val error: String?
    /** Falhou porque o destino já existe (só uploads). */
    val conflict: Boolean
    val batchId: String?
    val batchName: String?
    val addedAt: Long
    /** Texto opcional para o painel ("→ /pasta"). */
    val destination: String?
    /** O Uri com a permissão persistente do SAF. */
    val grantUri: String

    fun withStatus(status: TransferStatus): T

    val fraction: Float
        get() = if (size <= 0) 0f else (transferred.toDouble() / size).toFloat().coerceIn(0f, 1f)
    val isActive: Boolean
        get() = status == TransferStatus.QUEUED || status == TransferStatus.RUNNING
    val isFinished: Boolean
        get() = status == TransferStatus.DONE || status == TransferStatus.CANCELLED
}

/**
 * Lista de transferências persistida em SharedPreferences. O worker e a UI correm no mesmo
 * processo e partilham este StateFlow.
 *
 * As escritas em disco são agrupadas (no máximo uma por segundo); as transições importantes
 * usam `flush = true`. Se se perder o último segundo, a retoma reconcilia com o servidor
 * (HEAD do TUS) ou com o tamanho do ficheiro local (downloads).
 */
open class TransferStore<T : Transfer<T>>(
    context: Context,
    prefsName: String,
    private val arrayType: Class<Array<T>>,
) {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val state = MutableStateFlow(read())
    private val dirty = Channel<Unit>(Channel.CONFLATED)

    val items: StateFlow<List<T>> = state.asStateFlow()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            while (true) {
                dirty.receive()
                writeNow()
                delay(1_000)
            }
        }
    }

    fun get(id: String): T? = state.value.firstOrNull { it.id == id }

    @Synchronized
    fun add(records: List<T>) {
        if (records.isEmpty()) return
        state.value = state.value + records
        writeNow()
    }

    /**
     * @param persist false para actualizações muito frequentes (progresso).
     * @param flush grava já, em vez de esperar pela próxima escrita agrupada.
     */
    @Synchronized
    fun update(id: String, persist: Boolean = true, flush: Boolean = false, transform: (T) -> T) {
        state.value = state.value.map { if (it.id == id) transform(it) else it }
        save(persist, flush)
    }

    /** Actualiza vários registos numa única operação (acções sobre uma pasta inteira). */
    @Synchronized
    fun updateWhere(predicate: (T) -> Boolean, transform: (T) -> T) {
        state.value = state.value.map { if (predicate(it)) transform(it) else it }
        writeNow()
    }

    /** Passa o próximo registo em fila para RUNNING e devolve-o. Atómico entre lanes. */
    @Synchronized
    fun claimNext(): T? {
        val next = state.value.firstOrNull { it.status == TransferStatus.QUEUED } ?: return null
        val claimed = next.withStatus(TransferStatus.RUNNING)
        state.value = state.value.map { if (it.id == next.id) claimed else it }
        save(persist = true, flush = false)
        return claimed
    }

    /** Registos marcados como RUNNING por um worker que já não existe voltam à fila. */
    @Synchronized
    fun requeueRunning() {
        if (state.value.none { it.status == TransferStatus.RUNNING }) return
        state.value = state.value.map {
            if (it.status == TransferStatus.RUNNING) it.withStatus(TransferStatus.QUEUED) else it
        }
        writeNow()
    }

    @Synchronized
    fun remove(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val set = ids.toSet()
        state.value = state.value.filterNot { it.id in set }
        writeNow()
    }

    @Synchronized
    fun clear() {
        state.value = emptyList()
        writeNow()
    }

    fun flush() = writeNow()

    private fun save(persist: Boolean, flush: Boolean) {
        when {
            !persist -> Unit
            flush -> writeNow()
            else -> dirty.trySend(Unit)
        }
    }

    private fun read(): List<T> = try {
        val json = prefs.getString(KEY, null)
        if (json == null) emptyList() else gson.fromJson(json, arrayType)?.toList().orEmpty()
    } catch (e: Exception) {
        emptyList()
    }

    private fun writeNow() {
        prefs.edit().putString(KEY, gson.toJson(state.value)).apply()
    }

    private companion object {
        const val KEY = "records"
    }
}
