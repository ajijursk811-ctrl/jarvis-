package app.shilex.android.ui

import android.view.TextureView
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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.shilex.android.assistant.Phase

/**
 * Masuka as three looping videos (idle, thinking, talking), big, from the bottom of the screen
 * up to about the top fifth. The video for the current state fades in while the others fade
 * out, so changes are smooth. Files live in app/src/debug/assets: idle.mp4, thinking.mp4,
 * talking.mp4. Draw it behind the Home screen; it takes no touches.
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
            VideoLayer("idle.mp4", phase == Phase.Off || phase == Phase.Listening)
            VideoLayer("thinking.mp4", phase == Phase.Connecting)
            VideoLayer("talking.mp4", phase == Phase.Speaking)
        }
    }
}

/** An empty spot that keeps the Home layout and the tap-to-talk area where the orb used to be. */
@Composable
fun AvatarSlot(phase: Phase, level: Float, modifier: Modifier = Modifier) {
    Box(modifier)
}

@Composable
private fun VideoLayer(asset: String, visible: Boolean) {
    val context = LocalContext.current
    val fade by animateFloatAsState(if (visible) 1f else 0f, tween(400), label = "fade")
    val player = remember(asset) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri("asset:///$asset"))
            repeatMode = Player.REPEAT_MODE_ALL
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            volume = 0f
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    val shown = fade > 0.01f
    LaunchedEffect(shown) { player.playWhenReady = shown }
    AndroidView(
        factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } },
        modifier = Modifier.fillMaxSize().alpha(fade),
    )
}
