package com.foulfoxhacks.mellozone.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foulfoxhacks.mellozone.R
import com.foulfoxhacks.mellozone.ui.AppViewModel
import com.foulfoxhacks.mellozone.ui.Screen
import com.foulfoxhacks.mellozone.ui.components.Centered
import com.foulfoxhacks.mellozone.ui.components.MessageState

@Composable
fun SavedScreen(vm: AppViewModel) {
    val items = vm.saved
    if (items.isEmpty()) {
        MessageState(
            title = "Nothing saved yet",
            message = "Tap the bookmark at the top of any page to keep it here. Saved pages can be read offline.",
            icon = R.drawable.ic_bookmark_border,
            primaryLabel = "Browse the journal",
            onPrimary = { vm.selectTab(Screen.Journal) },
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 32.dp),
    ) {
        items(items, key = { it.path }) { item ->
            Centered {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { vm.openPath(item.path) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium)
                        if (item.description.isNotBlank()) {
                            Text(
                                text = item.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    IconButton(onClick = { vm.toggleSaved(item.path, item.title) }) {
                        Icon(
                            painterResource(R.drawable.ic_bookmark),
                            contentDescription = "Remove ${item.title} from Saved",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
