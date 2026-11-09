package app.shilex.android.orb

import android.content.Context
import android.graphics.PixelFormat
import android.view.WindowManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shilex.android.Diag
import app.shilex.android.assistant.Phase
import app.shilex.android.assistant.VoiceSession
import app.shilex.android.data.Settings

/**
 * A glowing light that runs around the screen edge, over every app. It is dim while the
 * assistant is idle, and bright, spinning and pulsing with the voice while it listens or
 * speaks. Colour and thickness come from Settings. Needs "Display over other apps".
 */
object BorderLight {
    private var host: OverlayHost? = null
    private var colourIndex by mutableIntStateOf(0)
    private var widthDp by mutableIntStateOf(6)

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

    private fun show(context: Context) {
        val windows = context.getSystemService(WindowManager::class.java)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
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
            1 -> listOf(Color(0xFFB794FF), Color(0xFF4F7BFF), Color(0xFFE879C6))
            2 -> listOf(Color(0xFF38D6F5), Color(0xFF4F7BFF), Color(0xFF7C5CFF))
            3 -> listOf(Color(0xFFFF7EB6), Color(0xFFFF4D9D), Color(0xFFB794FF))
            4 -> listOf(Color(0xFF39FF88), Color(0xFF00E0C6), Color(0xFF7CFF4F))
            else -> listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta)
        }
        return base + base.first()
    }
}

@Composable
private fun BorderLayer(active: Boolean, level: Float, colours: List<Color>, widthDp: Int) {
    if (active) {
        val spin = rememberInfiniteTransition(label = "border")
        val angle by spin.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 4000, easing = LinearEasing)),
            label = "angle",
        )
        BorderDraw({ angle }, 0.7f + 0.3f * level.coerceIn(0f, 1f), colours, widthDp)
    } else {
        BorderDraw({ 0f }, 0.3f, colours, widthDp)
    }
}

@Composable
private fun BorderDraw(angle: () -> Float, strength: Float, colours: List<Color>, widthDp: Int) {
    Canvas(Modifier.fillMaxSize()) {
        val core = widthDp.dp.toPx()
        val radius = 44.dp.toPx()
        val brush = Brush.sweepGradient(colours)
        val turn = angle()

        fun ring(width: Float, alpha: Float) {
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

        for (i in 5 downTo 1) ring(core * (1f + i * 0.9f), 0.10f * strength)
        ring(core, strength)
    }
}
