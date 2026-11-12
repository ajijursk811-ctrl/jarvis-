package app.shilex.android.ui

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.widget.ImageView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.shilex.android.assistant.Phase

private const val IDLE = "idle.webp"
private const val THINKING = "thinking.webp"
private const val TALKING = "talking.webp"

/**
 * Masuka as three looping animations with a transparent background (idle, thinking, talking),
 * big, from the bottom of the screen up to about the top fifth, so she stands on the wallpaper.
 * The animation for the current state fades in while the others fade out. Files live in
 * app/src/debug/assets: idle.webp, thinking.webp, talking.webp. A missing one falls back to idle.
 * Draw it behind the Home screen; it takes no touches.
 */
@Composable
fun AvatarVideo(phase: Phase, level: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .align(Alignment.BottomCenter),
        ) {
            AvatarSprite(phase, Modifier.fillMaxSize())
        }
    }
}

/**
 * The character on her own (no frame, no background): the animation for the current state,
 * fading between states. Used on the Home screen and in the floating character over other apps.
 */
@Composable
fun AvatarSprite(phase: Phase, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val present = remember { context.assets.list("")?.toSet().orEmpty() }
    val wanted = when (phase) {
        Phase.Speaking -> TALKING
        Phase.Connecting -> THINKING
        else -> IDLE
    }
    val current = if (wanted in present) wanted else IDLE
    Box(modifier) {
        listOf(IDLE, THINKING, TALKING).filter { it in present }.forEach { name ->
            AnimatedLayer(name, name == current)
        }
    }
}

/** An empty spot that keeps the Home layout and the tap-to-talk area where the orb used to be. */
@Composable
fun AvatarSlot(phase: Phase, level: Float, modifier: Modifier = Modifier) {
    Box(modifier)
}

@Composable
private fun AnimatedLayer(asset: String, visible: Boolean) {
    val context = LocalContext.current
    val fade by animateFloatAsState(if (visible) 1f else 0f, tween(400), label = "fade")
    val drawable = remember(asset) {
        runCatching {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.assets, asset)) as? AnimatedImageDrawable
        }.getOrNull()
    }
    DisposableEffect(drawable) { onDispose { drawable?.stop() } }
    val shown = fade > 0.01f
    LaunchedEffect(shown, drawable) {
        drawable?.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        if (shown) drawable?.start() else drawable?.stop()
    }
    AndroidView(
        factory = { ctx ->
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(drawable)
            }
        },
        modifier = Modifier.fillMaxSize().alpha(fade),
    )
}
