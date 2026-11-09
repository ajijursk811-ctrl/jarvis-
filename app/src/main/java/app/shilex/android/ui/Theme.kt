package app.shilex.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Dark = darkColorScheme(
    primary = Color(0xFF4CD3EE),
    onPrimary = Color(0xFF00222B),
    primaryContainer = Color(0xFF0D3440),
    onPrimaryContainer = Color(0xFFB8EEFA),
    secondary = Color(0xFF9DB0C8),
    tertiary = Color(0xFFFFB454),
    background = Color(0xFF0A0E15),
    onBackground = Color(0xFFE7ECF3),
    surface = Color(0xFF0A0E15),
    onSurface = Color(0xFFE7ECF3),
    surfaceVariant = Color(0xFF151C28),
    onSurfaceVariant = Color(0xFF8E9AAE),
    surfaceContainer = Color(0xFF111723),
    outline = Color(0xFF2A3445),
    outlineVariant = Color(0xFF1F2836),
    error = Color(0xFFFF8A80),
)
private val Masuka = darkColorScheme(
    primary = Color(0xFFB794FF),
    onPrimary = Color(0xFF1B0B3F),
    primaryContainer = Color(0xFF2E1B66),
    onPrimaryContainer = Color(0xFFE9DDFF),
    secondary = Color(0xFF9FA8FF),
    tertiary = Color(0xFFFF7EB6),
    background = Color(0xFF0A0620),
    onBackground = Color(0xFFEDE8FF),
    surface = Color(0xFF0A0620),
    onSurface = Color(0xFFEDE8FF),
    surfaceVariant = Color(0xFF1B1240),
    onSurfaceVariant = Color(0xFFA99FD6),
    surfaceContainer = Color(0xFF150D33),
    outline = Color(0xFF3F3280),
    outlineVariant = Color(0xFF2A2056),
    error = Color(0xFFFF8A80),
)
private val Light = lightColorScheme(
    primary = Color(0xFF00708A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD4F3FA),
    onPrimaryContainer = Color(0xFF00313D),
    secondary = Color(0xFF52627A),
    tertiary = Color(0xFFA35F00),
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF111722),
    surface = Color(0xFFF5F7FA),
    onSurface = Color(0xFF111722),
    surfaceVariant = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF5B677A),
    surfaceContainer = Color(0xFFFFFFFF),
    outline = Color(0xFFD5DCE6),
    outlineVariant = Color(0xFFE4E9F0),
    error = Color(0xFFB3261E),
)

private val Type = Typography(
    displaySmall = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
)

private val Corners = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

/** Follows the phone's light or dark setting. */
@Composable
fun ShilexTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Masuka,
        typography = Type,
        shapes = Corners,
        content = content,
    )
}

// Short names the screens use; they follow light and dark mode.
val Accent: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
val Background: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.background
val Surface: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceVariant
val Muted: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
val Warning: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.tertiary
