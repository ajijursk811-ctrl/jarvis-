package app.shilex.android.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.shilex.android.data.KeyVault
import app.shilex.android.tools.GeminiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Masuka's eyes: point the camera at something and she tells you what it is, or pick or share a
 * picture and she tells you what is in it. The picture goes to Gemini (the user's own key) for
 * that one question and is not stored. She never names a real person from their face.
 */
class VisionActivity : ComponentActivity() {
    private val http = OkHttpClient()
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { status -> ttsReady = status == TextToSpeech.SUCCESS }
        val question = intent.getStringExtra(EXTRA_QUESTION).orEmpty()
        val auto = intent.getBooleanExtra(EXTRA_AUTO, false)
        val shared = sharedImage()
        setContent {
            ShilexTheme { VisionScreen(this, shared, question, auto && shared == null) }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    fun speak(text: String) {
        val engine = tts ?: return
        if (!ttsReady || text.isBlank()) return
        val hindi = text.any { it in '\u0900'..'\u097F' }
        engine.setLanguage(if (hindi) Locale("hi", "IN") else Locale.ENGLISH)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vision")
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    /** A picture shared to this app from the gallery, WhatsApp and so on. */
    private fun sharedImage(): Bitmap? {
        if (intent.action != Intent.ACTION_SEND) return null
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return null
        return load(uri)
    }

    fun load(uri: Uri): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize((maxOf(info.size.width, info.size.height) / MAX_SIDE).coerceAtLeast(1))
        }
    }.getOrNull()

    /** Asks Gemini about the picture. Returns the answer, or a short reason it failed. */
    fun analyze(bitmap: Bitmap, question: String): String {
        val key = KeyVault(this).read(KeyVault.GEMINI_KEY) ?: return "Gemini key nahi mili. Masuka Settings mein key daalo."
        val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        val gen = GeminiText.generate(
            http, key, question.ifBlank { DEFAULT_QUESTION }, SYSTEM,
            search = false, maxTokens = 1200, seconds = 60,
            imageBase64 = Base64.encodeToString(jpeg, Base64.NO_WRAP),
        )
        return gen.text ?: "Dekh nahi paayi: ${gen.error}"
    }

    companion object {
        const val EXTRA_QUESTION = "question"
        const val EXTRA_AUTO = "auto"
        private const val MAX_SIDE = 1280
        private const val DEFAULT_QUESTION = "यह क्या है? जो दिख रहा है उसे पहचानो और बताओ."
        private const val SYSTEM =
            "You are the eyes of a phone assistant. Look at the picture and answer the user's question. Reply in the " +
                "user's language: Hindi in Devanagari script if the question is in Hindi or Hinglish, Bangla script for " +
                "Bangla, otherwise English. Be clear and brief (at most six short sentences). Name what you see, read any " +
                "visible text, and say what it is for when that helps. For plants, animals, food, objects, places and " +
                "products give the most likely name and say if you are unsure. Never identify a real person from their " +
                "face: describe only what is visible. For medicines, documents or labels, summarise and remind the user " +
                "to check with a doctor or the original."

        fun shrink(bitmap: Bitmap): Bitmap {
            val scale = minOf(1f, MAX_SIDE / maxOf(bitmap.width, bitmap.height).toFloat())
            if (scale >= 1f) return bitmap
            return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        }
    }
}

@Composable
private fun VisionScreen(activity: VisionActivity, startImage: Bitmap?, startQuestion: String, auto: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var photo by remember { mutableStateOf(startImage) }
    var question by remember { mutableStateOf(startQuestion) }
    var answer by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var back by remember { mutableStateOf(true) }
    var ready by remember { mutableStateOf(false) }
    var warm by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var voiceOn by remember { mutableStateOf(true) }
    var count by remember { mutableStateOf(0) }
    var allowed by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                activity.stopSpeaking()
                answer = ""
                photo = withContext(Dispatchers.IO) { activity.load(uri) }
            }
        }
    }
    // COMPATIBLE draws the preview on a normal view, which shows up reliably inside Compose.
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }
    val capture = remember { ImageCapture.Builder().setFlashMode(ImageCapture.FLASH_MODE_AUTO).build() }

    fun ask() {
        val bitmap = photo ?: return
        if (busy) return
        busy = true
        answer = ""
        activity.stopSpeaking()
        scope.launch {
            val text = withContext(Dispatchers.IO) { activity.analyze(bitmap, question) }
            answer = text
            busy = false
            if (voiceOn) activity.speak(text)
        }
    }

    fun takePhoto() {
        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val turn = image.imageInfo.rotationDegrees.toFloat()
                    val raw = image.toBitmap()
                    image.close()
                    val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(turn) }, true)
                    photo = VisionActivity.shrink(upright)
                    ask()
                }

                override fun onError(exception: ImageCaptureException) {
                    answer = "Photo nahi kheench payi."
                }
            },
        )
    }

    LaunchedEffect(Unit) {
        if (!allowed && photo == null) permission.launch(Manifest.permission.CAMERA)
    }

    DisposableEffect(allowed, back, photo == null) {
        val showing = allowed && photo == null
        if (showing) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    runCatching {
                        val provider = future.get()
                        val preview = Preview.Builder().build()
                        preview.setSurfaceProvider(previewView.surfaceProvider)
                        provider.unbindAll()
                        val bound = provider.bindToLifecycle(
                            activity,
                            if (back) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA,
                            preview,
                            capture,
                        )
                        camera = bound
                        // Let the camera settle its focus and brightness on the middle of the picture.
                        bound.cameraControl.startFocusAndMetering(
                            FocusMeteringAction.Builder(SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f)).build(),
                        )
                        ready = true
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
        }
        onDispose {
            runCatching { camera?.cameraControl?.enableTorch(false) }
            torch = false
            if (showing) runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            ready = false
            camera = null
        }
    }

    // A photo taken the moment the camera starts comes out dark: give it a moment first.
    LaunchedEffect(ready) {
        warm = false
        if (ready) {
            delay(1200)
            warm = true
        }
    }

    // Opened by voice ("ye kya hai?"): count down 3, 2, 1 and take the photo by itself.
    LaunchedEffect(warm, auto) {
        if (auto && warm && photo == null) {
            for (n in 3 downTo 1) {
                count = n
                delay(1000)
            }
            count = 0
            takePhoto()
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF07070D)).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Masuka Vision", color = Color.White, modifier = Modifier.weight(1f))
            TextButton(onClick = { voiceOn = !voiceOn; if (!voiceOn) activity.stopSpeaking() }) {
                Text(if (voiceOn) "Awaaz: on" else "Awaaz: off")
            }
            TextButton(onClick = { activity.finish() }) { Text("Close") }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val shown = photo
            if (shown != null) {
                Image(shown.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else if (allowed) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                if (count > 0) Text("$count", color = Color.White, style = androidx.compose.material3.MaterialTheme.typography.displayLarge)
            } else {
                Text("Camera ki permission chahiye. Dobara koshish karne ke liye Gallery chuno ya permission do.", color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
            }
            if (busy) CircularProgressIndicator()
            if (photo == null && allowed && !ready) {
                Text("Camera taiyar ho raha hai...", color = Color.White, modifier = Modifier.padding(24.dp))
            }
        }
        if (busy) {
            Text("Dekh rahi hoon...", color = Color.White, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        if (answer.isNotEmpty()) {
            Text(
                Romanize.text(answer),
                color = Color(0xFF7CFF9A),
                modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        OutlinedTextField(
            value = question,
            onValueChange = { question = it },
            placeholder = { Text("Sawal (zaruri nahi): ye kya hai?") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (photo == null) {
                Button(onClick = { if (allowed && warm) takePhoto() }) { Text(if (warm) "Photo kheencho" else "Ruko...") }
                TextButton(onClick = { back = !back }) { Text("Flip") }
                TextButton(onClick = {
                    torch = !torch
                    camera?.cameraControl?.enableTorch(torch)
                }) { Text(if (torch) "Light: on" else "Light") }
            } else {
                Button(onClick = { ask() }) { Text("Poochho") }
                TextButton(onClick = {
                    photo = null
                    answer = ""
                    activity.stopSpeaking()
                }) { Text("Nayi photo") }
            }
            TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Text("Gallery")
            }
        }
    }
}
