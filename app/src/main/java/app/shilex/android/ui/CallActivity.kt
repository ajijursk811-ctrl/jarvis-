package app.shilex.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shilex.android.Branding
import app.shilex.android.R
import app.shilex.android.assistant.Phase
import app.shilex.android.assistant.VoiceSession
import kotlinx.coroutines.delay

/**
 * A voice call with Masuka: she fills the screen, there is a call timer, and "End call" ends
 * the conversation. It is the same live voice conversation as the Home screen's mic, in a call
 * look. Nothing is recorded or saved.
 */
class CallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { ShilexTheme { CallScreen(this) } }
    }

    override fun onDestroy() {
        VoiceSession.get(application).stop("call ended")
        super.onDestroy()
    }
}

private val CALL_SHADOW = Shadow(Color.Black, Offset(0f, 2f), 12f)

@Composable
private fun CallScreen(activity: CallActivity) {
    val context = LocalContext.current
    val session = remember { VoiceSession.get(activity.application) }
    val ui by session.state.collectAsStateWithLifecycle()
    var micOk by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micOk = it }
    var startedAt by remember { mutableStateOf(0L) }
    var seconds by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        if (!micOk) ask.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(micOk) {
        if (micOk && !session.active) session.start()
    }
    // The timer starts when she picks up (first time she listens or speaks).
    LaunchedEffect(ui.phase) {
        if ((ui.phase == Phase.Listening || ui.phase == Phase.Speaking) && startedAt == 0L) {
            startedAt = SystemClock.elapsedRealtime()
        }
    }
    LaunchedEffect(startedAt) {
        if (startedAt != 0L) {
            while (true) {
                seconds = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
                delay(1000)
            }
        }
    }

    val status = when {
        !micOk -> "Mic ki permission chahiye"
        ui.phase == Phase.Connecting -> "Calling..."
        ui.phase == Phase.Speaking -> "Bol rahi hoon"
        ui.phase == Phase.Listening -> "Sun rahi hoon"
        startedAt != 0L -> "Call khatam"
        else -> "Calling..."
    }
    val timer = "%02d:%02d".format(seconds / 60, seconds % 60)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Image(painterResource(R.drawable.masuka_bg), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        AvatarSprite(ui.phase, Modifier.fillMaxSize().padding(top = 110.dp, bottom = 190.dp))
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                Branding.ASSISTANT_NAME,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall.copy(shadow = CALL_SHADOW),
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                if (startedAt != 0L && ui.phase != Phase.Off) "$status  $timer" else status,
                color = Color(0xFF7CFF9A),
                style = MaterialTheme.typography.bodyLarge.copy(shadow = CALL_SHADOW),
            )
            Box(Modifier.weight(1f))
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ui.lines.takeLast(2).forEach { line ->
                    Text(
                        Romanize.text(line.text),
                        color = if (line.fromUser) Color(0xFFD9C8FF) else Color.White,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge.copy(shadow = CALL_SHADOW),
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
            Button(
                onClick = { activity.finish() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935), contentColor = Color.White),
                modifier = Modifier.padding(bottom = 24.dp, top = 8.dp),
            ) { Text("End call") }
        }
    }
}
