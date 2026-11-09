package app.shilex.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.shilex.android.R
import app.shilex.android.assistant.Phase

/** Masuka's face: her photo in a glowing ring that swells with the voice level. */
@Composable
fun Avatar(phase: Phase, modifier: Modifier = Modifier, level: Float = 0f) {
    val ring = Brush.sweepGradient(
        listOf(Color(0xFFB794FF), Color(0xFF4F7BFF), Color(0xFF4CD3EE), Color(0xFFE879C6), Color(0xFFB794FF)),
    )
    val glow = if (phase == Phase.Off) 0.25f else 0.45f + 0.4f * level
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .scale(1f + 0.04f * level)
                .background(Brush.radialGradient(listOf(Color(0xFFB794FF).copy(alpha = glow), Color.Transparent)), CircleShape),
        )
        Image(
            painter = painterResource(R.drawable.masuka_avatar),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp)
                .scale(1f + 0.03f * level)
                .border(4.dp, ring, CircleShape)
                .padding(4.dp)
                .clip(CircleShape),
        )
    }
}
