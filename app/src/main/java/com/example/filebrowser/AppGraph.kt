package com.example.filebrowser

import android.content.Context
import com.example.filebrowser.data.AuthInterceptor
import com.example.filebrowser.data.FileBrowserRepository
import com.example.filebrowser.data.SessionStore
import com.example.filebrowser.download.DownloadClient
import com.example.filebrowser.download.DownloadManager
import com.example.filebrowser.download.DownloadStore
import com.example.filebrowser.upload.TusClient
import com.example.filebrowser.upload.UploadManager
import com.example.filebrowser.upload.UploadStore
import coil.ImageLoader
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Dependências partilhadas entre a UI e os workers (o WorkManager instancia
 * o worker sozinho, por isso precisa de um sítio onde as ir buscar).
 */
class AppGraph private constructor(context: Context) {
    val session = SessionStore(context)

    /** Um único OkHttpClient para toda a app (reutiliza ligações e threads). */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // Depois de receber um chunk o servidor faz fsync, que pode demorar em discos lentos.
        .readTimeout(2, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .addInterceptor(AuthInterceptor(session))
        .build()

    val repository = FileBrowserRepository(session, client)

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
