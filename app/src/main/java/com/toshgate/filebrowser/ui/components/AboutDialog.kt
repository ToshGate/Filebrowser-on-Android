package com.toshgate.filebrowser.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.toshgate.filebrowser.BuildConfig

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            IconTile(
                Icons.Default.FolderOpen,
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.onPrimaryContainer,
                size = 56.dp,
            )
        },
        title = { Text("File Browser para Android") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Versão ${BuildConfig.VERSION_NAME}, licença MIT", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Cliente para servidores File Browser. Esta app não é oficial nem está " +
                        "associada ao projecto File Browser.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "As credenciais ficam guardadas só neste telemóvel, cifradas, e são enviadas " +
                        "apenas para o servidor que indicares.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "O robot Android é reproduzido ou modificado a partir de trabalho criado e " +
                        "partilhado pela Google, usado de acordo com a licença Creative Commons 3.0 " +
                        "Attribution.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Usa bibliotecas de código aberto sob a licença Apache 2.0: AndroidX e Jetpack " +
                        "Compose, Kotlin e kotlinx.coroutines, Retrofit, OkHttp, Gson e Coil.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
        dismissButton = {
            TextButton(onClick = { uriHandler.openUri("https://github.com/filebrowser/filebrowser") }) {
                Text("Projecto File Browser")
            }
        },
    )
}
