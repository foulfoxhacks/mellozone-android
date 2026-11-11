package com.foulfoxhacks.mellozone.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.foulfoxhacks.mellozone.BuildConfig
import com.foulfoxhacks.mellozone.core.Core
import com.foulfoxhacks.mellozone.core.DiagInfo
import com.foulfoxhacks.mellozone.core.LogEntry
import com.foulfoxhacks.mellozone.core.TestResult
import com.foulfoxhacks.mellozone.ui.AppViewModel
import com.foulfoxhacks.mellozone.ui.components.Centered
import com.foulfoxhacks.mellozone.ui.components.LocalAllowImageDownloads
import com.foulfoxhacks.mellozone.ui.components.formatBytes
import com.foulfoxhacks.mellozone.ui.theme.Brand
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun pageSize(): Long = try {
    Os.sysconf(OsConstants._SC_PAGESIZE)
} catch (e: Exception) {
    -1
}

private fun deviceLines(): List<Pair<String, String>> = listOf(
    "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
    "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    "ABIs" to Build.SUPPORTED_ABIS.joinToString(", "),
    "Memory page size" to pageSize().let { if (it > 0) "${it / 1024} KB" else "unknown" },
    "App ID" to BuildConfig.APPLICATION_ID,
    "App version" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}",
)

private fun engineLines(d: DiagInfo): List<Pair<String, String>> = listOf(
    "Engine" to "mellozone-core ${d.coreVersion} (${d.profile})",
    "Compiler" to d.rustc,
    "Target" to d.target,
    "HTTP" to d.http,
    "TLS" to d.tls,
    "HTML" to d.html,
    "Site" to d.site,
    "Uptime" to "${d.uptimeSecs / 60} min ${d.uptimeSecs % 60} s",
    "Requests" to "${d.requests} (${d.failures} failed)",
    "Downloaded" to formatBytes(d.bytesDownloaded),
    "Cache hits" to d.cacheHits.toString(),
    "Pages parsed" to d.pagesParsed.toString(),
    "Saved pages" to "${d.storage.pages} (${formatBytes(d.storage.pageBytes)})",
    "Saved images" to "${d.storage.images} (${formatBytes(d.storage.imageBytes)})",
    "Data folder" to d.dataDir,
)

private val logTime = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

private fun copy(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText(label, text))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeveloperScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var diag by remember { mutableStateOf<DiagInfo?>(null) }
    var logs by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var tests by remember { mutableStateOf<List<TestResult>?>(null) }
    var testing by remember { mutableStateOf(false) }
    var path by rememberSaveable { mutableStateOf("/") }
    val settings = vm.settings

    fun reload() {
        scope.launch {
            runCatching { diag = Core.diag() }
            runCatching { logs = Core.logs() }
        }
    }
    LaunchedEffect(Unit) { reload() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Centered {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "Tools for testing and troubleshooting. These switches change how the app " +
                        "loads content; turn them off for normal use.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Group("Test switches") {
                    SwitchRow(
                        "Always load from network",
                        "Skip the saved copy and fetch every page fresh.",
                        settings.devBypassCache,
                    ) { vm.setFlag("dev_bypass_cache", it) }
                    RowDivider()
                    SwitchRow(
                        "Simulate offline",
                        "The engine refuses all network requests.",
                        settings.devSimulateOffline,
                    ) { vm.setFlag("dev_simulate_offline", it) }
                    RowDivider()
                    SwitchRow(
                        "Show block outlines",
                        "Outline and label every content block on pages.",
                        settings.devBlockOutlines,
                    ) { vm.setFlag("dev_block_outlines", it) }
                    RowDivider()
                    SwitchRow(
                        "Show content source",
                        "Add cache/network details under each page.",
                        settings.devSourceBadge,
                    ) { vm.setFlag("dev_source_badge", it) }
                    RowDivider()
                    SwitchRow(
                        "Verbose engine log",
                        "Record parse timings and image requests.",
                        settings.devVerboseLog,
                    ) { vm.setFlag("dev_verbose_log", it) }
                    RowDivider()
                    TextButton(
                        onClick = { vm.resetDeveloperOptions() },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) { Text("Reset all switches") }
                }

                Group("Open a page by path") {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = path,
                            onValueChange = { path = it },
                            label = { Text("Path on mellozone.site") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { vm.openLink(path.trim()) }),
                        )
                        Button(onClick = { vm.openLink(path.trim()) }) { Text("Open") }
                    }
                }

                Group("Self-test") {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            enabled = !testing,
                            onClick = {
                                testing = true
                                scope.launch {
                                    tests = runCatching { Core.selfTest().results }.getOrElse {
                                        listOf(TestResult("Self-test", false, it.message ?: "failed"))
                                    }
                                    testing = false
                                    reload()
                                }
                            },
                        ) { Text(if (testing) "Running…" else "Run engine self-test") }
                        tests?.forEach { t ->
                            Row {
                                Text(
                                    if (t.ok) "PASS" else "FAIL",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (t.ok) Color(0xFF7CE0A3) else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.width(48.dp),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "${t.name} · ${String.format(Locale.US, "%.1f", t.ms)} ms",
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Text(
                                        t.detail,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                Group("Storage") {
                    FlowRow(
                        Modifier.padding(18.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedButton(onClick = { vm.clearCache("pages"); reload() }) { Text("Clear pages") }
                        OutlinedButton(onClick = { vm.clearCache("images"); reload() }) { Text("Clear images") }
                        OutlinedButton(onClick = { vm.startSync() }, enabled = !vm.sync.running) {
                            Text(if (vm.sync.running) "Syncing ${vm.sync.done}/${vm.sync.total}" else "Sync all pages")
                        }
                    }
                }

                val info = diag
                val device = remember { deviceLines() } +
                    ("Image downloads" to if (LocalAllowImageDownloads.current) "allowed" else "paused (data saver)")
                Group("Engine") {
                    SelectionContainer {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (info == null) Text("Loading…") else engineLines(info).forEach { KeyValue(it.first, it.second) }
                        }
                    }
                }
                Group("Device") {
                    SelectionContainer {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            device.forEach { KeyValue(it.first, it.second) }
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        val text = buildString {
                            appendLine("Mello Zone diagnostics")
                            device.forEach { appendLine("${it.first}: ${it.second}") }
                            info?.let { d -> engineLines(d).forEach { appendLine("${it.first}: ${it.second}") } }
                        }
                        copy(context, "Mello Zone diagnostics", text)
                        vm.message("Diagnostics copied")
                    }) { Text("Copy diagnostics") }
                    OutlinedButton(onClick = { reload() }) { Text("Refresh") }
                }

                Group("Engine log (${logs.size})") {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = {
                                copy(
                                    context, "Mello Zone log",
                                    logs.joinToString("\n") {
                                        "${logTime.format(Date(it.at))} ${it.level.uppercase()} ${it.tag}: ${it.message}"
                                    },
                                )
                                vm.message("Log copied")
                            }) { Text("Copy") }
                            OutlinedButton(onClick = {
                                scope.launch {
                                    runCatching { Core.logsClear() }
                                    reload()
                                }
                            }) { Text("Clear") }
                        }
                        if (logs.isEmpty()) {
                            Text("No entries yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        logs.take(120).forEach { entry ->
                            Text(
                                text = "${logTime.format(Date(entry.at))}  ${entry.tag}  ${entry.message}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = when (entry.level) {
                                    "error" -> MaterialTheme.colorScheme.error
                                    "warn" -> Brand.PinkLight
                                    "debug" -> MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row {
        Text(
            key,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(128.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
