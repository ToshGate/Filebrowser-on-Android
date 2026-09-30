package com.toshgate.filebrowser.data

import com.google.gson.Gson
import okhttp3.Authenticator
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import java.util.concurrent.TimeUnit

/**
 * Quando o servidor responde 401 (token expirado ou inválido), volta a fazer login com as
 * credenciais guardadas e repete o pedido com o token novo. Como está no OkHttpClient,
 * cobre tudo: listagens, acções, uploads TUS, downloads e miniaturas.
 */
class ReLoginAuthenticator(
    private val session: SessionStore,
    private val credentials: CredentialStore,
) : Authenticator {

    /** Cliente à parte, sem interceptor nem authenticator, para o login não entrar em ciclo. */
    private val loginClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        val request = response.request
        val failedToken = request.header("X-Auth") ?: return null // pedido sem sessão (ex.: o próprio login)
        if (response.priorResponse != null) return null           // já tentámos uma vez: desistir
        val base = session.baseUrl.toHttpUrlOrNull() ?: return null
        if (request.url.host != base.host || request.url.port != base.port) return null

        val token = synchronized(lock) {
            val current = session.token
            // Vários pedidos podem falhar ao mesmo tempo: se outro já fez login, usar esse token.
            if (current.isNotBlank() && current != failedToken) current
            else loginBlocking(base.toString()) ?: return null
        }
        return request.newBuilder().header("X-Auth", token).build()
    }

    /** Login síncrono (o Authenticator corre numa thread do OkHttp). */
    private fun loginBlocking(baseUrl: String): String? {
        val saved = credentials.load() ?: return null
        val body = gson.toJson(LoginRequest(saved.username, saved.password))
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/login")
            .post(body)
            .build()
        return try {
            loginClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val token = response.body?.string()?.trim().orEmpty()
                        if (token.count { it == '.' } == 2) {
                            session.saveToken(token)
                            token
                        } else null
                    }
                    // A palavra-passe mudou no servidor: não insistir (evita bloqueios por tentativas).
                    response.code == 401 || response.code == 403 -> {
                        credentials.forgetPassword()
                        null
                    }
                    else -> null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
