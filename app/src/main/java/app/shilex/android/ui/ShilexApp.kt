package app.shilex.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shilex.android.assistant.AssistantViewModel
import app.shilex.android.assistant.UiState
import app.shilex.android.local.ActivityLog
import app.shilex.android.local.LocalPhase
import app.shilex.android.task.Task

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Home),
    Automations("Automations", Icons.Filled.Star),
    Activity("Activity", Icons.Filled.List),
    Settings("Settings", Icons.Filled.Settings),
}

@Composable
fun ShilexApp(assistant: AssistantViewModel) {
    val state by assistant.state.collectAsStateWithLifecycle()
    val localPhase by assistant.localPhase.collectAsStateWithLifecycle()
    val activity by assistant.activity.collectAsStateWithLifecycle()
    val wakeWord by assistant.wakeWord.collectAsStateWithLifecycle()
    val level by assistant.level.collectAsStateWithLifecycle()
    val task by assistant.task.collectAsStateWithLifecycle()
    var onboarded by rememberSaveable { mutableStateOf(assistant.onboarded) }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    ShilexTheme {
        Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
            Content(assistant, state, localPhase, level, task, activity, wakeWord, onboarded, { onboarded = true }, tab) { tab = it }
        }
    }
}

@Composable
private fun Content(
    assistant: AssistantViewModel,
    state: UiState,
    localPhase: LocalPhase,
    level: Float,
    task: Task?,
    activity: List<ActivityLog.Entry>,
    wakeWord: Boolean,
    onboarded: Boolean,
    onOnboarded: () -> Unit,
    tab: Int,
    setTab: (Int) -> Unit,
) {
    if (!onboarded || !state.hasKey) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            Onboarding(assistant, state.hasKey) {
                assistant.onboarded = true
                onOnboarded()
            }
        }
        return
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
                Tab.entries.forEach {
                    NavigationBarItem(
                        selected = tab == it.ordinal,
                        onClick = { setTab(it.ordinal) },
                        icon = { Icon(it.icon, contentDescription = null) },
                        label = { Text(it.label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
                        if (tab == Tab.Home.ordinal) {
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(app.shilex.android.R.drawable.masuka_bg), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                                AvatarVideo(state.phase, level)
            }
            when (Tab.entries[tab]) {
                Tab.Home -> HomeScreen(
                    state = state,
                    localPhase = localPhase,
                    level = level,
                    task = task,
                    activity = activity,
                    wakeOn = assistant.wakeWordAvailable && wakeWord,
                    onStart = assistant::start,
                    onStop = assistant::stop,
                    onInterrupt = assistant::interrupt,
                    onMicDenied = assistant::micUnavailable,
                    onPermissionAnswered = assistant::permissionAnswered,
                    onQuick = assistant::quick,
                    onSeeActivity = { setTab(Tab.Activity.ordinal) },
                    favourites = remember(activity.size) { assistant.favourites() },
                )
                Tab.Automations -> AutomationsScreen()
                Tab.Activity -> ActivityScreen(activity, assistant::clearActivity)
                Tab.Settings -> SettingsScreen(assistant)
            }
        }
    }
}
