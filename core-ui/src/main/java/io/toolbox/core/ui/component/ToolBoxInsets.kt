package io.toolbox.core.ui.component

import androidx.compose.runtime.Immutable

/**
 * The semantic owner for a system inset within [ToolBoxAppScaffold].
 *
 * This is deliberately public so host routes can be tested without pretending JVM layout tests
 * measure real cutouts, navigation modes, or an on-screen keyboard.
 */
enum class ToolBoxInsetOwner {
    TopBar,
    BottomBar,
    Content,
    FloatingActionButton,
    InheritedFromBottomBar,
    None,
}

@Immutable
data class ToolBoxAppScaffoldInsetOwnership(
    val statusBars: ToolBoxInsetOwner,
    val displayCutout: ToolBoxInsetOwner,
    val navigationBars: ToolBoxInsetOwner,
    val ime: ToolBoxInsetOwner,
    val floatingActionButton: ToolBoxInsetOwner,
)

/**
 * The single inset policy for every host route.
 *
 * A present top or bottom slot includes its own system geometry in the measurement Miuix receives.
 * When that slot is absent, the content receives the fallback padding. Content always owns IME
 * padding so focused fields remain reachable. A floating action button inherits an existing
 * bottom bar's safe placement; otherwise it applies its own navigation and IME placement without
 * asking feature code to reinterpret window insets.
 */
object ToolBoxAppScaffoldInsetPolicy {
    fun resolve(
        hasTopBar: Boolean,
        hasBottomBar: Boolean,
        hasFloatingActionButton: Boolean,
    ): ToolBoxAppScaffoldInsetOwnership = ToolBoxAppScaffoldInsetOwnership(
        statusBars = if (hasTopBar) ToolBoxInsetOwner.TopBar else ToolBoxInsetOwner.Content,
        displayCutout = if (hasTopBar) ToolBoxInsetOwner.TopBar else ToolBoxInsetOwner.Content,
        navigationBars = if (hasBottomBar) ToolBoxInsetOwner.BottomBar else ToolBoxInsetOwner.Content,
        ime = ToolBoxInsetOwner.Content,
        floatingActionButton = when {
            !hasFloatingActionButton -> ToolBoxInsetOwner.None
            hasBottomBar -> ToolBoxInsetOwner.InheritedFromBottomBar
            else -> ToolBoxInsetOwner.FloatingActionButton
        },
    )
}
