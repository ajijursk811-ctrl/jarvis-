package app.shilex.android.orb

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.RoundedCorner
import android.view.WindowManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shilex.android.Diag
import app.shilex.android.assistant.Phase
import app.shilex.android.assistant.VoiceSession
import app.shilex.android.data.Settings
import kotlinx.coroutines.delay

/**
 * A glowing light that runs around the very edge of the screen (status bar and navigation
 * bar included), over every app. It follows the phone's own rounded corners. It is dim while
 * the assistant is idle, and bright, spinning and pulsing with the voice while it listens or
 * speaks. Colour and thickness come from Settings. Needs "Display over other apps".
 */
object BorderLight {
    private var host: OverlayHost? = null
    private var colourIndex by mutableIntStateOf(0)
    private var widthDp by mutableIntStateOf(10)

    val colourNames = listOf("Rainbow", "Violet", "Blue", "Pink", "Green")

    /** Shows, hides or restyles the border to match the settings and the permission. */
    fun sync(context: Context) {
        val app = context.applicationContext
        val settings = Settings(app)
        colourIndex = settings.borderColor
        widthDp = settings.borderSize
        val want = settings.borderLight && FloatingOrb.allowed(app)
        if (want && host == null) show(app) else if (!want && host != null) hide(app)
    }

    @Suppress("DEPRECATION")
    private fun show(context: Context) {
        val windows = context.getSystemService(WindowManager::class.java)
        val screen = DisplayMetrics().also { windows.defaultDisplay.getRealMetrics(it) }
        val params = WindowManager.LayoutParams(
            screen.widthPixels,
            screen.heightPixels,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        val overlay = OverlayHost(context)
        overlay.open {
            val session = VoiceSession.get(context)
            val state by session.state.collectAsStateWithLifecycle()
            val level by session.level.collectAsStateWithLifecycle()
            BorderLayer(state.phase != Phase.Off, level, palette(colourIndex), widthDp)
        }
        runCatching { windows.addView(overlay.view, params) }
            .onSuccess {
                host = overlay
                Diag.i("border: shown")
            }
            .onFailure { Diag.w("border: couldn't show (${it.javaClass.simpleName})") }
    }

    private fun hide(context: Context) {
        val overlay = host ?: return
        runCatching { context.getSystemService(WindowManager::class.java).removeView(overlay.view) }
        overlay.close()
        host = null
        Diag.i("border: hidden")
    }

    private fun palette(index: Int): List<Color> {
        val base = when (index) {
            1 -> listOf(Color(0xFFB794FF), Color(0xFF6D7CFF), Color(0xFFE879C6), Color(0xFF8E6BFF))
            2 -> listOf(Color(0xFF38D6F5), Color(0xFF4F7BFF), Color(0xFF7C5CFF), Color(0xFF38D6F5))
            3 -> listOf(Color(0xFFFF7EB6), Color(0xFFFF4D9D), Color(0xFFB794FF), Color(0xFFFF9BC8))
            4 -> listOf(Color(0xFF39FF88), Color(0xFF00E0C6), Color(0xFF7CFF4F), Color(0xFF1FD6A0))
            else -> listOf(
                Color(0xFFFF2D55), Color(0xFFFF9500), Color(0xFFFFE600), Color(0xFF34FF6A),
                Color(0xFF00E5FF), Color(0xFF3D5AFF), Color(0xFFB04DFF), Color(0xFFFF2DAA),
            )
        }
        return base + base.first()
    }
}

@Composable
private fun BorderLayer(active: Boolean, level: Float, colours: List<Color>, widthDp: Int) {
    val view = LocalView.current
    val fallback = with(LocalDensity.current) { 48.dp.toPx() }
    var corner by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(view) {
        delay(400)
        if (Build.VERSION.SDK_INT >= 31) {
            view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.let { corner = it.radius.toFloat() }
        }
    }
    val radius = if (corner > 0f) corner else fallback
    if (active) {
        val spin = rememberInfiniteTransition(label = "border")
        val angle by spin.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 3500, easing = LinearEasing)),
            label = "angle",
        )
        BorderDraw({ angle }, 0.75f + 0.25f * level.coerceIn(0f, 1f), colours, widthDp, radius)
    } else {
        BorderDraw({ 0f }, 0.45f, colours, widthDp, radius)
    }
}

@Composable
private fun BorderDraw(angle: () -> Float, strength: Float, colours: List<Color>, widthDp: Int, radius: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val core = widthDp.dp.toPx()
        val colour = Brush.sweepGradient(colours)
        val white = SolidColor(Color.White)
        val turn = angle()

        fun ring(width: Float, alpha: Float, brush: Brush) {
            val path = Path().apply {
                fillType = PathFillType.EvenOdd
                addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius)))
                addRoundRect(
                    RoundRect(
                        width, width, size.width - width, size.height - width,
                        CornerRadius((radius - width).coerceAtLeast(0f)),
                    ),
                )
            }
            clipPath(path) {
                rotate(turn, pivot = center) {
                    drawRect(
                        brush,
                        topLeft = Offset(-size.width, -size.height),
                        size = Size(size.width * 3f, size.height * 3f),
                        alpha = alpha,
                    )
                }
            }
        }

        // soft glow that fades towards the middle, then the bright colour line, then a thin white edge
        for (i in 9 downTo 1) ring(core * (1f + i * 0.45f), 0.05f * strength, colour)
        ring(core, 0.9f * strength, colour)
        ring(core * 0.28f, 0.8f * strength, white)
    }
}
