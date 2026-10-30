package dev.piatra.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import dev.piatra.android.SyncState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piatra.core.agent.ToolCallJson
import dev.piatra.data.ItemEntity
import dev.piatra.data.ItemKind
import dev.piatra.data.RecordingEntity
import dev.piatra.data.RecordingStatus
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel
import java.util.Locale
import dev.piatra.R

// Connects the ViewModel to the UI. koinViewModel() returns the same instance after
// rotation, and on first use builds it with Koin's recipe.
// Permission handling lives here because it's Android UI work; the ViewModel never sees it.
@Composable
fun MainScreen(
    onOpenMenu: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenRecording: (Long) -> Unit = {},
    viewModel: MainViewModel = koinViewModel()) {
    // Turns each StateFlow into Compose state; recomposes when it changes,
    // and stops listening while the app is in the background.
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lastRecording by viewModel.lastRecording.collectAsStateWithLifecycle()
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val itemsByRecording by viewModel.itemsByRecording.collectAsStateWithLifecycle()
    val listTitles by viewModel.listTitles.collectAsStateWithLifecycle()
    val modelsNotice by viewModel.modelsNotice.collectAsStateWithLifecycle()
    val hasBoard by viewModel.hasBoard.collectAsStateWithLifecycle()
    val sync by viewModel.sync.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // rememberSaveable: like remember, but also survives rotation.
    var micDenied by rememberSaveable { mutableStateOf(false) }

    // Registered up front: the OS shows the dialog and calls this lambda later with the answer.
    // It doesn't start recording: by the time the user answers, they've let go of the button.
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        micDenied = !granted
    }

    // Notifications, for reminders. Reminders are created in the background with no moment to
    // ask, so ask once when the screen opens. ON_RESUME re-checks after a trip to Settings.
    var notificationsAllowed by remember { mutableStateOf(hasNotificationPermission(context)) }
    var askedForNotifications by rememberSaveable { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsAllowed = granted
    }
    LaunchedEffect(Unit) {
        if (!notificationsAllowed && !askedForNotifications && Build.VERSION.SDK_INT >= 33) {
            askedForNotifications = true // rememberSaveable: not again after a rotation
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationsAllowed = hasNotificationPermission(context)
    }

    MainScreenContent(
        state = state,
        lastRecording = lastRecording,
        recordings = recordings,
        itemsByRecording = itemsByRecording,
        listTitles = listTitles,
        micDenied = micDenied,
        notificationsAllowed = notificationsAllowed,
        modelsNotice = modelsNotice,
        hasBoard = hasBoard,
        sync = sync,
        onSyncClick = viewModel::syncNow,
        onDismissSync = viewModel::dismissSync,
        onRecordPress = {
            // Checked on every press: the user can revoke the permission in Settings.
            if (hasMicPermission(context)) {
                micDenied = false
                viewModel.startRecording()
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        onRecordRelease = viewModel::stopRecording,
        onOpenMenu = onOpenMenu,
        onOpenSettings = onOpenSettings,
        onOpenRecording = onOpenRecording,
        onOpenSystemSettings = { openAppSettings(context) },
    )
}

// Stateless: it only draws what it's given and reports presses upward.
// That keeps it easy to preview and reason about.
@Composable
fun MainScreenContent(
    state: RecordingUiState,
    lastRecording: LastRecording?,
    recordings: List<RecordingEntity>,
    itemsByRecording: Map<Long, List<ItemEntity>>,
    listTitles: Map<Long, String>,
    micDenied: Boolean,
    notificationsAllowed: Boolean,
    // Shown with a "Set up" button while a model isn't ready; null hides it.
    modelsNotice: String?,
    onRecordPress: () -> Unit,
    onRecordRelease: () -> Unit,
    // Defaults, so the previews don't have to pass them.
    // The board: a Sync button while one is paired, and a line about the last sync.
    hasBoard: Boolean = false,
    sync: SyncState = SyncState.Idle,
    onSyncClick: () -> Unit = {},
    onDismissSync: () -> Unit = {},
    onOpenMenu: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenRecording: (Long) -> Unit = {},
    onOpenSystemSettings: () -> Unit,
) {
    // The app draws edge-to-edge: behind the status bar and camera cutout at the top and the
    // gesture bar at the bottom. safeDrawingPadding() keeps content (and touches) clear of them.
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        // A title like the other tabs have, with the menu button (Device, Settings, Logs) in front.
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconOnlyButton(R.drawable.ph_list, "Menu", onOpenMenu)
            Text(
                "Recordings",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            )
            if (hasBoard) {
                TextButton(onClick = onSyncClick, enabled = sync !is SyncState.Running) {
                    IconText(R.drawable.ph_arrows_clockwise, "Sync")
                }
            }
        }
        syncLabel(sync)?.let { label ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    color = if (sync is SyncState.Failed) MaterialTheme.colorScheme.error else Color.Unspecified,
                    modifier = Modifier.weight(1f),
                )
                // A result hides by itself after a while (BoardSyncManager.finish); ✕ hides it now.
                if (sync !is SyncState.Running) RemoveButton(onDismissSync, contentDescription = "Hide")
            }
        }
        if (!notificationsAllowed) {
            // A reminder still goes off without this permission, but Android shows nothing.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Notifications are off, so reminders can't alert you.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenSystemSettings) { Text("Open settings") }
            }
        }
        if (modelsNotice != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(modelsNotice, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenSettings) { ForwardText("Set up") }
            }
        }
        if (micDenied) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Piatra needs the microphone to record voice notes. " +
                        "If no dialog appears, allow it in the app's settings.",
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenSystemSettings) { Text("Open settings") }
            }
        }
        HorizontalDivider()
        // A Box stacks its children: the list fills it, and the record button floats on top.
        // weight(1f): take whatever height is left below the title and notices.
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (recordings.isEmpty()) {
                Text(
                    "No recordings yet.",
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    textAlign = TextAlign.Center,
                )
            }
            val listState = rememberLazyListState()
            KeepNewestInView(listState, newestId = recordings.firstOrNull()?.id)
            // LazyColumn only creates the rows that are on screen, so a long list stays fast.
            // The bottom padding lets the last row scroll up clear of the floating button.
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 136.dp),
            ) {
                // key: lets Compose tell rows apart when items are added or deleted.
                items(recordings, key = { it.id }) { recording ->
                    // animateItem: a new row fades in and the others slide down to make room.
                    Column(modifier = Modifier.animateItem()) {
                        // A thin line between recordings (not above the first).
                        if (recording.id != recordings.first().id) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                        RecordingRow(
                            recording = recording,
                            // getOrDefault: recordings that created nothing aren't in the map.
                            items = itemsByRecording.getOrDefault(recording.id, emptyList()),
                            listTitles = listTitles,
                            onOpenClick = { onOpenRecording(recording.id) },
                        )
                    }
                }
            }
            FloatingRecord(
                state = state,
                lastRecording = lastRecording,
                onRecordPress = onRecordPress,
                onRecordRelease = onRecordRelease,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            )
        }
    }
}

// A LazyColumn keeps its place by the first visible row, so a recording added above it stays
// hidden above the top edge. If the list was showing the very top, jump up to the new row.
// SideEffect runs after the new list is composed but before it is laid out, so the scroll
// position still says where the list was before the new row arrived.
@Composable
fun KeepNewestInView(listState: LazyListState, newestId: Long?) {
    // A plain holder rather than state: changing it shouldn't redraw anything.
    val shownNewestId = remember { longArrayOf(newestId ?: -1) }
    SideEffect {
        val id = newestId ?: -1
        if (id == shownNewestId[0]) return@SideEffect
        shownNewestId[0] = id
        if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) {
            // Applied at the next layout, instead of keeping the old first row in place.
            listState.requestScrollToItem(0)
        }
    }
}

// The record button floating over the bottom of the list, with a small label above it about
// the last recording ("Saved 3 s", "Too short").
@Composable
fun FloatingRecord(
    state: RecordingUiState,
    lastRecording: LastRecording?,
    onRecordPress: () -> Unit,
    onRecordRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val label = when (lastRecording) {
            null -> null
            is LastRecording.Saved -> "Saved ${formatSeconds(lastRecording.durationMillis)}, ${lastRecording.sizeBytes / 1024} KB"
            is LastRecording.TooShort -> "Too short (${formatSeconds(lastRecording.durationMillis)}), not saved. Hold while you speak."
            is LastRecording.Failed -> "Recording failed: ${lastRecording.message}"
        }
        if (label != null) {
            // A pill with a slight shadow, so it reads over the list behind it.
            Surface(shape = CircleShape, shadowElevation = 2.dp, tonalElevation = 2.dp) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (lastRecording is LastRecording.Failed) MaterialTheme.colorScheme.error else Color.Unspecified,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        HoldToRecordButton(
            // Inside this check Kotlin knows state is a Recording ("smart cast"),
            // so state.startedAtMillis is available.
            startedAtMillis = if (state is RecordingUiState.Recording) state.startedAtMillis else null,
            onPress = onRecordPress,
            onRelease = onRecordRelease,
        )
    }
}

// One recording in the list: when, how long, its status and what it created. Long text stops
// after a few lines with "…"; Play, Delete and the full text are on the recording's own screen.
// Tapping anywhere on the row, items included, opens the recording's own screen.
@Composable
fun RecordingRow(
    modifier: Modifier = Modifier,
    recording: RecordingEntity,
    items: List<ItemEntity>,
    listTitles: Map<Long, String>,
    onOpenClick: () -> Unit = {},
) {
    // ListItem: Material's standard list row with slots for each part.
    ListItem(
        modifier = modifier.clickable(onClick = onOpenClick),
        headlineContent = { Text(formatDateTime(recording.createdAt)) },
        supportingContent = {
            Column {
                Text("${formatSeconds(recording.durationMillis)} · ${statusLabel(recording)}")
                // The transcript, once there is one.
                recording.transcript?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurface, maxLines = LIST_MAX_LINES, overflow = TextOverflow.Ellipsis)
                }
                RecordingOutcome(recording, items, listTitles, onItemClick = null, maxLines = LIST_MAX_LINES)
            }
        },
    )
}

// What a recording led to: the items the agent created (list items can be tapped to tick
// them off). On the recording's own screen also the decision lines, which are there to judge the agent.
@Composable
fun RecordingOutcome(
    recording: RecordingEntity,
    items: List<ItemEntity>,
    listTitles: Map<Long, String>,
    // Set on the recording's own screen: tapping a note or list item opens it (its note screen,
    // its list). Not in the Recordings list, where a tap opens the recording. Ticking off is done
    // in Lists only (it happened by accident here).
    onItemClick: ((ItemEntity) -> Unit)?,
    // Set on the recording's own screen: each item gets a ✕ to remove it.
    onRemoveItem: ((ItemEntity) -> Unit)? = null,
    showDecisions: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
) {
    // What the agent made from it, e.g. "Created note: buy milk".
    for (item in items) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Alarms and timers live in the Clock app: nothing here to open.
            val tappable = onItemClick != null &&
                (item.kind == ItemKind.NOTE || (item.kind == ItemKind.LIST_ITEM && item.listId != null))
            ItemLine(
                item, listTitles, maxLines,
                // weight(1f): the text takes the room the ✕ leaves.
                modifier = Modifier.weight(1f).then(if (tappable) Modifier.clickable { onItemClick?.invoke(item) } else Modifier),
            )
            if (onRemoveItem != null) {
                RemoveButton({ onRemoveItem(item) })
            }
        }
    }
    val calls = agentCalls(recording)
    if (showDecisions && calls.isNotEmpty()) {
        Text(
            "What the agent decided",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        // Tool by tool: 🤖 the agent model, 📏 a shortcut rule, 🔧 a correction of the model.
        for (call in calls) {
            Text(
                "${deciderIcon(call.decidedBy)} ${ToolCallJson.describe(call)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// One item as "Created reminder: Call mom · Todo · 23/10/2026, 17:00": the label small and grey,
// the item in the accent colour (struck through once ticked off), so it's clear where each starts.
// An AnnotatedString is one piece of text with differently styled parts, so it wraps like normal text.
@Composable
fun ItemLine(item: ItemEntity, listTitles: Map<Long, String>, maxLines: Int = Int.MAX_VALUE, modifier: Modifier = Modifier) {
    val labelStyle = SpanStyle(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = MaterialTheme.typography.labelMedium.fontSize,
    )
    val itemStyle = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        textDecoration = if (item.done) TextDecoration.LineThrough else null,
    )
    Text(
        buildAnnotatedString {
            withStyle(labelStyle) { append(itemKindLabel(item) + ": ") }
            withStyle(itemStyle) { append(itemText(item, listTitles)) }
        },
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

// The time since recording started, ticking in tenths of a second.
@Composable
fun RecordingTimer(startedAtMillis: Long, color: Color) {
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // LaunchedEffect runs a coroutine while this composable is on screen, and cancels it when
    // it leaves (recording stopped). Each tick updates nowMillis, which recomposes the Text.
    // (Same time source as SystemClock, which stamped startedAtMillis.)
    LaunchedEffect(startedAtMillis) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(50) // often enough that the tenths digit moves smoothly
        }
    }
    Text(
        formatElapsed(nowMillis - startedAtMillis),
        color = color,
        // "tnum": digits all the same width, so the timer doesn't wobble as it counts.
        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
    )
}

// A round button that reports when it's pressed down and when it's let go.
// (A normal Button only reports a finished tap, which isn't enough for hold-to-record.)
// startedAtMillis: null while idle; while recording, the button turns a deeper red and shows the timer.
@Composable
fun HoldToRecordButton(startedAtMillis: Long?, onPress: () -> Unit, onRelease: () -> Unit) {
    // pointerInput below starts once and keeps running, so it would keep calling the
    // callbacks from the first composition. rememberUpdatedState always holds the latest ones.
    val currentOnPress by rememberUpdatedState(onPress)
    val currentOnRelease by rememberUpdatedState(onRelease)
    val isRecording = startedAtMillis != null
    // Red in every theme, so the button stands out. White text reads on both reds.
    val color = if (isRecording) RecordRedPressed else RecordRed
    val textColor = Color.White

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(96.dp)
            // shadow before clip: the shadow is drawn outside the circle, so it isn't clipped away.
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(color)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        currentOnPress()
                        // Suspends until the finger lifts (or the gesture is cancelled, e.g. the app is left).
                        tryAwaitRelease()
                        currentOnRelease()
                    },
                )
            },
    ) {
        if (startedAtMillis != null) {
            RecordingTimer(startedAtMillis, textColor)
        } else {
            Text(
                "Hold to\nrecord",
                color = textColor,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// How many lines of a transcript or item the Recordings list shows before "…".
private const val LIST_MAX_LINES = 5

private val RecordRed = Color(0xFFD32F2F)
private val RecordRedPressed = Color(0xFF9A0007)

// Before Android 13, apps could always post notifications.
private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun hasMicPermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

// Opens this app's page in system Settings, where the user can allow the microphone
// after Android has stopped showing the dialog (two denials).
private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
    context.startActivity(intent)
}

// 67_340 -> "1:07.3"
private fun formatElapsed(millis: Long): String {
    val tenths = millis.coerceAtLeast(0) / 100
    val totalSeconds = tenths / 10
    return String.format(Locale.getDefault(), "%d:%02d.%d", totalSeconds / 60, totalSeconds % 60, tenths % 10)
}

private val previewRecordings = listOf(
    RecordingEntity(
        id = 3, fileId = "c", createdAt = 1_792_571_100_000, durationMillis = 3100,
        status = RecordingStatus.TRANSCRIBING,
    ),
    RecordingEntity(
        id = 2, fileId = "b", createdAt = 1_792_571_003_000, durationMillis = 4200,
        status = RecordingStatus.DONE, transcript = "Note, buy milk and eggs.",
        agentResult = """[{"name":"create_note","arguments":{"text":"buy milk and eggs."}}]""",
    ),
    RecordingEntity(
        id = 1, fileId = "a", createdAt = 1_792_570_862_000, durationMillis = 2000,
        status = RecordingStatus.FAILED, error = "Audio file missing",
    ),
)

private val previewItems = mapOf(
    2L to listOf(
        ItemEntity(id = 1, kind = ItemKind.NOTE, text = "buy milk and eggs.", createdAt = 1_792_571_005_000, recordingId = 2),
        ItemEntity(
            id = 2, kind = ItemKind.LIST_ITEM, text = "Call mom", createdAt = 1_792_571_005_000, recordingId = 2,
            dueAt = 1_792_605_600_000, listId = 1,
        ),
        ItemEntity(
            id = 3, kind = ItemKind.ALARM, text = "Gym", createdAt = 1_792_571_005_000, recordingId = 2,
            dueAt = 1_792_652_400_000,
        ),
    ),
)

@Preview
@Composable
fun MainScreenIdlePreview() {
    MaterialTheme {
        Surface {
            MainScreenContent(
                state = RecordingUiState.Idle,
                lastRecording = LastRecording.Saved(durationMillis = 4200, sizeBytes = 134_400),
                recordings = previewRecordings,
                itemsByRecording = previewItems,
                listTitles = mapOf(1L to "Todo"),
                micDenied = false,
                notificationsAllowed = true,
                modelsNotice = null,
                onRecordPress = {},
                onRecordRelease = {},
                onOpenSystemSettings = {},
            )
        }
    }
}

@Preview
@Composable
fun MainScreenRecordingPreview() {
    MaterialTheme {
        Surface {
            MainScreenContent(
                state = RecordingUiState.Recording(startedAtMillis = System.currentTimeMillis() - 7_000),
                lastRecording = null,
                recordings = emptyList(),
                itemsByRecording = emptyMap(),
                listTitles = emptyMap(),
                micDenied = false,
                notificationsAllowed = true,
                modelsNotice = null,
                onRecordPress = {},
                onRecordRelease = {},
                onOpenSystemSettings = {},
            )
        }
    }
}

@Preview
@Composable
fun MainScreenMicDeniedPreview() {
    MaterialTheme {
        Surface {
            MainScreenContent(
                state = RecordingUiState.Idle,
                lastRecording = LastRecording.TooShort(durationMillis = 300),
                recordings = previewRecordings,
                itemsByRecording = previewItems,
                listTitles = mapOf(1L to "Todo"),
                micDenied = true,
                notificationsAllowed = false,
                modelsNotice = "Downloading models…",
                onRecordPress = {},
                onRecordRelease = {},
                onOpenSystemSettings = {},
            )
        }
    }
}
