package com.example.filebrowser.data

import java.net.URLEncoder

/**
 * Utilitários para caminhos no servidor. Internamente os caminhos são sempre
 * normalizados: começam por "/" e não terminam em "/" (excepto a raiz).
 */
object RemotePath {

    fun segments(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }

    fun normalize(path: String): String = "/" + segments(path).joinToString("/")

    fun join(dir: String, name: String): String = normalize("$dir/$name")

    fun parent(path: String): String = normalize(normalize(path).substringBeforeLast('/', ""))

    fun name(path: String): String = normalize(path).substringAfterLast('/')

    /**
     * Codifica o caminho para usar num `@Path(encoded = true)` do Retrofit.
     * Cada segmento é codificado em separado para que nomes com espaços, `#`, `?`, `%`
     * ou `+` não partam o URL. Pastas levam "/" final (o File Browser usa isso para
     * distinguir "criar pasta" de "criar ficheiro" no POST).
     */
    fun encode(path: String, trailingSlash: Boolean = false): String {
        val encoded = segments(path).joinToString("/") { encodeSegment(it) }
        return if (trailingSlash && encoded.isNotEmpty()) "$encoded/" else encoded
    }

    private fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    /** Devolve uma mensagem de erro, ou null se o nome for válido. */
    fun nameError(raw: String): String? {
        val name = raw.trim()
        return when {
            name.isEmpty() -> "O nome não pode estar vazio."
            name == "." || name == ".." -> "Nome inválido."
            name.any { it == '/' || it == '\\' || it == '\u0000' } -> "O nome não pode conter / ou \\."
            name.length > 255 -> "Nome demasiado longo."
            else -> null
        }
    }

    fun validateName(raw: String): String {
        nameError(raw)?.let { throw IllegalArgumentException(it) }
        return raw.trim()
    }
}
