package com.foulfoxhacks.mellozone.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.foulfoxhacks.mellozone.BuildConfig
import com.foulfoxhacks.mellozone.R
import com.foulfoxhacks.mellozone.ui.AppViewModel
import com.foulfoxhacks.mellozone.ui.Screen
import com.foulfoxhacks.mellozone.ui.components.Centered
import com.foulfoxhacks.mellozone.ui.components.SectionLabel
import com.foulfoxhacks.mellozone.ui.components.formatBytes
import com.foulfoxhacks.mellozone.ui.theme.Brand
import kotlin.math.roundToInt

@Composable
fun InfoScreen(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.refreshStorage() }
    var confirmClear by remember { mutableStateOf(false) }
    var showNotices by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Centered {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // ---------------------------------------------------- about
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.size(84.dp),
                    )
                    Column {
                        Text(
                            "Mello Zone",
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    "Different lives. Different stories. One community.",
                    style = MaterialTheme.typography.titleMedium,
                    color = Brand.Pink,
                )
                Text(
                    "The official app for Mello Zone, an 18+ SFW furry community for Discord, VRChat " +
                        "and Telegram. Everything here comes from mellozone.site and is shown with " +
                        "native Android screens, so it stays fast and readable offline.",
                    style = MaterialTheme.typography.bodyLarge,
                )

                // -------------------------------------------------- reading
                Group("Reading") {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                        val percent = (vm.settings.textScale * 100).roundToInt()
                        Text("Text size", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "$percent% of your system text size",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = vm.settings.textScale,
                            onValueChange = { vm.setTextScale((it * 20).roundToInt() / 20f) },
                            valueRange = 0.85f..1.6f,
                            steps = 14,
                            modifier = Modifier.semantics { stateDescription = "$percent percent" },
                        )
                    }
                    RowDivider()
                    SwitchRow(
                        title = "Data saver",
                        subtitle = "Don't download images on mobile data. Tap an image to load it.",
                        checked = vm.settings.dataSaver,
                    ) { vm.setFlag("data_saver", it) }
                    RowDivider()
                    SwitchRow(
                        title = "Pure black background",
                        subtitle = "Saves battery on OLED screens.",
                        checked = vm.settings.pureBlack,
                    ) { vm.setFlag("pure_black", it) }
                }

                // -------------------------------------------------- offline
                Group("Offline reading") {
                    Column(
                        Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val sync = vm.sync
                        val storage = vm.storage
                        Text(
                            "${storage.pages} pages (${formatBytes(storage.pageBytes)}) and " +
                                "${storage.images} images (${formatBytes(storage.imageBytes)}) on this device.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (sync.running) {
                            LinearProgressIndicator(
                                progress = { if (sync.total > 0) sync.done.toFloat() / sync.total else 0f },
                                modifier = Modifier.fillMaxWidth(),
                                color = Brand.Pink,
                            )
                            Text(
                                if (sync.total > 0) "Saving page ${sync.done} of ${sync.total}…" else sync.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(onClick = { vm.cancelSync() }) { Text("Stop") }
                        } else {
                            if (sync.message.isNotBlank() && sync.state != "idle") {
                                Text(
                                    sync.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (sync.state == "failed") {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            Button(onClick = { vm.startSync() }) {
                                Icon(painterResource(R.drawable.ic_download), null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Download everything")
                            }
                            TextButton(onClick = { confirmClear = true }) { Text("Clear downloaded content") }
                        }
                    }
                }

                // ------------------------------------------------ community
                Group("Community") {
                    LinkRow("How to join") { vm.openPath("/join") }
                    RowDivider()
                    LinkRow("Official platforms and links") { vm.openPath("/platforms") }
                    RowDivider()
                    LinkRow("Safety Center") { vm.openPath("/safety") }
                    RowDivider()
                    LinkRow("Help Center") { vm.openPath("/help") }
                    RowDivider()
                    LinkRow("Contact Mello Zone") { vm.openPath("/contact") }
                }

                // ---------------------------------------------------- legal
                Group("Policies") {
                    LinkRow("Privacy policy") { vm.openPath("/privacy") }
                    RowDivider()
                    LinkRow("Privacy Center") { vm.openPath("/privacy-center") }
                    RowDivider()
                    LinkRow("Terms of service") { vm.openPath("/tos") }
                    RowDivider()
                    LinkRow("Accessibility") { vm.openPath("/accessibility") }
                    RowDivider()
                    LinkRow("Open-source notices") { showNotices = true }
                }

                // ------------------------------------------------ developer
                Group("Advanced") {
                    LinkRow(
                        title = "Developer options",
                        subtitle = "Engine diagnostics, logs, cache tools and test switches.",
                        icon = R.drawable.ic_code,
                    ) { vm.push(Screen.Developer) }
                }

                Text(
                    "© Mello Zone Furry Community · ${BuildConfig.APPLICATION_ID}\n" +
                        "This app does not collect analytics or advertising data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear downloaded content?") },
            text = { Text("Pages and images saved for offline reading will be removed. Your Saved list and settings are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    vm.clearCache("all")
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
    if (showNotices) {
        AlertDialog(
            onDismissRequest = { showNotices = false },
            title = { Text("Open-source notices") },
            text = {
                Text(
                    NOTICES,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { showNotices = false }) { Text("Close") } },
        )
    }
}

private const val NOTICES =
    "This app is built with open-source software.\n\n" +
        "Rust engine (MIT or Apache-2.0 unless noted): serde, serde_json, ureq, rustls, " +
        "rustls-webpki, webpki-roots (MPL-2.0 data), ring (ISC and OpenSSL-derived licences), " +
        "scraper (ISC), html5ever, selectors, cssparser (MPL-2.0), url, flate2, jni.\n\n" +
        "Android: Jetpack Compose, Material 3, AndroidX Activity, Core, Lifecycle and SplashScreen " +
        "(Apache-2.0); Kotlin, kotlinx.coroutines and kotlinx.serialization (Apache-2.0).\n\n" +
        "Full licence texts are available from each project's repository."

@Composable
fun Group(title: String, content: @Composable () -> Unit) {
    Column {
        SectionLabel(title, Modifier.padding(top = 10.dp, bottom = 8.dp))
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column { content() }
        }
    }
}

@Composable
fun RowDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 18.dp))
}

@Composable
fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
fun LinkRow(
    title: String,
    subtitle: String? = null,
    icon: Int? = null,
    trailing: ImageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), null, tint = Brand.Pink, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(trailing, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
