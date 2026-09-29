package com.example.filebrowser

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.filebrowser.ui.App
import com.example.filebrowser.ui.theme.FileBrowserTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Com targetSdk 35 o Android 15 força edge-to-edge; assim fica igual em todas as versões.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            FileBrowserTheme {
                Surface(Modifier.fillMaxSize()) {
                    App()
                }
            }
        }
    }
}
