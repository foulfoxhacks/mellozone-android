package com.foulfoxhacks.mellozone.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foulfoxhacks.mellozone.core.Core
import com.foulfoxhacks.mellozone.core.CoreException
import com.foulfoxhacks.mellozone.core.DirSection
import com.foulfoxhacks.mellozone.core.FeedItem
import com.foulfoxhacks.mellozone.core.PageResult
import com.foulfoxhacks.mellozone.core.SavedItem
import com.foulfoxhacks.mellozone.core.Settings
import com.foulfoxhacks.mellozone.core.StoreStats
import com.foulfoxhacks.mellozone.core.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

sealed interface Screen {
    data object Home : Screen
    data object Journal : Screen
    data object Explore : Screen
    data object Saved : Screen
    data object Info : Screen
    data object Developer : Screen
    data class PageView(val path: String) : Screen
}

/** One entry on the back stack. [id] keys saved scroll positions. */
data class Entry(val id: Int, val screen: Screen)

data class PageUi(
    val result: PageResult? = null,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: CoreException? = null,
)

data class ListUi<T>(
    val items: List<T> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val warning: String? = null,
)

sealed interface UiEvent {
    data class OpenExternal(val url: String) : UiEvent
    data class Message(val text: String) : UiEvent
}

val TABS: List<Screen> = listOf(Screen.Home, Screen.Journal, Screen.Explore, Screen.Saved, Screen.Info)

private fun tabId(screen: Screen): Int = -1 - TABS.indexOf(screen)

class AppViewModel : ViewModel() {
    var settings by mutableStateOf(Settings())
        private set

    val stack = mutableStateListOf(Entry(tabId(Screen.Home), Screen.Home))
    private var nextId = 1

    val pages = mutableStateMapOf<String, PageUi>()
    private val pageJobs = HashMap<String, Job>()

    var journal by mutableStateOf(ListUi<FeedItem>())
        private set
    var directory by mutableStateOf(ListUi<DirSection>())
        private set
    var saved by mutableStateOf<List<SavedItem>>(emptyList())
        private set
    var sync by mutableStateOf(SyncStatus())
        private set
    var storage by mutableStateOf(StoreStats())
        private set

    /** One-shot request for the Explore tab to focus its search field. */
    var focusSearch by mutableStateOf(false)

    private val eventChannel = Channel<UiEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            runCatching { settings = Core.settings() }
            runCatching { saved = Core.savedList() }
            runCatching { sync = Core.syncStatus() }
        }
        ensurePage("/")
    }

    // ------------------------------------------------------- navigation --

    val current: Entry get() = stack.last()

    fun selectTab(screen: Screen) {
        val entry = Entry(tabId(screen), screen)
        if (stack.size == 1 && stack[0] == entry) return
        stack.clear()
        stack.add(entry)
        when (screen) {
            Screen.Home -> ensurePage("/")
            Screen.Journal -> ensureJournal()
            Screen.Explore -> ensureDirectory()
            else -> {}
        }
    }

    fun push(screen: Screen) {
        if (current.screen == screen) return
        stack.add(Entry(nextId++, screen))
        if (screen is Screen.PageView) ensurePage(screen.path)
        trimPages()
    }

    /** Returns false when there is nowhere left to go back to. */
    fun back(): Boolean = when {
        stack.size > 1 -> {
            stack.removeAt(stack.lastIndex)
            true
        }
        current.screen != Screen.Home -> {
            selectTab(Screen.Home)
            true
        }
        else -> false
    }

    val canGoBack: Boolean get() = stack.size > 1 || current.screen != Screen.Home

    fun openPath(path: String) {
        when (path) {
            "/" -> selectTab(Screen.Home)
            "/blog" -> selectTab(Screen.Journal)
            "/sitemap" -> selectTab(Screen.Explore)
            "/search" -> {
                focusSearch = true
                selectTab(Screen.Explore)
            }
            else -> push(Screen.PageView(path))
        }
    }

    /** Any tapped link: the engine decides whether it is a native page. */
    fun openLink(href: String) {
        viewModelScope.launch {
            val route = try {
                Core.route(href)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val path = route?.path
            if (route?.kind == "page" && path != null) {
                openPath(path)
            } else {
                eventChannel.send(UiEvent.OpenExternal(route?.url?.ifBlank { href } ?: href))
            }
        }
    }

    fun message(text: String) {
        eventChannel.trySend(UiEvent.Message(text))
    }

    // ------------------------------------------------------------ pages --

    fun ensurePage(path: String) {
        val existing = pages[path]
        if (existing != null && (existing.result != null || existing.loading)) return
        loadPage(path, refresh = false)
    }

    fun refreshPage(path: String) = loadPage(path, refresh = true)

    private fun loadPage(path: String, refresh: Boolean) {
        pageJobs[path]?.cancel()
        val before = pages[path]
        pages[path] = if (before?.result != null) {
            before.copy(refreshing = true, error = null)
        } else {
            PageUi(loading = true)
        }
        pageJobs[path] = viewModelScope.launch {
            try {
                var result = before?.result
                if (!refresh || result == null) {
                    // Instant when the page is on the device.
                    result = Core.page(path, "cache_first")
                    pages[path] = PageUi(result = result, refreshing = result.stale)
                }
                if (refresh || result.stale) {
                    val fresh = Core.page(path, "network")
                    pages[path] = PageUi(result = fresh)
                    if (refresh && fresh.warning != null) message("Showing the saved copy. ${fresh.warning}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CoreException) {
                val shown = pages[path]?.result
                pages[path] = if (shown != null) PageUi(result = shown) else PageUi(error = e)
                if (shown != null && refresh) message(e.message ?: "Could not refresh.")
            } catch (e: Exception) {
                pages[path] = PageUi(error = CoreException("internal", "This page could not be shown."))
            }
        }
    }

    private fun trimPages() {
        if (pages.size <= 40) return
        val keep = stack.mapNotNull { (it.screen as? Screen.PageView)?.path }.toSet() + "/"
        pages.keys.filter { it !in keep }.take(pages.size - 30).forEach {
            pages.remove(it)
            pageJobs.remove(it)
        }
    }

    // ---------------------------------------------- journal & directory --

    fun ensureJournal() {
        if (journal.loaded || journal.loading) return
        loadJournal(refresh = false)
    }

    fun refreshJournal() = loadJournal(refresh = true)

    private fun loadJournal(refresh: Boolean) {
        journal = journal.copy(loading = !journal.loaded, refreshing = journal.loaded, error = null)
        viewModelScope.launch {
            try {
                var r = if (refresh) null else Core.journal("cache_first")
                if (r != null) journal = ListUi(r.items, loaded = true, refreshing = r.stale, warning = r.warning)
                if (r == null || r.stale) {
                    r = Core.journal("network")
                    journal = ListUi(r.items, loaded = true, warning = r.warning)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                journal = if (journal.loaded) {
                    journal.copy(refreshing = false, loading = false, warning = e.message)
                } else {
                    ListUi(error = e.message ?: "The journal could not be loaded.")
                }
            }
        }
    }

    fun ensureDirectory() {
        if (directory.loaded || directory.loading) return
        loadDirectory(refresh = false)
    }

    fun refreshDirectory() = loadDirectory(refresh = true)

    private fun loadDirectory(refresh: Boolean) {
        directory = directory.copy(loading = !directory.loaded, refreshing = directory.loaded, error = null)
        viewModelScope.launch {
            try {
                var r = if (refresh) null else Core.directory("cache_first")
                if (r != null) directory = ListUi(r.sections, loaded = true, refreshing = r.stale, warning = r.warning)
                if (r == null || r.stale) {
                    r = Core.directory("network")
                    directory = ListUi(r.sections, loaded = true, warning = r.warning)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                directory = if (directory.loaded) {
                    directory.copy(refreshing = false, loading = false, warning = e.message)
                } else {
                    ListUi(error = e.message ?: "The page list could not be loaded.")
                }
            }
        }
    }

    // ------------------------------------------------------------ saved --

    fun isSaved(path: String): Boolean = saved.any { it.path == path }

    fun toggleSaved(path: String, title: String) {
        viewModelScope.launch {
            runCatching { Core.savedToggle(path, title) }
                .onSuccess {
                    saved = it.items
                    message(if (it.saved) "Saved for later" else "Removed from Saved")
                }
                .onFailure { message("Could not update Saved.") }
        }
    }

    // --------------------------------------------------------- settings --

    private fun set(key: String, value: JsonPrimitive) {
        viewModelScope.launch {
            runCatching { Core.setSetting(key, value) }.onSuccess { settings = it }
        }
    }

    fun setFlag(key: String, value: Boolean) = set(key, JsonPrimitive(value))

    fun setTextScale(value: Float) {
        // Update the UI immediately; persist in the background.
        settings = settings.copy(textScale = value)
        set("text_scale", JsonPrimitive(value))
    }

    fun resetDeveloperOptions() {
        listOf(
            "dev_bypass_cache", "dev_simulate_offline", "dev_block_outlines",
            "dev_verbose_log", "dev_source_badge",
        ).forEach { setFlag(it, false) }
    }

    // ---------------------------------------------- offline and storage --

    fun refreshStorage() {
        viewModelScope.launch { runCatching { storage = Core.cacheStats() } }
    }

    fun startSync() {
        viewModelScope.launch {
            runCatching {
                sync = Core.syncStart()
                while (sync.running) {
                    delay(350)
                    sync = Core.syncStatus()
                }
                storage = Core.cacheStats()
            }
        }
    }

    fun cancelSync() {
        viewModelScope.launch { runCatching { sync = Core.syncCancel() } }
    }

    fun clearCache(what: String) {
        viewModelScope.launch {
            runCatching { storage = Core.cacheClear(what) }
                .onSuccess {
                    if (what != "images") {
                        pages.clear()
                        journal = ListUi()
                        directory = ListUi()
                    }
                    message("Cleared")
                }
                .onFailure { message("Could not clear storage.") }
        }
    }
}
