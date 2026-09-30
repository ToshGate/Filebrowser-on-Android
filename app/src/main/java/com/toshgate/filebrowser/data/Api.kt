package com.toshgate.filebrowser.data

import com.google.gson.annotations.SerializedName
import retrofit2.http.*

data class LoginRequest(val username: String, val password: String, val recaptcha: String = "")

/**
 * Nota: todos os parâmetros têm valor por omissão para o Kotlin gerar um construtor
 * sem argumentos — assim o Gson respeita os defaults quando um campo falta no JSON.
 */
data class Resource(
    val name: String = "",
    val path: String = "",
    val size: Long = 0,
    val extension: String? = null,
    val modified: String? = null,
    // os.FileMode do Go é uint32 e as pastas têm o bit 31 ligado: não cabe num Int.
    val mode: Long? = null,
    @SerializedName("isDir") val isDir: Boolean = false,
    @SerializedName("isSymlink") val isSymlink: Boolean = false,
    val type: String? = null,
)

data class Listing(
    val path: String = "/",
    val name: String = "",
    // Uma pasta vazia pode vir como "items": null.
    val items: List<Resource>? = null,
    val numDirs: Int = 0,
    val numFiles: Int = 0,
)

interface FileBrowserApi {
    @POST("api/login")
    suspend fun login(@Body body: LoginRequest): String

    @POST("api/renew")
    suspend fun renew(): String

    @GET("api/resources/{path}")
    suspend fun list(@Path("path", encoded = true) path: String): Listing

    @GET("api/resources/{path}")
    suspend fun stat(@Path("path", encoded = true) path: String): Resource

    @DELETE("api/resources/{path}")
    suspend fun delete(@Path("path", encoded = true) path: String)

    /** Com "/" final no path cria uma pasta; sem ele cria um ficheiro com o corpo enviado. */
    @POST("api/resources/{path}")
    suspend fun create(
        @Path("path", encoded = true) path: String,
        @Query("override") override: Boolean = false,
    )

    /**
     * Mover/renomear/copiar. O servidor faz `Query().Get()` (1.º decode) seguido de
     * `url.QueryUnescape()` (2.º decode), por isso [destination] tem de ir já codificado
     * uma vez — o Retrofit trata da segunda codificação.
     */
    @PATCH("api/resources/{path}")
    suspend fun patch(
        @Path("path", encoded = true) path: String,
        @Query("action") action: String,
        @Query("destination") destination: String,
        @Query("override") override: Boolean = false,
        @Query("rename") rename: Boolean = false,
    )
}
