package dev.piatra.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import dev.piatra.R

// The three tabs at the bottom of the screen, each with the route it opens.
// icon: a picture in res/drawable (Phosphor, see tools/phosphor.py). R.drawable.x is the number
// Android's build gives each resource file; @DrawableRes lets lint check it is one.
enum class Tab(val route: Any, @DrawableRes val icon: Int, val label: String) {
    RECORDINGS(Home, R.drawable.ph_microphone, "Recordings"),
    LISTS(Lists, R.drawable.ph_check_square, "Lists"),
    NOTES(Notes, R.drawable.ph_note_pencil, "Notes"),
}

// True for the screens that show the tab bar.
fun NavDestination.isTab(): Boolean = Tab.entries.any { hasRoute(it.route::class) }

// A tab screen: its content with the tab bar underneath. Each tab screen draws its own bar,
// so the bar slides away together with the screen when a detail screen opens on top.
@Composable
fun TabScaffold(current: Tab, onSelect: (Tab) -> Unit, content: @Composable () -> Unit) {
    // Scaffold: Material's screen frame. It measures the bar and tells the content how much
    // room it takes (innerPadding).
    Scaffold(
        bottomBar = {
            NavigationBar {
                for (tab in Tab.entries) {
                    NavigationBarItem(
                        selected = tab == current,
                        onClick = { if (tab != current) onSelect(tab) },
                        // Icon tints the picture with the surrounding content colour (the
                        // selected tab's is brighter). No contentDescription: the label says it.
                        icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        // consumeWindowInsets: the bar already covers the phone's gesture area and the padding
        // covers the status bar, so the screen's own safeDrawingPadding() doesn't add them again.
        Box(Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)) {
            content()
        }
    }
}
