package app.shilex.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shilex.android.assistant.Phase
import app.shilex.android.assistant.VoiceSession
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Live camera: the camera stays on and Masuka sees it like a video call. About one picture a
 * second goes to the live conversation (Gemini, the user's own key) while the user just asks
 * "ye kya hai?" out loud and she answers by voice. Nothing is saved, and she never names a
 * real person from their face.
 */
class LiveCameraActivity : ComponentActivity() {
    val executor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var lastSent = 0L
    private val keepSession by lazy { intent.getBooleanExtra(EXTRA_KEEP_SESSION, false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { ShilexTheme { LiveScreen(this) } }
    }

    override fun onDestroy() {
        executor.shutdown()
        // Opened from the Home screen: closing the camera ends the conversation too.
        if (!keepSession) VoiceSession.get(application).stop("live camera closed")
        super.onDestroy()
    }

    /** Turns each camera frame into a small JPEG and sends about one a second. */
    fun analyzer(): ImageAnalysis.Analyzer = ImageAnalysis.Analyzer { image ->
        try {
            val session = VoiceSession.get(application)
            val now = SystemClock.elapsedRealtime()
            if (session.active && now - lastSent >= FRAME_MS) {
                lastSent = now
                val turn = image.imageInfo.rotationDegrees.toFloat()
                val raw = image.toBitmap()
                val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(turn) }, true)
                val small = shrinkTo(upright, MAX_SIDE)
                val jpeg = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 60, it) }.toByteArray()
                session.sendFrame(jpeg)
            }
        } finally {
            image.close()
        }
    }

    private fun shrinkTo(bitmap: Bitmap, max: Int): Bitmap {
        val scale = minOf(1f, max / maxOf(bitmap.width, bitmap.height).toFloat())
        if (scale >= 1f) return bitmap
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
    }

    companion object {
        const val EXTRA_KEEP_SESSION = "keep_session"
        private const val FRAME_MS = 1_000L
        private const val MAX_SIDE = 640
    }
}

private val SHADOW = Shadow(Color.Black, Offset(0f, 2f), 10f)

@Composable
private fun LiveScreen(activity: LiveCameraActivity) {
    val context = LocalContext.current
    val session = remember { VoiceSession.get(activity.application) }
    val ui by session.state.collectAsStateWithLifecycle()
    var back by remember { mutableStateOf(true) }
    var torch by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var camOk by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var micOk by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.CAMERA] == true) camOk = true
        if (result[Manifest.permission.RECORD_AUDIO] == true) micOk = true
    }
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val analysis = remember {
        ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also {
            it.setAnalyzer(activity.executor, activity.analyzer())
        }
    }

    LaunchedEffect(Unit) {
        val need = listOfNotNull(
            Manifest.permission.CAMERA.takeIf { !camOk },
            Manifest.permission.RECORD_AUDIO.takeIf { !micOk },
        )
        if (need.isNotEmpty()) permissions.launch(need.toTypedArray())
    }

    // Start listening as soon as both the camera and the mic are allowed.
    LaunchedEffect(camOk, micOk) {
        if (camOk && micOk && !session.active) session.start()
    }

    DisposableEffect(camOk, back) {
        if (camOk) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    runCatching {
                        val provider = future.get()
                        val preview = Preview.Builder().build()
                        preview.setSurfaceProvider(previewView.surfaceProvider)
                        provider.unbindAll()
                        camera = provider.bindToLifecycle(
                            activity,
                            if (back) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA,
                            preview,
                            analysis,
                        )
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
        }
        onDispose {
            runCatching { camera?.cameraControl?.enableTorch(false) }
            torch = false
            if (camOk) runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            camera = null
        }
    }

    val status = when {
        !camOk -> "Camera ki permission chahiye"
        !micOk -> "Mic ki permission chahiye"
        ui.phase == Phase.Connecting -> "Connect ho rahi hoon..."
        ui.phase == Phase.Speaking -> "Bol rahi hoon..."
        ui.phase == Phase.Listening -> "Sun rahi hoon: poochho, ye kya hai?"
        else -> "Mic band hai"
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (camOk) AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Masuka Live",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(shadow = SHADOW),
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                status,
                color = Color(0xFF7CFF9A),
                style = MaterialTheme.typography.bodyMedium.copy(shadow = SHADOW),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Box(Modifier.weight(1f))
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                ui.lines.takeLast(3).forEach { line ->
                    Text(
                        Romanize.text(line.text),
                        color = if (line.fromUser) Color(0xFFD9C8FF) else Color.White,
                        style = MaterialTheme.typography.bodyLarge.copy(shadow = SHADOW),
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { back = !back }) { Text("Flip") }
                TextButton(onClick = {
                    torch = !torch
                    camera?.cameraControl?.enableTorch(torch)
                }) { Text(if (torch) "Light: on" else "Light") }
                Box(Modifier.weight(1f))
                TextButton(onClick = { activity.finish() }) { Text("Band karo") }
            }
        }
    }
}
