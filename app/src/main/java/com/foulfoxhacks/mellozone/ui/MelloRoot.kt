package com.foulfoxhacks.mellozone.ui

import android.content.Context
import android.net.ConnectivityManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.foulfoxhacks.mellozone.R
import com.foulfoxhacks.mellozone.ui.blocks.FORM_PREFIX
import com.foulfoxhacks.mellozone.ui.blocks.LocalBlockOutlines
import com.foulfoxhacks.mellozone.ui.components.LocalAllowImageDownloads
import com.foulfoxhacks.mellozone.ui.components.SectionLabel
import com.foulfoxhacks.mellozone.ui.screens.DeveloperScreen
import com.foulfoxhacks.mellozone.ui.screens.ExploreScreen
import com.foulfoxhacks.mellozone.ui.screens.InfoScreen
import com.foulfoxhacks.mellozone.ui.screens.JournalScreen
import com.foulfoxhacks.mellozone.ui.screens.PageScreen
import com.foulfoxhacks.mellozone.ui.screens.SavedScreen
import com.foulfoxhacks.mellozone.ui.theme.Brand
import com.foulfoxhacks.mellozone.ui.theme.MelloTheme
import kotlinx.coroutines.launch

private fun isMetered(context: Context): Boolean =
    context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false

@Composable
private fun rememberMetered(): Boolean {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var metered by remember { mutableStateOf(isMetered(context)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) metered = isMetered(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return metered
}

private fun tabLabel(screen: Screen): String = when (screen) {
    Screen.Home -> "Home"
    Screen.Journal -> "Journal"
    Screen.Explore -> "Explore"
    Screen.Saved -> "Saved"
    Screen.Info -> "Info"
    Screen.Developer -> "Developer options"
    is Screen.PageView -> ""
}

@Composable
private fun tabIcon(screen: Screen): Painter = when (screen) {
    Screen.Journal -> painterResource(R.drawable.ic_article)
    Screen.Explore -> painterResource(R.drawable.ic_explore)
    Screen.Saved -> painterResource(R.drawable.ic_bookmark)
    Screen.Developer -> painterResource(R.drawable.ic_code)
    Screen.Info -> androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Filled.Info)
    else -> androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Filled.Home)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MelloRoot(vm: AppViewModel) {
    val settings = vm.settings
    val context = LocalContext.current
    val metered = rememberMetered()

    MelloTheme(pureBlack = settings.pureBlack, textScale = settings.textScale) {
        CompositionLocalProvider(
            LocalAllowImageDownloads provides !(settings.dataSaver && metered),
            LocalBlockOutlines provides settings.devBlockOutlines,
        ) {
            val drawer = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            val snackbar = remember { SnackbarHostState() }
            val entry = vm.current
            val rootTab = vm.stack.first().screen

            val onLink: (String) -> Unit = remember(vm, context) {
                { href ->
                    if (href.startsWith(FORM_PREFIX)) {
                        openExternal(context, href.removePrefix(FORM_PREFIX))
                    } else {
                        vm.openLink(href)
                    }
                }
            }

            LaunchedEffect(vm) {
                vm.events.collect { event ->
                    when (event) {
                        is UiEvent.OpenExternal -> openExternal(context, event.url)
                        is UiEvent.Message -> launch {
                            snackbar.currentSnackbarData?.dismiss()
                            snackbar.showSnackbar(event.text)
                        }
                    }
                }
            }

            BackHandler(enabled = vm.canGoBack) { vm.back() }
            BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

            fun closeDrawerThen(action: () -> Unit) {
                scope.launch { drawer.close() }
                action()
            }

            ModalNavigationDrawer(
                drawerState = drawer,
                drawerContent = {
                    MelloDrawer(
                        current = entry.screen,
                        onTab = { closeDrawerThen { vm.selectTab(it) } },
                        onPath = { closeDrawerThen { vm.openPath(it) } },
                        onDeveloper = { closeDrawerThen { vm.push(Screen.Developer) } },
                    )
                },
            ) {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        MelloTopBar(
                            vm = vm,
                            entry = entry,
                            onMenu = { scope.launch { drawer.open() } },
                        )
                    },
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                            TABS.forEach { tab ->
                                NavigationBarItem(
                                    selected = rootTab == tab,
                                    onClick = { vm.selectTab(tab) },
                                    icon = { Icon(tabIcon(tab), contentDescription = null) },
                                    label = { Text(tabLabel(tab), maxLines = 1) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    ),
                                )
                            }
                        }
                    },
                    snackbarHost = { SnackbarHost(snackbar) },
                ) { padding ->
                    val holder = rememberSaveableStateHolder()
                    AnimatedContent(
                        targetState = entry,
                        contentKey = { it.id },
                        transitionSpec = { fadeIn(tween(170)) togetherWith fadeOut(tween(110)) },
                        label = "screen",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .consumeWindowInsets(padding)
                            .imePadding(),
                    ) { target ->
                        holder.SaveableStateProvider(target.id) {
                            when (val screen = target.screen) {
                                Screen.Home -> PageScreen(vm, "/", onLink)
                                Screen.Journal -> JournalScreen(vm)
                                Screen.Explore -> ExploreScreen(vm)
                                Screen.Saved -> SavedScreen(vm)
                                Screen.Info -> InfoScreen(vm)
                                Screen.Developer -> DeveloperScreen(vm)
                                is Screen.PageView -> PageScreen(vm, screen.path, onLink)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MelloTopBar(vm: AppViewModel, entry: Entry, onMenu: () -> Unit) {
    val context = LocalContext.current
    val screen = entry.screen
    val isRoot = vm.stack.size == 1
    val page = (screen as? Screen.PageView)?.let { vm.pages[it.path]?.result?.page }
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
        ),
        navigationIcon = {
            if (isRoot) {
                IconButton(onClick = onMenu) { Icon(Icons.Filled.Menu, contentDescription = "Open menu") }
            } else {
                IconButton(onClick = { vm.back() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        },
        title = {
            when (screen) {
                Screen.Home -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(44.dp))
                    Text("Mello Zone", style = MaterialTheme.typography.titleLarge)
                }
                is Screen.PageView -> Text(
                    text = page?.displayTitle.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                else -> Text(tabLabel(screen), style = MaterialTheme.typography.titleLarge)
            }
        },
        actions = {
            if (screen is Screen.PageView) {
                val path = screen.path
                val saved = vm.isSaved(path)
                var menu by remember { mutableStateOf(false) }
                IconButton(onClick = { vm.toggleSaved(path, page?.displayTitle ?: path) }) {
                    Icon(
                        painterResource(if (saved) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border),
                        contentDescription = if (saved) "Remove from Saved" else "Save for later",
                        tint = if (saved) Brand.Pink else MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = {
                    sharePage(context, page?.displayTitle ?: "Mello Zone", "https://mellozone.site$path")
                }) { Icon(Icons.Filled.Share, contentDescription = "Share") }
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Refresh") },
                        onClick = {
                            menu = false
                            vm.refreshPage(path)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Open in browser") },
                        onClick = {
                            menu = false
                            openExternal(context, "https://mellozone.site$path")
                        },
                    )
                }
            } else if (screen != Screen.Explore && screen != Screen.Developer) {
                IconButton(onClick = {
                    vm.focusSearch = true
                    vm.selectTab(Screen.Explore)
                }) { Icon(Icons.Filled.Search, contentDescription = "Search") }
            }
        },
    )
}

private val DRAWER_SECTIONS: List<Pair<String, List<Pair<String, String>>>> = listOf(
    "Community" to listOf(
        "How to join" to "/join",
        "Our community" to "/community",
        "Events" to "/events",
        "Platforms" to "/platforms",
        "Featured creators" to "/featured-creators",
        "Partnered communities" to "/partnered-communities",
        "Community voices" to "/community-voices",
    ),
    "Safety and help" to listOf(
        "Safety Center" to "/safety",
        "Age verification" to "/verification",
        "Help Center" to "/help",
        "FAQ" to "/faq",
        "Report a concern" to "/report",
    ),
    "About" to listOf(
        "About Mello Zone" to "/about",
        "Manifesto" to "/manifesto",
        "Transparency" to "/transparency",
        "Contact" to "/contact",
    ),
)

/** The slide-out drawer: tabs, the site's main sections, Info and Developer options. */
@Composable
private fun MelloDrawer(
    current: Screen,
    onTab: (Screen) -> Unit,
    onPath: (String) -> Unit,
    onDeveloper: () -> Unit,
) {
    val currentPath = (current as? Screen.PageView)?.path
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Row(
                Modifier.padding(start = 12.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(72.dp))
                Column {
                    Text(
                        "Mello Zone",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        "18+ SFW furry community",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            TABS.filter { it != Screen.Info }.forEach { tab ->
                DrawerItem(tabLabel(tab), tabIcon(tab), selected = current == tab) { onTab(tab) }
            }
            DRAWER_SECTIONS.forEach { (title, links) ->
                DrawerDivider()
                SectionLabel(title, Modifier.padding(start = 28.dp, top = 6.dp, bottom = 8.dp))
                links.forEach { (label, path) ->
                    DrawerItem(label, icon = null, selected = currentPath == path) { onPath(path) }
                }
            }
            DrawerDivider()
            SectionLabel("App", Modifier.padding(start = 28.dp, top = 6.dp, bottom = 8.dp))
            DrawerItem("Info", tabIcon(Screen.Info), selected = current == Screen.Info) { onTab(Screen.Info) }
            DrawerItem(
                "Developer options", tabIcon(Screen.Developer),
                selected = current == Screen.Developer, onClick = onDeveloper,
            )
        }
    }
}

@Composable
private fun DrawerDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 28.dp, vertical = 10.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun DrawerItem(label: String, icon: Painter?, selected: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label) },
        selected = selected,
        onClick = onClick,
        icon = icon?.let { painter -> { Icon(painter, contentDescription = null) } },
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
    )
}
