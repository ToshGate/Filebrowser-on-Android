package com.toshgate.filebrowser.download

import android.content.Context
import com.toshgate.filebrowser.transfer.Transfer
import com.toshgate.filebrowser.transfer.TransferStatus
import com.toshgate.filebrowser.transfer.TransferStore

enum class DownloadKind {
    /** Um ficheiro, com retoma por HTTP Range. */
    FILE,
    /** Uma pasta como ZIP gerado pelo servidor: tamanho desconhecido e sem retoma. */
    ARCHIVE,
}

data class DownloadRecord(
    override val id: String = "",
    val remotePath: String = "",
    override val name: String = "",
    override val size: Long = -1,
    val received: Long = 0,
    override val status: TransferStatus = TransferStatus.QUEUED,
    val kind: DownloadKind = DownloadKind.FILE,
    /** Documento local onde escrever. Nos downloads de pasta é criado só quando o ficheiro começa. */
    val localUri: String? = null,
    /** Pasta local (documento SAF) onde criar o ficheiro, nos downloads de pasta. */
    val localParentUri: String? = null,
    /** Árvore SAF escolhida pelo utilizador, nos downloads de pasta. */
    val treeUri: String? = null,
    /** Last-Modified da primeira resposta, usado em If-Range para não misturar versões. */
    val lastModified: String? = null,
    override val error: String? = null,
    val attempts: Int = 0,
    override val addedAt: Long = 0,
    override val batchId: String? = null,
    override val batchName: String? = null,
) : Transfer<DownloadRecord> {
    override val transferred: Long get() = received
    override val conflict: Boolean get() = false
    override val destination: String? get() = null
    override val grantUri: String get() = treeUri ?: localUri.orEmpty()
    override fun withStatus(status: TransferStatus) = copy(status = status)
}

class DownloadStore(context: Context) :
    TransferStore<DownloadRecord>(context, "downloads", Array<DownloadRecord>::class.java)
