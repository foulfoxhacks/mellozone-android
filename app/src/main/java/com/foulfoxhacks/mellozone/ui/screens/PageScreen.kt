package com.foulfoxhacks.mellozone.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.foulfoxhacks.mellozone.core.PageResult
import com.foulfoxhacks.mellozone.ui.AppViewModel
import com.foulfoxhacks.mellozone.ui.PageUi
import com.foulfoxhacks.mellozone.ui.blocks.BlockView
import com.foulfoxhacks.mellozone.ui.blocks.pageSpacing
import com.foulfoxhacks.mellozone.ui.components.Banner
import com.foulfoxhacks.mellozone.ui.components.Centered
import com.foulfoxhacks.mellozone.ui.components.LoadingState
import com.foulfoxhacks.mellozone.ui.components.MessageState
import com.foulfoxhacks.mellozone.ui.components.relativeTime
import com.foulfoxhacks.mellozone.ui.openExternal
import com.foulfoxhacks.mellozone.ui.theme.Brand

/** Any mellozone.site page, drawn natively from engine blocks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageScreen(vm: AppViewModel, path: String, onLink: (String) -> Unit) {
    LaunchedEffect(path) { vm.ensurePage(path) }
    val ui = vm.pages[path] ?: PageUi(loading = true)
    val result = ui.result
    val context = LocalContext.current
    val error = ui.error

    when {
        result != null -> {
            // The pull indicator is for the reader's own refresh; quiet
            // background revalidation shows a thin bar instead.
            var pulled by remember { mutableStateOf(false) }
            LaunchedEffect(ui.refreshing) { if (!ui.refreshing) pulled = false }
            PullToRefreshBox(
                isRefreshing = pulled && ui.refreshing,
                onRefresh = {
                    pulled = true
                    vm.refreshPage(path)
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                PageBody(result, onLink, showSource = vm.settings.devSourceBadge)
                if (ui.refreshing && !pulled) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                        color = Brand.Pink,
                        trackColor = MaterialTheme.colorScheme.surface,
                    )
                }
            }
        }

        error != null -> {
            val offline = error.isConnectivity
            MessageState(
                title = when {
                    offline -> "You're offline"
                    error.code == "not_found" -> "Page not found"
                    else -> "This page didn't load"
                },
                message = if (offline) {
                    "This page isn't saved on this device yet. Connect to the internet and try again."
                } else {
                    error.message ?: "Please try again."
                },
                primaryLabel = "Try again",
                onPrimary = { vm.refreshPage(path) },
                secondaryLabel = "Open in browser",
                onSecondary = { openExternal(context, "https://mellozone.site$path") },
            )
        }

        else -> LoadingState()
    }
}

@Composable
private fun PageBody(result: PageResult, onLink: (String) -> Unit, showSource: Boolean) {
    val blocks = result.page.blocks
    val listState = rememberLazyListState()
    SelectionContainer {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 40.dp),
        ) {
            val warning = result.warning
            if (warning != null) {
                item(key = "offline", contentType = "banner") {
                    Centered(Modifier.padding(bottom = 16.dp)) {
                        Banner("Showing the copy saved on this device. $warning")
                    }
                }
            }
            itemsIndexed(blocks, contentType = { _, block -> block::class }) { index, block ->
                Centered(Modifier.padding(top = pageSpacing(blocks, index))) {
                    BlockView(block, onLink)
                }
            }
            item(key = "footer", contentType = "footer") {
                Centered(Modifier.padding(top = 32.dp)) { PageFooter(result, onLink, showSource) }
            }
        }
    }
}

@Composable
private fun PageFooter(result: PageResult, onLink: (String) -> Unit, showSource: Boolean) {
    val context = LocalContext.current
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.padding(top = 14.dp)) {
            Text(
                text = "Saved on this device · checked ${relativeTime(result.fetchedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showSource) {
            Text(
                text = "source=${result.source} stale=${result.stale} blocks=${result.page.blocks.size} " +
                    "kind=${result.page.kind} path=${result.page.path}",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.Violet,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        TextButton(
            onClick = { openExternal(context, result.page.url) },
            contentPadding = PaddingValues(0.dp),
        ) { Text("View this page on mellozone.site") }
    }
}
