package com.toshgate.filebrowser

import android.content.Context
import com.toshgate.filebrowser.data.AuthInterceptor
import com.toshgate.filebrowser.data.CredentialStore
import com.toshgate.filebrowser.data.ReLoginAuthenticator
import com.toshgate.filebrowser.data.FileBrowserRepository
import com.toshgate.filebrowser.data.SessionStore
import com.toshgate.filebrowser.download.DownloadClient
import com.toshgate.filebrowser.download.DownloadManager
import com.toshgate.filebrowser.download.DownloadStore
import com.toshgate.filebrowser.upload.TusClient
import com.toshgate.filebrowser.upload.UploadManager
import com.toshgate.filebrowser.upload.UploadStore
import coil.ImageLoader
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Dependências partilhadas entre a UI e os workers (o WorkManager instancia
 * o worker sozinho, por isso precisa de um sítio onde as ir buscar).
 */
class AppGraph private constructor(context: Context) {
    val session = SessionStore(context)
    val credentials = CredentialStore(context)

    /** Um único OkHttpClient para toda a app (reutiliza ligações e threads). */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // Depois de receber um chunk o servidor faz fsync, que pode demorar em discos lentos.
        .readTimeout(2, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .addInterceptor(AuthInterceptor(session))
        // Token expirado (401): volta a entrar com as credenciais guardadas e repete o pedido.
        .authenticator(ReLoginAuthenticator(session, credentials))
        .build()

    val repository = FileBrowserRepository(session, credentials, client)

    /** Coil com o mesmo OkHttpClient: as miniaturas levam o X-Auth do interceptor. */
    val imageLoader: ImageLoader = ImageLoader.Builder(context)
        .okHttpClient(client)
        .crossfade(true)
        .build()
    val uploadStore = UploadStore(context)
    val tus = TusClient(session, client, context.contentResolver, repository)
    val uploads = UploadManager(context, uploadStore, tus, repository)

    val downloadStore = DownloadStore(context)
    val downloadClient = DownloadClient(session, client, context.contentResolver, repository)
    val downloads = DownloadManager(context, downloadStore, repository)

    companion object {
        @Volatile private var instance: AppGraph? = null

        fun get(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context.applicationContext).also { instance = it }
            }
    }
}
