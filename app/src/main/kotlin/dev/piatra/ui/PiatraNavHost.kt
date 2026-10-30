package dev.piatra.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

// Which screen is showing. The NavController keeps the back stack: the screens opened so far,
// newest on top. navigate() puts one on top; popBackStack() (or the phone's back gesture,
// handled for us) takes it off again. NavHost shows whatever is on top.
// listToOpen: a list to show on top (a reminder's notification was tapped); onListOpened clears it.
@Composable
fun PiatraNavHost(listToOpen: Long?, onListOpened: () -> Unit) {
    val navController = rememberNavController()
    // LaunchedEffect runs when listToOpen changes, after the NavHost below exists.
    LaunchedEffect(listToOpen) {
        if (listToOpen != null) {
            navController.navigate(ListDetail(listToOpen))
            onListOpened()
        }
    }
    val openRecording = { id: Long -> navController.navigate(RecordingDetail(id)) }
    // Switching tabs: keep one copy of each tab on the back stack, above Home. Back from any tab
    // goes to Home, and Back from Home leaves the app. saveState/restoreState: a tab comes back
    // scrolled where you left it.
    val selectTab = { tab: Tab ->
        navController.navigate(tab.route) {
            popUpTo<Home> { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    NavHost(
        navController = navController,
        startDestination = Home,
        // Slide instead of the default slow fade: a new screen comes in from the right and
        // pushes the old one left; going back reverses it. tween(SLIDE_MILLIS): how long it takes.
        // Between two tabs there's no animation: the tab bar stays put and the content swaps.
        enterTransition = { if (isTabSwitch()) EnterTransition.None else slideIntoContainer(SlideDirection.Start, tween(SLIDE_MILLIS)) },
        exitTransition = { if (isTabSwitch()) ExitTransition.None else slideOutOfContainer(SlideDirection.Start, tween(SLIDE_MILLIS)) },
        popEnterTransition = { if (isTabSwitch()) EnterTransition.None else slideIntoContainer(SlideDirection.End, tween(SLIDE_MILLIS)) },
        popExitTransition = { if (isTabSwitch()) ExitTransition.None else slideOutOfContainer(SlideDirection.End, tween(SLIDE_MILLIS)) },
        // The back gesture has its own pair (by default a shrink-and-fade that follows the finger).
        // Use the same slide, so dragging back looks like the Back button. (The edge you swiped
        // from is passed in; it doesn't matter here.)
        predictivePopEnterTransition = { _ ->
            if (isTabSwitch()) EnterTransition.None else slideIntoContainer(SlideDirection.End, tween(SLIDE_MILLIS))
        },
        predictivePopExitTransition = { _ ->
            if (isTabSwitch()) ExitTransition.None else slideOutOfContainer(SlideDirection.End, tween(SLIDE_MILLIS))
        },
    ) {
        // One block per screen. A ViewModel made inside a block (koinViewModel()) belongs to
        // that screen's place on the back stack, and is cleared when the screen is closed.
        composable<Home> {
            val openSettings = { navController.navigate(AppSettings) }
            MenuDrawer(
                onOpenDevice = { navController.navigate(Device) },
                onOpenLogs = { navController.navigate(Logs) },
                onOpenSettings = openSettings,
            ) { openMenu ->
                TabScaffold(Tab.RECORDINGS, selectTab) {
                    MainScreen(onOpenMenu = openMenu, onOpenSettings = openSettings, onOpenRecording = openRecording)
                }
            }
        }
        composable<Device> {
            DeviceScreen(onBack = { navController.popBackStack() })
        }
        composable<Logs> {
            LogsScreen(onBack = { navController.popBackStack() })
        }
        composable<Lists> {
            TabScaffold(Tab.LISTS, selectTab) {
                ListsScreen(onOpenList = { id -> navController.navigate(ListDetail(id)) })
            }
        }
        composable<Notes> {
            TabScaffold(Tab.NOTES, selectTab) {
                NotesScreen(onOpenNote = { id -> navController.navigate(NoteDetail(id)) })
            }
        }
        composable<ListDetail> {
            ListDetailScreen(onBack = { navController.popBackStack() }, onOpenRecording = openRecording)
        }
        composable<NoteDetail> {
            NoteDetailScreen(onBack = { navController.popBackStack() }, onOpenRecording = openRecording)
        }
        composable<AppSettings> {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenShortcuts = { navController.navigate(ShortcutRules) },
                onOpenLab = { navController.navigate(Lab) },
            )
        }
        composable<ShortcutRules> {
            ShortcutRulesScreen(onBack = { navController.popBackStack() })
        }
        composable<Lab> {
            LabScreen(onBack = { navController.popBackStack() })
        }
        composable<RecordingDetail> {
            RecordingDetailScreen(
                onBack = { navController.popBackStack() },
                onOpenNote = { id -> navController.navigate(NoteDetail(id)) },
                onOpenList = { id -> navController.navigate(ListDetail(id)) },
            )
        }
    }
}

private const val SLIDE_MILLIS = 200

// True when going from one tab to another (both ends show the tab bar).
private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch(): Boolean =
    initialState.destination.isTab() && targetState.destination.isTab()
