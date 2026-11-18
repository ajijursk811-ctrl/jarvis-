package app.shilex.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
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

/** Which look the user picked in Settings. App sets it at start and Settings changes it live. */
object ThemePrefs {
    var index: Int by mutableIntStateOf(0)
    val names = listOf("Violet night", "Ocean", "Rose", "Forest", "Sunset")
}

private fun night(
    primary: Long, onPrimary: Long, container: Long, onContainer: Long, secondary: Long, tertiary: Long,
    background: Long, text: Long, variant: Long, muted: Long, raised: Long, outline: Long, outlineVariant: Long,
): ColorScheme = darkColorScheme(
    primary = Color(primary),
    onPrimary = Color(onPrimary),
    primaryContainer = Color(container),
    onPrimaryContainer = Color(onContainer),
    secondary = Color(secondary),
    tertiary = Color(tertiary),
    background = Color(background),
    onBackground = Color(text),
    surface = Color(background),
    onSurface = Color(text),
    surfaceVariant = Color(variant),
    onSurfaceVariant = Color(muted),
    surfaceContainer = Color(raised),
    outline = Color(outline),
    outlineVariant = Color(outlineVariant),
    error = Color(0xFFFF8A80),
)

private val Themes: List<ColorScheme> = listOf(
    night(0xFFB794FF, 0xFF1B0B3F, 0xFF2E1B66, 0xFFE9DDFF, 0xFF9FA8FF, 0xFFFF7EB6, 0xFF0A0620, 0xFFEDE8FF, 0xFF1B1240, 0xFFA99FD6, 0xFF150D33, 0xFF3F3280, 0xFF2A2056),
    night(0xFF5BC8FF, 0xFF00263A, 0xFF0A3A55, 0xFFD3F0FF, 0xFF7FA8FF, 0xFF6FFFE0, 0xFF04101C, 0xFFE6F4FF, 0xFF0C2236, 0xFF9BB8CF, 0xFF081A2B, 0xFF2A4C6B, 0xFF1B3550),
    night(0xFFFF8CC6, 0xFF3F0A26, 0xFF5E1B3F, 0xFFFFD9EA, 0xFFFFA8A8, 0xFFFFC857, 0xFF160610, 0xFFFFEAF4, 0xFF2B1020, 0xFFD9A8C2, 0xFF21101B, 0xFF6B3452, 0xFF482338),
    night(0xFF7CE0A0, 0xFF06301A, 0xFF14502E, 0xFFD2FFE2, 0xFFB4E87C, 0xFFFFD36B, 0xFF05120A, 0xFFE8FFF0, 0xFF10271A, 0xFF9FC4AE, 0xFF0B1D13, 0xFF2F5B42, 0xFF1E3E2D),
    night(0xFFFFA45C, 0xFF3A1900, 0xFF5E3000, 0xFFFFDDBF, 0xFFFF7E6B, 0xFFFFD36B, 0xFF150A06, 0xFFFFEFE6, 0xFF2B1810, 0xFFD9B3A0, 0xFF21130D, 0xFF6B4630, 0xFF482F21),
)

/** The look the user picked in Settings (always dark). */
@Composable
fun ShilexTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Themes[ThemePrefs.index.coerceIn(0, Themes.lastIndex)],
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
