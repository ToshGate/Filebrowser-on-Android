# Regras R8 para o build release.
# As bibliotecas (Retrofit, OkHttp, Gson, WorkManager, Coil, Compose) trazem as suas próprias
# regras; aqui fica só o que é específico desta app, mais algumas redes de segurança.

# --- Stack traces legíveis -----------------------------------------------------------------
# Mantém os números de linha. Para traduzir um crash ofuscado usa-se o mapping.txt gerado em
# app/build/outputs/mapping/release/ (a Play Console faz isto sozinha se o enviares).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Modelos lidos/escritos pelo Gson por reflexão -----------------------------------------
# Se o R8 mudar os nomes dos campos, o JSON do servidor deixa de corresponder e as listas
# aparecem vazias; se remover o construtor sem argumentos, perdem-se os valores por omissão.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod
-keep class com.toshgate.filebrowser.data.LoginRequest { *; }
-keep class com.toshgate.filebrowser.data.Resource { *; }
-keep class com.toshgate.filebrowser.data.Listing { *; }
# Uploads e downloads persistidos em SharedPreferences como JSON: renomear os campos entre
# versões faria perder a fila guardada.
-keep class com.toshgate.filebrowser.upload.UploadRecord { *; }
-keep class com.toshgate.filebrowser.download.DownloadRecord { *; }
-keep enum com.toshgate.filebrowser.transfer.TransferStatus { *; }
-keep enum com.toshgate.filebrowser.download.DownloadKind { *; }

# --- Retrofit com funções suspend (R8 em modo "full", o padrão do AGP 8) --------------------
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# --- Workers criados pelo WorkManager por reflexão -----------------------------------------
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
