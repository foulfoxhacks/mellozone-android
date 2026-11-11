package com.foulfoxhacks.mellozone.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foulfoxhacks.mellozone.R
import com.foulfoxhacks.mellozone.core.Core
import com.foulfoxhacks.mellozone.core.SearchResult
import com.foulfoxhacks.mellozone.ui.AppViewModel
import com.foulfoxhacks.mellozone.ui.components.Banner
import com.foulfoxhacks.mellozone.ui.components.Centered
import com.foulfoxhacks.mellozone.ui.components.LoadingState
import com.foulfoxhacks.mellozone.ui.components.MessageState
import com.foulfoxhacks.mellozone.ui.components.SectionLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Search plus the full page directory. */
@Composable
fun ExploreScreen(vm: AppViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<SearchResult?>(null) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) { vm.ensureDirectory() }
    LaunchedEffect(vm.focusSearch) {
        if (vm.focusSearch) {
            vm.focusSearch = false
            runCatching { focus.requestFocus() }
        }
    }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            result = null
            return@LaunchedEffect
        }
        delay(90) // debounce typing; the search itself takes about a millisecond
        try {
            result = Core.search(q)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            result = SearchResult(query = q)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Centered(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                placeholder = { Text("Search Mello Zone") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            )
        }
        if (query.isNotBlank()) SearchResults(result, vm) else Directory(vm)
    }
}

@Composable
private fun SearchResults(result: SearchResult?, vm: AppViewModel) {
    if (result == null) return
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
    ) {
        item(key = "count", contentType = "label") {
            Centered {
                Text(
                    text = when (result.hits.size) {
                        0 -> "No matches for \"${result.query}\""
                        1 -> "1 result"
                        else -> "${result.hits.size} results"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        items(result.hits, key = { it.path }, contentType = { "hit" }) { hit ->
            Centered {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { vm.openPath(hit.path) }
                        .padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SectionLabel(if (hit.kind == "article") "Journal" else "Page")
                    Text(hit.title, style = MaterialTheme.typography.titleMedium)
                    if (hit.snippet.isNotBlank()) {
                        Text(
                            text = hit.snippet,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        item(key = "hint", contentType = "hint") {
            Centered(Modifier.padding(top = 16.dp)) {
                Column {
                    Text(
                        text = "Search works offline. It covers every page title, plus the full text " +
                            "of pages saved on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!vm.sync.running) {
                        TextButton(onClick = { vm.startSync() }, contentPadding = PaddingValues(0.dp)) {
                            Text("Download every page for full-text search")
                        }
                    } else {
                        Text(
                            text = "Downloading pages… ${vm.sync.done} of ${vm.sync.total}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Directory(vm: AppViewModel) {
    val ui = vm.directory
    when {
        ui.loaded -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
        ) {
            val warning = ui.warning
            if (warning != null) {
                item(key = "warning", contentType = "banner") {
                    Centered(Modifier.padding(vertical = 8.dp)) { Banner("Showing the saved page list. $warning") }
                }
            }
            ui.items.forEachIndexed { sectionIndex, section ->
                item(key = "section-$sectionIndex", contentType = "section") {
                    Centered(Modifier.padding(top = 22.dp, bottom = 6.dp)) {
                        Text(section.title, style = MaterialTheme.typography.titleLarge)
                    }
                }
                items(
                    items = section.entries,
                    key = { "$sectionIndex:${it.path}" },
                    contentType = { "entry" },
                ) { entry ->
                    Centered {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) { vm.openPath(entry.path) }
                                .padding(vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text = entry.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (entry.description.isNotBlank()) {
                                Text(
                                    text = entry.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        ui.error != null -> MessageState(
            title = "The page list didn't load",
            message = ui.error,
            icon = R.drawable.ic_cloud_off,
            primaryLabel = "Try again",
            onPrimary = { vm.refreshDirectory() },
        )

        else -> LoadingState("Loading pages")
    }
}
