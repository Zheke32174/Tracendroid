package dev.pleiades.masamune.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SmartToy
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
    const val EDITOR = "editor"
    const val CHAT = "chat"
    const val FLOWS = "flows"
    const val OPERATOR = "operator"
    const val ABOUT = "about"

    const val EDITOR_DISCLAIMER = "editor/disclaimer"
    const val EDITOR_WELCOME = "editor/welcome"

    const val SETTINGS_PROVIDER = "settings/provider"
    const val SETTINGS_ACCOUNT = "settings/account"
    const val SETTINGS_CAPABILITIES = "settings/capabilities"
    const val SETTINGS_DECLINES = "settings/declines"
    const val RYZNIX = "about/ryznix"
    const val NAV_MAP = "about/navmap"
    const val ROM = "about/rom"

    /** Start destination. The file explorer is the primary surface. */
    const val START = FILES

    val all: List<MasamuneRoute> = listOf(
        MasamuneRoute(FILES, "Files", Icons.Filled.Folder, Surface.BOTTOM_NAV, 0, "app launch (start destination)"),
        MasamuneRoute(SHELL, "Shell", Icons.Filled.Terminal, Surface.BOTTOM_NAV, 1, "bottom nav → Shell"),
        MasamuneRoute(CHAT, "Chat", Icons.Filled.Chat, Surface.BOTTOM_NAV, 2, "bottom nav → Chat"),
        MasamuneRoute(FLOWS, "Flows", Icons.Filled.AccountTree, Surface.BOTTOM_NAV, 3, "bottom nav → Flows"),
        MasamuneRoute(OPERATOR, "Operator", Icons.Filled.SmartToy, Surface.BOTTOM_NAV, 4, "bottom nav → Operator"),
        MasamuneRoute(ABOUT, "About", Icons.Filled.Info, Surface.BOTTOM_NAV, 5, "bottom nav → About"),
        // Editor shares order 1 with Shell; the stable sort keeps it immediately after Shell, so
        // the three workspace surfaces (Files/Shell/Editor) sit together in the bottom bar.
        MasamuneRoute(EDITOR, "Editor", Icons.Filled.Edit, Surface.BOTTOM_NAV, 1, "bottom nav → Editor"),
        MasamuneRoute(
            EDITOR_DISCLAIMER, "Terms of Use & Disclaimer", null, Surface.DETAIL, 20,
            "bottom nav → Editor → command palette → Terms of Use & Disclaimer",
        ),
        MasamuneRoute(
            EDITOR_WELCOME, "Welcome", null, Surface.DETAIL, 21,
            "bottom nav → Editor → command palette → Show welcome",
        ),
        MasamuneRoute(
            SETTINGS_PROVIDER, "AI provider", null, Surface.DETAIL, 10,
            "bottom nav → About → AI provider (also the gear on Chat)",
        ),
        MasamuneRoute(
            SETTINGS_ACCOUNT, "Account", null, Surface.DETAIL, 10,
            "bottom nav \u2192 About \u2192 Account (also the account icon on Chat)",
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
        MasamuneRoute(
            ROM, "Launch a ROM", null, Surface.DETAIL, 15,
            "bottom nav → About → Launch a ROM",
        ),
    )

    val bottomNav: List<MasamuneRoute> =
        all.filter { it.surface == Surface.BOTTOM_NAV }.sortedBy { it.order }

    fun titleOf(route: String?): String =
        all.firstOrNull { it.route == route }?.title ?: "Masamune"
}
