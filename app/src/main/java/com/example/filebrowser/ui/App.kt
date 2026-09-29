package com.example.filebrowser.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun App(vm: AppViewModel = viewModel()) {
    // Transição suave entre o login e a lista (e de volta, ao terminar sessão).
    AnimatedContent(
        targetState = vm.loggedIn,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "session",
    ) { loggedIn ->
        if (loggedIn) FilesScreen(vm) else LoginScreen(vm)
    }
}
