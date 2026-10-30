package dev.piatra.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// The side menu behind ☰ on the Recordings tab: Device, Logs and Settings.
// It slides over the screen from the left; a swipe or a tap outside closes it.
// content gets the function that opens it.
@Composable
fun MenuDrawer(
    onOpenDevice: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenSettings: () -> Unit,
    content: @Composable (openMenu: () -> Unit) -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    // Opening and closing are animations, so they're suspend functions: they need a coroutine.
    val scope = rememberCoroutineScope()
    val closeThen = { next: () -> Unit ->
        scope.launch { drawerState.close() }
        next()
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            // Material's default sheet is 360 dp, nearly the whole phone; three short entries need far less.
            ModalDrawerSheet(modifier = Modifier.width(220.dp)) {
                Text("Piatra", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                NavigationDrawerItem(
                    label = { Text("Device") },
                    selected = false,
                    onClick = { closeThen(onOpenDevice) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                NavigationDrawerItem(
                    label = { Text("Logs") },
                    selected = false,
                    onClick = { closeThen(onOpenLogs) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                NavigationDrawerItem(
                    label = { Text("Settings") },
                    selected = false,
                    onClick = { closeThen(onOpenSettings) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        },
    ) {
        content { scope.launch { drawerState.open() } }
    }
}
