package se.spareparts.inventory.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import se.spareparts.inventory.AppContainer
import se.spareparts.inventory.ui.auth.ChangePasswordScreen
import se.spareparts.inventory.ui.auth.SignInScreen
import se.spareparts.inventory.ui.components.SyncBanner
import se.spareparts.inventory.ui.detail.PartDetailScreen
import se.spareparts.inventory.ui.scan.ScannerScreen
import se.spareparts.inventory.ui.search.SearchScreen
import se.spareparts.inventory.ui.settings.SettingsScreen
import se.spareparts.inventory.ui.theme.AppIcons
import android.net.Uri

object Routes {
    const val SCAN = "scan"
    const val SEARCH = "search?q={q}"
    const val LOW = "low"
    const val SETTINGS = "settings"
    const val PART = "part/{pn}"
    const val PASSWORD = "password"
    fun part(pn: String) = "part/" + Uri.encode(pn)
    fun search(q: String = "") = "search?q=" + Uri.encode(q)
}

private data class Tab(val route: String, val nav: String, val label: String, val icon: ImageVector)

/**
 * Decides what the app shows: the sign-in screen while there is no token, the forced
 * "choose a password" wall for a freshly created account, and otherwise the app itself.
 */
@Composable
fun AppRoot(c: AppContainer) {
    val session by c.session.session.collectAsStateWithLifecycle()
    val s = session
    when {
        s == null || !s.valid -> SignInScreen(c)
        s.user.mustChange && !s.device -> ChangePasswordScreen(c, forced = true, onDone = {})
        else -> MainApp(c)
    }
}

@Composable
private fun MainApp(c: AppContainer) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val repo = c.repository
    val online by repo.online.collectAsStateWithLifecycle()
    val pending by repo.pending.collectAsStateWithLifecycle()
    val status by repo.status.collectAsStateWithLifecycle()
    val lowCount = repo.parts.collectAsStateWithLifecycle().value.count { it.isLow }

    LaunchedEffect(Unit) { repo.messages.collect { snackbar.showSnackbar(it) } }

    val tabs = listOf(
        Tab(Routes.SCAN, Routes.SCAN, "Scan", AppIcons.QrScan),
        Tab(Routes.SEARCH, Routes.search(), "Search", Icons.Filled.Search),
        Tab(Routes.LOW, Routes.LOW, "Low stock", AppIcons.TrendingDown),
        Tab(Routes.SETTINGS, Routes.SETTINGS, "Settings", Icons.Filled.Settings),
    )
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination
    val onTab = tabs.any { t -> current?.hierarchy?.any { it.route == t.route } == true }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (onTab) {
                NavigationBar {
                    tabs.forEach { t ->
                        val selected = current?.hierarchy?.any { it.route == t.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(t.nav) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                if (t.route == Routes.LOW && lowCount > 0) {
                                    BadgedBox(badge = { Badge { Text(lowCount.toString()) } }) { Icon(t.icon, null) }
                                } else Icon(t.icon, null)
                            },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            // The scanner is full-bleed; other screens draw their own top bars below the banner.
            if (current?.route != Routes.SCAN) {
                Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                    SyncBanner(online, status.reachable, pending.size, status.error) {
                        scope.launch { repo.sync() }
                    }
                }
            }
            NavHost(nav, startDestination = Routes.SCAN, modifier = Modifier.weight(1f)) {
                composable(Routes.SCAN) {
                    ScannerScreen(
                        c = c,
                        banner = { SyncBanner(online, status.reachable, pending.size, status.error) { scope.launch { repo.sync() } } },
                        openPart = { nav.navigate(Routes.part(it)) },
                        searchFor = { q -> nav.navigate(Routes.search(q)) { launchSingleTop = true } },
                    )
                }
                composable(
                    Routes.SEARCH,
                    arguments = listOf(navArgument("q") { type = NavType.StringType; defaultValue = "" }),
                ) { entry ->
                    SearchScreen(c, initialQuery = entry.arguments?.getString("q").orEmpty(), lowOnly = false,
                        openPart = { nav.navigate(Routes.part(it)) })
                }
                composable(Routes.LOW) {
                    SearchScreen(c, initialQuery = "", lowOnly = true, openPart = { nav.navigate(Routes.part(it)) })
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(c, changePassword = { nav.navigate(Routes.PASSWORD) })
                }
                composable(Routes.PASSWORD) {
                    ChangePasswordScreen(c, forced = false, onDone = {}, onCancel = { nav.popBackStack() })
                }
                composable(Routes.PART, arguments = listOf(navArgument("pn") { type = NavType.StringType })) { entry ->
                    val pn = entry.arguments?.getString("pn").orEmpty()
                    PartDetailScreen(c, pn, onBack = { nav.popBackStack() }, snackbar = snackbar)
                }
            }
        }
    }
}
