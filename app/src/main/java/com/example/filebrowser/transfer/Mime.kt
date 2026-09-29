package com.example.filebrowser.transfer

import android.webkit.MimeTypeMap

/**
 * MIME a partir da extensão. "application/octet-stream" quando desconhecido: com esse tipo
 * os DocumentsProviders mantêm o nome tal como está, sem acrescentar extensões.
 */
fun mimeTypeFor(name: String): String {
    val extension = name.substringAfterLast('.', "").lowercase()
    if (extension.isEmpty()) return "application/octet-stream"
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
}
