package com.example.filebrowser.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.io.IOException
import java.net.URLEncoder

/** Erros que não vale a pena repetir (permissões, conflitos, sessão expirada…). */
open class FatalApiException(message: String) : IOException(message)
class SessionExpiredException : FatalApiException("A sessão expirou. Inicia sessão novamente.")
class ConflictException(message: String = "Já existe um item com esse nome.") : FatalApiException(message)
class NotFoundException : FatalApiException("O item já não existe no servidor.")

/** Converte um código HTTP numa excepção. 5xx é tratado como transitório (IOException simples). */
fun httpError(code: Int): IOException = when (code) {
    401 -> SessionExpiredException()
    403 -> FatalApiException("Sem permissão para esta operação.")
    404 -> NotFoundException()
    409 -> ConflictException()
    413 -> FatalApiException("O servidor recusou o ficheiro por ser demasiado grande.")
    in 500..599 -> IOException("Erro no servidor ($code).")
    else -> FatalApiException("Pedido rejeitado pelo servidor ($code).")
}

class SessionStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("filebrowser_session", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("baseUrl", "") ?: ""
        set(value) = prefs.edit().putString("baseUrl", value).apply()

    val token: String
        get() = prefs.getString("token", "") ?: ""

    val tokenSavedAt: Long
        get() = prefs.getLong("tokenSavedAt", 0L)

    fun saveToken(token: String) {
        prefs.edit()
            .putString("token", token)
            .putLong("tokenSavedAt", System.currentTimeMillis())
            .apply()
    }

    /** Ordenação da lista (nome enum) e sentido; guardados para a próxima sessão. */
    var sortBy: String
        get() = prefs.getString("sortBy", "NAME") ?: "NAME"
        set(value) = prefs.edit().putString("sortBy", value).apply()

    var sortAscending: Boolean
        get() = prefs.getBoolean("sortAscending", true)
        set(value) = prefs.edit().putBoolean("sortAscending", value).apply()

    /** Mantém o URL do servidor para pré-preencher o login. */
    fun clearToken() {
        prefs.edit().remove("token").remove("tokenSavedAt").apply()
    }
}

/** Só envia o token para o servidor configurado, nunca para outros hosts. */
class AuthInterceptor(private val session: SessionStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = session.token
        val base = session.baseUrl.toHttpUrlOrNull()
        val sameServer = base != null && request.url.host == base.host && request.url.port == base.port
        if (token.isBlank() || !sameServer) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("X-Auth", token).build())
    }
}

class FileBrowserRepository(
    private val session: SessionStore,
    private val client: OkHttpClient,
) {
    @Volatile private var cached: Pair<String, FileBrowserApi>? = null
    private val renewLock = Mutex()

    private fun api(): FileBrowserApi {
        val base = session.baseUrl.trimEnd('/') + "/"
        cached?.takeIf { it.first == base }?.let { return it.second }
        val api = Retrofit.Builder()
            .baseUrl(base)
            .client(client)
            // A ordem importa: scalars primeiro para String, Gson para o resto.
            .addConverterFactory(ScalarsConverterFactory.create())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(FileBrowserApi::class.java)
        cached = base to api
        return api
    }

    val serverUrl: String get() = session.baseUrl
    val isLoggedIn: Boolean get() = session.token.isNotBlank()

    suspend fun login(baseUrl: String, username: String, password: String) {
        val normalized = baseUrl.trim().trimEnd('/')
        if (normalized.toHttpUrlOrNull() == null) throw FatalApiException("URL do servidor inválido.")
        session.baseUrl = normalized
        val token = try {
            api().login(LoginRequest(username, password)).trim()
        } catch (e: HttpException) {
            throw if (e.code() == 403 || e.code() == 401) {
                FatalApiException("Utilizador ou palavra-passe incorrectos.")
            } else httpError(e.code())
        }
        if (!token.looksLikeJwt()) throw FatalApiException("O servidor não devolveu um token válido.")
        session.saveToken(token)
    }

    /**
     * Os tokens do File Browser expiram (2 h por omissão). Renovamos quando já têm
     * mais de [maxAgeMs], para que uploads longos não morram a meio.
     */
    suspend fun renewIfNeeded(maxAgeMs: Long = 30 * 60_000L) {
        renewLock.withLock {
            if (session.token.isBlank()) throw SessionExpiredException()
            if (System.currentTimeMillis() - session.tokenSavedAt < maxAgeMs) return
            val token = call { api().renew() }.trim()
            if (token.looksLikeJwt()) session.saveToken(token)
        }
    }

    suspend fun list(path: String): Listing =
        call { api().list(RemotePath.encode(path, trailingSlash = true)) }

    suspend fun statOrNull(path: String): Resource? = try {
        call { api().stat(RemotePath.encode(path)) }
    } catch (e: NotFoundException) {
        null
    }

    suspend fun delete(path: String) = call { api().delete(RemotePath.encode(path)) }

    suspend fun createFolder(parent: String, name: String) {
        val target = RemotePath.join(parent, RemotePath.validateName(name))
        // O servidor usa MkdirAll, que não falha se a pasta já existir: verificamos antes.
        if (statOrNull(target) != null) throw ConflictException()
        call { api().create(RemotePath.encode(target, trailingSlash = true)) }
    }

    /** Cria a pasta e as pastas-pai que faltem; não falha se já existir. */
    suspend fun mkdirs(path: String) {
        call { api().create(RemotePath.encode(path, trailingSlash = true)) }
    }

    suspend fun rename(path: String, newName: String) {
        val source = RemotePath.normalize(path)
        val destination = RemotePath.join(RemotePath.parent(source), RemotePath.validateName(newName))
        if (destination == source) return
        call {
            api().patch(
                path = RemotePath.encode(source),
                action = "rename",
                destination = URLEncoder.encode(destination, "UTF-8"),
            )
        }
    }

    /** Miniatura do servidor (`/api/preview/thumb/...`), só para imagens. */
    fun thumbnailUrl(item: Resource): String? {
        if (item.isDir || item.type != "image") return null
        val base = session.baseUrl.toHttpUrlOrNull() ?: return null
        return base.newBuilder().apply {
            addPathSegment("api")
            addPathSegment("preview")
            addPathSegment("thumb")
            RemotePath.segments(item.path).forEach { addPathSegment(it) }
            // Muda quando o ficheiro muda, para a cache do Coil não mostrar uma miniatura antiga.
            item.modified?.let { addQueryParameter("k", it) }
        }.build().toString()
    }

    fun logout() = session.clearToken()

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: HttpException) {
        throw httpError(e.code())
    }

    private fun String.looksLikeJwt() = count { it == '.' } == 2
}
