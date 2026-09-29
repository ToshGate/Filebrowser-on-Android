package com.example.filebrowser.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.filebrowser.data.RemotePath

/** Caminho actual como chips; tocar num segmento vai para essa pasta. */
@Composable
fun Breadcrumbs(path: String, onNavigate: (String) -> Unit, modifier: Modifier = Modifier) {
    val segments = RemotePath.segments(path)
    // (rótulo, caminho) desde a raiz até à pasta actual.
    val crumbs = listOf("Início" to "/") + segments.indices.map { i ->
        segments[i] to "/" + segments.subList(0, i + 1).joinToString("/")
    }
    val state = rememberLazyListState()
    LaunchedEffect(path) { state.animateScrollToItem(crumbs.lastIndex) }

    LazyRow(
        state = state,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(crumbs, key = { _, crumb -> crumb.second }) { index, (label, target) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (index > 0) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                val homeIcon: (@Composable () -> Unit)? = if (index == 0) {
                    { Icon(Icons.Default.Home, null, Modifier.size(AssistChipDefaults.IconSize)) }
                } else null
                if (index == crumbs.lastIndex) {
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text(label, maxLines = 1) },
                        leadingIcon = homeIcon,
                    )
                } else {
                    AssistChip(
                        onClick = { onNavigate(target) },
                        label = { Text(label, maxLines = 1) },
                        leadingIcon = homeIcon,
                    )
                }
            }
        }
    }
}
