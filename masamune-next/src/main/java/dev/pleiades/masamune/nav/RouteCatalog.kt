package dev.pleiades.masamune.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The route table. This is the mechanism that makes "every feature has a path from the first
 * screen" checkable instead of a promise: the bottom bar and the NavHost are both GENERATED
 * from this list, so a screen that is not declared here literally cannot be reached, and a
 * screen declared here is guaranteed a nav entry.
 *
 * Pattern salvaged from the donor tree's AppRouteCatalog / ScreenRouteRegistry; none of its
 * 765 lines are carried over.
 */
enum class Surface {
    /** Rendered as a bottom-navigation destination. */
    BOTTOM_NAV,

    /** Reachable only by pushing from another screen; still declared, still generated. */
    DETAIL,
}

data class MasamuneRoute(
    val route: String,
    val title: String,
    val icon: ImageVector?,
    val surface: Surface,
    val order: Int,
    /** How a user gets here from a cold launch. Rendered verbatim on About → Navigation map. */
    val navPath: String,
)

object RouteCatalog {

    const val FILES = "files"
    const val SHELL = "shell"
    const val CHAT = "chat"
    const val ABOUT = "about"

    const val SETTINGS_PROVIDER = "settings/provider"
    const val SETTINGS_CAPABILITIES = "settings/capabilities"
    const val SETTINGS_DECLINES = "settings/declines"
    const val RYZNIX = "about/ryznix"
    const val NAV_MAP = "about/navmap"

    /** Start destination. The file explorer is the primary surface. */
    const val START = FILES

    val all: List<MasamuneRoute> = listOf(
        MasamuneRoute(FILES, "Files", Icons.Filled.Folder, Surface.BOTTOM_NAV, 0, "app launch (start destination)"),
        MasamuneRoute(SHELL, "Shell", Icons.Filled.Terminal, Surface.BOTTOM_NAV, 1, "bottom nav → Shell"),
        MasamuneRoute(CHAT, "Chat", Icons.Filled.Chat, Surface.BOTTOM_NAV, 2, "bottom nav → Chat"),
        MasamuneRoute(ABOUT, "About", Icons.Filled.Info, Surface.BOTTOM_NAV, 3, "bottom nav → About"),
        MasamuneRoute(
            SETTINGS_PROVIDER, "AI provider", null, Surface.DETAIL, 10,
            "bottom nav → About → AI provider (also the gear on Chat)",
        ),
        MasamuneRoute(
            SETTINGS_CAPABILITIES, "Capabilities", null, Surface.DETAIL, 11,
            "bottom nav → About → Capabilities",
        ),
        MasamuneRoute(
            SETTINGS_DECLINES, "Refusal log", null, Surface.DETAIL, 12,
            "bottom nav → About → Refusal log",
        ),
        MasamuneRoute(
            RYZNIX, "ryznix / second OS", null, Surface.DETAIL, 13,
            "bottom nav → About → ryznix roadmap",
        ),
        MasamuneRoute(
            NAV_MAP, "Navigation map", null, Surface.DETAIL, 14,
            "bottom nav → About → Navigation map",
        ),
    )

    val bottomNav: List<MasamuneRoute> =
        all.filter { it.surface == Surface.BOTTOM_NAV }.sortedBy { it.order }

    fun titleOf(route: String?): String =
        all.firstOrNull { it.route == route }?.title ?: "Masamune"
}
