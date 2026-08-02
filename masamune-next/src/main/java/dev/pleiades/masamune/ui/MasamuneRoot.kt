package dev.pleiades.masamune.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.pleiades.masamune.core.halt.HaltController
import dev.pleiades.masamune.nav.RouteCatalog
import dev.pleiades.masamune.nav.Surface
import dev.pleiades.masamune.ui.about.AboutScreen
import dev.pleiades.masamune.ui.about.NavigationMapScreen
import dev.pleiades.masamune.ui.about.RyznixRoadmapScreen
import dev.pleiades.masamune.flow.ui.FlowPlaneScreen
import dev.pleiades.masamune.operator.ui.OperatorScreen
import dev.pleiades.masamune.ui.chat.ChatScreen
import dev.pleiades.masamune.ui.files.FilesScreen
import dev.pleiades.masamune.ui.settings.AccountScreen
import dev.pleiades.masamune.ui.settings.CapabilitiesScreen
import dev.pleiades.masamune.ui.settings.DeclineLogScreen
import dev.pleiades.masamune.ui.settings.ProviderSettingsScreen
import dev.pleiades.masamune.ui.shell.ShellScreen

/**
 * The app shell.
 *
 * Both the bottom bar and the NavHost are generated from [RouteCatalog]. Adding a screen means
 * adding a row to that table; a screen absent from the table has no route and therefore does
 * not exist, which is the whole point.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MasamuneRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val haltState by HaltController.state.collectAsState()

    val isTopLevel = RouteCatalog.bottomNav.any { it.route == currentRoute }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(RouteCatalog.titleOf(currentRoute)) },
                navigationIcon = {
                    if (!isTopLevel) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    // Global halt control: reachable from every screen, not buried in chat.
                    IconButton(
                        onClick = {
                            if (haltState is HaltController.State.Halted) {
                                HaltController.clear()
                            } else {
                                HaltController.requestHalt("user", "halt button")
                            }
                        }
                    ) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = if (haltState is HaltController.State.Halted) {
                                "Clear halt"
                            } else {
                                "Halt everything"
                            },
                            tint = if (haltState is HaltController.State.Halted) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                RouteCatalog.bottomNav.forEach { entry ->
                    NavigationBarItem(
                        selected = currentRoute == entry.route,
                        onClick = {
                            if (currentRoute != entry.route) {
                                navController.navigate(entry.route) {
                                    popUpTo(RouteCatalog.START) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = {
                            entry.icon?.let { Icon(it, contentDescription = entry.title) }
                        },
                        label = { Text(entry.title) },
                    )
                }
            }
        },
    ) { inner ->
        NavHost(
            navController = navController,
            startDestination = RouteCatalog.START,
            modifier = Modifier.padding(inner),
        ) {
            registerRoutes(navigate = { navController.navigate(it) })
        }
    }
}

/**
 * One `composable(...)` per catalog row. The `when` is exhaustive over the catalog's route
 * constants; a new constant without a branch fails to compile the screen into the graph, which
 * is the compile-time half of the reachability guarantee.
 */
private fun NavGraphBuilder.registerRoutes(navigate: (String) -> Unit) {
    RouteCatalog.all.forEach { entry ->
        composable(entry.route) {
            when (entry.route) {
                RouteCatalog.FILES -> FilesScreen()
                RouteCatalog.SHELL -> ShellScreen(onOpenCapabilities = { navigate(RouteCatalog.SETTINGS_CAPABILITIES) })
                RouteCatalog.CHAT -> ChatScreen(
                    onOpenProviderSettings = { navigate(RouteCatalog.SETTINGS_PROVIDER) },
                    onOpenCapabilities = { navigate(RouteCatalog.SETTINGS_CAPABILITIES) },
                    onOpenAccount = { navigate(RouteCatalog.SETTINGS_ACCOUNT) },
                )
                RouteCatalog.FLOWS -> FlowPlaneScreen()
                RouteCatalog.OPERATOR -> OperatorScreen()
                RouteCatalog.ABOUT -> AboutScreen(onNavigate = navigate)
                RouteCatalog.SETTINGS_PROVIDER -> ProviderSettingsScreen(
                    onOpenAccount = { navigate(RouteCatalog.SETTINGS_ACCOUNT) },
                )
                RouteCatalog.SETTINGS_ACCOUNT -> AccountScreen()
                RouteCatalog.SETTINGS_CAPABILITIES -> CapabilitiesScreen()
                RouteCatalog.SETTINGS_DECLINES -> DeclineLogScreen()
                RouteCatalog.RYZNIX -> RyznixRoadmapScreen()
                RouteCatalog.NAV_MAP -> NavigationMapScreen()
                else -> UnroutedScreen(entry.route)
            }
        }
    }
}

/** If a catalog row ever loses its branch, the app says so rather than showing a blank page. */
@Composable
private fun UnroutedScreen(route: String) {
    dev.pleiades.masamune.ui.components.EmptyState(
        title = "No screen wired for \"$route\"",
        body = "This route is declared in RouteCatalog but has no composable. That is a bug, " +
            "not a feature that is coming soon.",
    )
}

/** Kept for the surface enum's exhaustiveness; referenced by the catalog. */
internal val allSurfaces = Surface.entries
