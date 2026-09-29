package com.example.filebrowser.upload

import android.content.Context
import com.example.filebrowser.data.RemotePath
import com.example.filebrowser.transfer.Transfer
import com.example.filebrowser.transfer.TransferStatus
import com.example.filebrowser.transfer.TransferStore

data class UploadRecord(
    override val id: String = "",
    val uri: String = "",
    override val name: String = "",
    val remoteDir: String = "/",
    override val size: Long = 0,
    val sent: Long = 0,
    override val status: TransferStatus = TransferStatus.QUEUED,
    val created: Boolean = false,
    val override: Boolean = false,
    override val conflict: Boolean = false,
    override val error: String? = null,
    override val addedAt: Long = 0,
    /** Falhas de rede seguidas; ao fim de algumas o upload passa a FAILED. */
    val attempts: Int = 0,
    /** Uploads de uma mesma pasta partilham o batchId. */
    override val batchId: String? = null,
    override val batchName: String? = null,
    /** Árvore SAF de onde veio o ficheiro; a permissão persistente é da árvore, não do ficheiro. */
    val treeUri: String? = null,
) : Transfer<UploadRecord> {
    val remotePath: String get() = RemotePath.join(remoteDir, name)
    override val transferred: Long get() = sent
    override val destination: String get() = remoteDir
    override val grantUri: String get() = treeUri ?: uri
    override fun withStatus(status: TransferStatus) = copy(status = status)
}

/** O nome das SharedPreferences ("uploads") mantém-se para não perder uploads de versões anteriores. */
class UploadStore(context: Context) :
    TransferStore<UploadRecord>(context, "uploads", Array<UploadRecord>::class.java)
