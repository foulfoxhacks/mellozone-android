package com.foulfoxhacks.mellozone.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foulfoxhacks.mellozone.R
import com.foulfoxhacks.mellozone.core.FeedItem
import com.foulfoxhacks.mellozone.ui.AppViewModel
import com.foulfoxhacks.mellozone.ui.components.Banner
import com.foulfoxhacks.mellozone.ui.components.Centered
import com.foulfoxhacks.mellozone.ui.components.LoadingState
import com.foulfoxhacks.mellozone.ui.components.MessageState
import com.foulfoxhacks.mellozone.ui.components.RemoteImage
import com.foulfoxhacks.mellozone.ui.components.SectionLabel
import com.foulfoxhacks.mellozone.ui.components.formatDate

private fun kicker(item: FeedItem): String =
    listOf(formatDate(item.datePublished), item.tags.firstOrNull().orEmpty())
        .filter { it.isNotBlank() }
        .joinToString("  ·  ")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.ensureJournal() }
    val ui = vm.journal
    when {
        ui.loaded -> {
            var pulled by remember { mutableStateOf(false) }
            LaunchedEffect(ui.refreshing) { if (!ui.refreshing) pulled = false }
            PullToRefreshBox(
                isRefreshing = pulled && ui.refreshing,
                onRefresh = {
                    pulled = true
                    vm.refreshJournal()
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item(key = "header", contentType = "header") {
                        Centered {
                            Column {
                                SectionLabel("Mello Zone Journal")
                                Text(
                                    text = "Guides and stories built to stay useful.",
                                    style = MaterialTheme.typography.headlineMedium,
                                    modifier = Modifier.padding(top = 6.dp).semantics { heading() },
                                )
                            }
                        }
                    }
                    val warning = ui.warning
                    if (warning != null) {
                        item(key = "warning", contentType = "banner") {
                            Centered { Banner("Showing saved articles. $warning") }
                        }
                    }
                    itemsIndexed(
                        items = ui.items,
                        key = { _, item -> item.path },
                        contentType = { index, _ -> if (index == 0) "lead" else "row" },
                    ) { index, item ->
                        Centered {
                            if (index == 0) {
                                LeadArticle(item) { vm.openPath(item.path) }
                            } else {
                                ArticleRow(item) { vm.openPath(item.path) }
                            }
                        }
                    }
                }
            }
        }

        ui.error != null -> MessageState(
            title = "The journal didn't load",
            message = ui.error,
            icon = R.drawable.ic_cloud_off,
            primaryLabel = "Try again",
            onPrimary = { vm.refreshJournal() },
        )

        else -> LoadingState("Loading the journal")
    }
}

@Composable
private fun LeadArticle(item: FeedItem, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clip(shape).clickable(role = Role.Button, onClick = onClick),
    ) {
        Column {
            val image = item.image
            if (image != null) {
                RemoteImage(image, null, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            }
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val label = kicker(item)
                if (label.isNotBlank()) SectionLabel(label)
                Text(item.title, style = MaterialTheme.typography.headlineSmall)
                if (item.summary.isNotBlank()) {
                    Text(
                        text = item.summary,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ArticleRow(item: FeedItem, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val image = item.image
        if (image != null) {
            RemoteImage(
                url = image,
                contentDescription = null,
                modifier = Modifier.width(112.dp).aspectRatio(4f / 3f).clip(RoundedCornerShape(12.dp)),
                maxWidthFraction = 0.4f,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            val label = kicker(item)
            if (label.isNotBlank()) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
