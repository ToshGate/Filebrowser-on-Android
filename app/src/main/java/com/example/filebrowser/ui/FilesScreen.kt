package com.example.filebrowser.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.filebrowser.data.Resource
import com.example.filebrowser.download.DownloadRecord
import com.example.filebrowser.transfer.mimeTypeFor
import com.example.filebrowser.ui.components.Breadcrumbs
import com.example.filebrowser.ui.components.FileTile
import com.example.filebrowser.ui.components.ItemActionsSheet
import com.example.filebrowser.ui.components.MessageState
import com.example.filebrowser.ui.components.NewItemSheet
import com.example.filebrowser.ui.components.TransfersBanner
import kotlinx.coroutines.flow.collectLatest

/** Só uma coisa aberta de cada vez por cima da lista. */
private sealed interface Overlay {
    data object NewItem : Overlay
    data class Actions(val item: Resource) : Overlay
    data class Transfers(val tab: Int) : Overlay
    data object NewFolder : Overlay
    data class Rename(val item: Resource) : Overlay
    data class Delete(val item: Resource) : Overlay
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val uploads by vm.uploads.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val activeTransfers = uploads.count { it.isActive } + downloads.count { it.isActive }
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var accountMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // collectLatest: uma mensagem nova substitui a anterior em vez de esperar que desapareça.
        vm.messages.collectLatest { snackbar.showSnackbar(it) }
    }
    // Ao mudar de pasta, volta ao topo da lista.
    LaunchedEffect(vm.path) { listState.scrollToItem(0) }

    BackHandler(enabled = vm.path != "/") { vm.back() }

    // --- Selectores do Storage Access Framework ---
    // OpenDocument / OpenDocumentTree / CreateDocument (e não GetContent) para podermos pedir
    // uma permissão persistente e os workers conseguirem retomar depois de a app fechar.

    val uploadFilesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.upload(uris)
    }
    val uploadFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.uploadFolder(uri)
    }
    val saveAsPicker = rememberLauncherForActivityResult(CreateDocumentFor()) { uri ->
        val item = vm.pendingDownload
        vm.pendingDownload = null
        if (uri != null && item != null) {
            if (item.isDir) vm.downloadArchive(item, uri) else vm.downloadFile(item, uri)
        }
    }
    val downloadFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val item = vm.pendingDownload
        vm.pendingDownload = null
        if (uri != null && item != null) vm.downloadFolder(item, uri)
    }

    // Android 13+: sem esta permissão a notificação de progresso não aparece (a transferência
    // funciona na mesma). Pedimo-la antes da primeira transferência e depois seguimos em frente.
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        afterPermission?.invoke()
        afterPermission = null
    }
    val withNotifications: (() -> Unit) -> Unit = { action ->
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            afterPermission = action
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }
    val download: (Resource) -> Unit = { item ->
        withNotifications {
            vm.pendingDownload = item
            if (item.isDir) downloadFolderPicker.launch(null) else saveAsPicker.launch(item.name)
        }
    }
    val downloadZip: (Resource) -> Unit = { item ->
        withNotifications {
            vm.pendingDownload = item
            saveAsPicker.launch("${item.name}.zip")
        }
    }
    val openTransfers = {
        val tab = if (uploads.isEmpty() || (downloads.any { it.isActive } && uploads.none { it.isActive })) {
            TAB_DOWNLOADS
        } else TAB_UPLOADS
        overlay = Overlay.Transfers(tab)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            // Cabeçalho fixo: barra com fundo tonal + faixa dos breadcrumbs. As cores são as
            // mesmas com e sem scroll, para o cabeçalho ter sempre o mesmo aspecto.
            Column {
                TopAppBar(
                    title = {
                        Text(
                            if (vm.path == "/") "Ficheiros" else vm.path.substringAfterLast('/'),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        if (vm.path != "/") {
                            IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
                        }
                    },
                    actions = {
                        if (uploads.isNotEmpty() || downloads.isNotEmpty()) {
                            IconButton(onClick = openTransfers) {
                                BadgedBox(badge = { if (activeTransfers > 0) Badge { Text("$activeTransfers") } }) {
                                    Icon(Icons.Default.SwapVert, "Transferências")
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { sortMenu = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, "Ordenar")
                            }
                            SortMenu(
                                expanded = sortMenu,
                                current = vm.sortBy,
                                ascending = vm.sortAscending,
                                onDismiss = { sortMenu = false },
                                onSelect = { vm.sort(it); sortMenu = false },
                            )
                        }
                        Box {
                            IconButton(onClick = { accountMenu = true }) { Icon(Icons.Default.MoreVert, "Mais opções") }
                            DropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Terminar sessão") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, null) },
                                    onClick = { accountMenu = false; vm.logout() },
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                )
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Breadcrumbs(
                        vm.path,
                        onNavigate = { vm.load(it) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("Novo") },
                icon = { Icon(Icons.Default.Add, null) },
                expanded = fabExpanded,
                onClick = { overlay = Overlay.NewItem },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = vm.loading && vm.items.isNotEmpty(),
            onRefresh = { vm.load() },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
            ) {
                if (activeTransfers > 0) {
                    item(key = "transfers") {
                        TransfersBanner(uploads, downloads, onClick = openTransfers)
                    }
                }
                val error = vm.error
                when {
                    vm.loading && vm.items.isEmpty() -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 64.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    error != null -> item(key = "error") {
                        MessageState(
                            icon = Icons.Default.CloudOff,
                            title = "Não foi possível abrir esta pasta",
                            body = error,
                            container = MaterialTheme.colorScheme.errorContainer,
                            content = MaterialTheme.colorScheme.onErrorContainer,
                            actionLabel = "Tentar de novo",
                            onAction = { vm.load() },
                        )
                    }
                    vm.items.isEmpty() -> item(key = "empty") {
                        MessageState(
                            icon = Icons.Default.FolderOpen,
                            title = "Esta pasta está vazia",
                            body = "Carrega ficheiros do telemóvel ou cria uma pasta nova.",
                            actionLabel = "Adicionar",
                            onAction = { overlay = Overlay.NewItem },
                        )
                    }
                    else -> items(vm.items, key = { it.path }) { item ->
                        FileRow(
                            item = item,
                            thumbnailUrl = vm.thumbnailUrl(item),
                            vm = vm,
                            modifier = Modifier.animateItem(),
                            onClick = { if (item.isDir) vm.open(item) else overlay = Overlay.Actions(item) },
                            onMore = { overlay = Overlay.Actions(item) },
                        )
                    }
                }
            }
        }
    }

    // --- Sheets e diálogos ---
    when (val o = overlay) {
        null -> Unit
        Overlay.NewItem -> NewItemSheet(
            onDismiss = { overlay = null },
            onUploadFiles = {
                overlay = null
                withNotifications { uploadFilesPicker.launch(arrayOf("*/*")) }
            },
            onUploadFolder = {
                overlay = null
                withNotifications { uploadFolderPicker.launch(null) }
            },
            onNewFolder = { overlay = Overlay.NewFolder },
        )
        is Overlay.Actions -> ItemActionsSheet(
            item = o.item,
            thumbnailUrl = vm.thumbnailUrl(o.item),
            imageLoader = vm.imageLoader,
            onDismiss = { overlay = null },
            onOpen = { overlay = null; vm.open(o.item) },
            onDownload = { overlay = null; download(o.item) },
            onDownloadZip = { overlay = null; downloadZip(o.item) },
            onRename = { overlay = Overlay.Rename(o.item) },
            onDelete = { overlay = Overlay.Delete(o.item) },
        )
        is Overlay.Transfers -> TransfersSheet(
            uploads = uploads,
            downloads = downloads,
            initialTab = o.tab,
            uploadActions = remember(vm) {
                TransferActions(
                    pause = vm::pauseUpload,
                    resume = vm::resumeUpload,
                    cancel = vm::cancelUpload,
                    pauseBatch = vm::pauseBatch,
                    resumeBatch = vm::resumeBatch,
                    cancelBatch = vm::cancelBatch,
                    clearFinished = vm::clearFinishedUploads,
                    override = vm::overrideUpload,
                    overrideBatch = vm::overrideBatch,
                )
            },
            downloadActions = remember(vm) {
                TransferActions(
                    pause = vm::pauseDownload,
                    resume = vm::resumeDownload,
                    cancel = vm::cancelDownload,
                    pauseBatch = vm::pauseDownloadBatch,
                    resumeBatch = vm::resumeDownloadBatch,
                    cancelBatch = vm::cancelDownloadBatch,
                    clearFinished = vm::clearFinishedDownloads,
                    open = { id ->
                        val record = vm.downloads.value.firstOrNull { it.id == id }
                        if (record != null && !openLocalFile(context, record)) {
                            vm.showMessage("Nenhuma app instalada abre \"${record.name}\".")
                        }
                    },
                )
            },
            onDismiss = { overlay = null },
        )
        Overlay.NewFolder -> NameDialog(
            title = "Nova pasta",
            initial = "",
            confirmLabel = "Criar",
            onDismiss = { overlay = null },
            onConfirm = { vm.createFolder(it); overlay = null },
        )
        is Overlay.Rename -> NameDialog(
            title = "Mudar o nome",
            initial = o.item.name,
            confirmLabel = "Mudar",
            onDismiss = { overlay = null },
            onConfirm = { vm.rename(o.item, it); overlay = null },
        )
        is Overlay.Delete -> ConfirmDeleteDialog(
            name = o.item.name,
            isDir = o.item.isDir,
            onDismiss = { overlay = null },
            onConfirm = { vm.delete(o.item); overlay = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    item: Resource,
    thumbnailUrl: String?,
    vm: AppViewModel,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val modified = formatModified(item.modified)
    val details = when {
        item.isDir -> modified
        modified != null -> "${formatSize(item.size)}, $modified"
        else -> formatSize(item.size)
    }
    ListItem(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onMore),
        leadingContent = { FileTile(item, thumbnailUrl, vm.imageLoader) },
        headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (details != null) {
            { Text(details, maxLines = 1) }
        } else null,
        trailingContent = {
            IconButton(onClick = onMore) { Icon(Icons.Default.MoreVert, "Opções de ${item.name}") }
        },
    )
}

@Composable
private fun SortMenu(
    expanded: Boolean,
    current: SortBy,
    ascending: Boolean,
    onDismiss: () -> Unit,
    onSelect: (SortBy) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        SortBy.entries.forEach { option ->
            val selected = option == current
            DropdownMenuItem(
                text = { Text(option.label) },
                leadingIcon = {
                    if (selected) Icon(Icons.Default.Check, null) else Spacer(Modifier.size(24.dp))
                },
                trailingIcon = if (selected) {
                    {
                        Icon(
                            if (ascending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                            if (ascending) "Crescente" else "Decrescente",
                        )
                    }
                } else null,
                onClick = { onSelect(option) },
            )
        }
    }
}

/** Abre um ficheiro descarregado noutra app. Devolve false se nenhuma o souber abrir. */
private fun openLocalFile(context: Context, record: DownloadRecord): Boolean {
    val uri = record.localUri ?: return false
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(uri), mimeTypeFor(record.name))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
