package dev.piatra

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piatra.data.Settings
import dev.piatra.ui.PiatraNavHost
import dev.piatra.ui.PiatraTheme
import dev.piatra.ui.isDark
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    // inject(): Koin's way to hand an Activity something (Android builds Activities itself,
    // so there's no constructor to pass it through).
    private val settings: Settings by inject()

    // A list to open, from a tap on a reminder's notification. State, so Compose sees it change.
    private var listToOpen by mutableStateOf<Long?>(null)

    // Android calls onCreate when the screen is first created (and again after rotation).
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // After a rotation (savedInstanceState isn't null) the intent is the old one: already handled.
        if (savedInstanceState == null) listToOpen = listIdFrom(intent)
        // setContent hands the screen over to Compose.
        setContent {
            // null until the settings file has been read (a few milliseconds): draw nothing
            // rather than flash the wrong theme.
            val appearance by settings.appearance.collectAsStateWithLifecycle(initialValue = null)
            val current = appearance ?: return@setContent
            val dark = isDark(current.theme)
            // The status and gesture bars are see-through; their icons must be light on a dark
            // theme and dark on a light one, which follows the app's choice, not the system's.
            LaunchedEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            PiatraTheme(current) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PiatraNavHost(listToOpen = listToOpen, onListOpened = { listToOpen = null })
                }
            }
        }
    }

    // A notification tapped while Piatra is already open (SINGLE_TOP) arrives here instead of
    // starting a new screen.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        listIdFrom(intent)?.let { listToOpen = it }
    }

    private fun listIdFrom(intent: Intent): Long? = intent.getLongExtra(EXTRA_LIST_ID, -1).takeIf { it != -1L }

    companion object {
        const val EXTRA_LIST_ID = "list_id"
    }
}
