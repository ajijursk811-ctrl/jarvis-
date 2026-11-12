package app.shilex.android.orb

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shilex.android.Branding
import app.shilex.android.Diag
import app.shilex.android.MainActivity
import app.shilex.android.assistant.Phase
import app.shilex.android.assistant.VoiceSession
import app.shilex.android.data.Settings
import app.shilex.android.ui.AvatarSprite
import app.shilex.android.ui.ShilexTheme
import kotlin.math.roundToInt

/**
 * Masuka (the character) floating over every app, so it can be used without opening JARVIS: tap to talk
 * (tap again to stop, or to cut in while it speaks), double-tap to type, drag to move; it
 * settles at the nearest side. Hidden while JARVIS's own screen is open, which has the big orb.
 */
object FloatingOrb {
    private var host: OverlayHost? = null
    private var params: WindowManager.LayoutParams? = null

    /** True while JARVIS's own screen is in front. */
    @Volatile var appVisible = false

    fun allowed(context: Context) = AndroidSettings.canDrawOverlays(context)

    /** Shows or hides the orb to match the setting, the permission and whether the app is open. */
    fun sync(context: Context) {
        val app = context.applicationContext
        val want = Settings(app).floatingOrb && allowed(app) && !appVisible
        if (want && host == null) show(app) else if (!want && host != null) hide(app)
    }

    private fun show(context: Context) {
        val windows = context.getSystemService(WindowManager::class.java)
        val settings = Settings(context)
        val screen = context.resources.displayMetrics
        val height = (settings.floatingSize * screen.density).roundToInt().coerceAtMost(screen.heightPixels)
        val width = (height * 9f / 16f).roundToInt()
        val p = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (settings.orbX.takeIf { it >= 0 } ?: ((screen.widthPixels - width) / 2))
                .coerceIn(0, (screen.widthPixels - width).coerceAtLeast(0))
            y = (settings.orbY.takeIf { it >= 0 } ?: (screen.heightPixels * 0.35f).roundToInt())
                .coerceIn(0, (screen.heightPixels - height).coerceAtLeast(0))
        }
        val orb = OverlayHost(context)
        orb.open {
            val session = VoiceSession.get(context)
            val state by session.state.collectAsStateWithLifecycle()
            val level by session.level.collectAsStateWithLifecycle()
            ShilexTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = "${Branding.ASSISTANT_NAME}: tap to talk, double-tap to type" }
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { tap(context) }, onDoubleTap = { TypeBox.show(context) })
                        }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragEnd = { settle(context) },
                                onDrag = { change, delta ->
                                    change.consume()
                                    move(context, delta.x.roundToInt(), delta.y.roundToInt())
                                },
                            )
                        },
                ) {
                    AvatarSprite(state.phase, Modifier.fillMaxSize())
                }
            }
        }
        runCatching { windows.addView(orb.view, p) }
            .onSuccess {
                host = orb
                params = p
                Diag.i("orb: shown")
            }
            .onFailure { Diag.w("orb: couldn't show (${it.javaClass.simpleName})") }
    }

    private fun hide(context: Context) {
        val orb = host ?: return
        runCatching { context.getSystemService(WindowManager::class.java).removeView(orb.view) }
        orb.close()
        host = null
        params = null
        TypeBox.hide(context)
        Diag.i("orb: hidden")
    }

    private fun tap(context: Context) {
        val session = VoiceSession.get(context)
        val micAllowed = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        when {
            !micAllowed || !session.state.value.hasKey -> openApp(context)
            !session.active -> session.start()
            session.state.value.phase == Phase.Speaking -> session.interrupt("orb")
            else -> session.stop("orb tap")
        }
    }

    private fun move(context: Context, dx: Int, dy: Int) {
        val p = params ?: return
        val screen = context.resources.displayMetrics
        p.x = (p.x + dx).coerceIn(0, screen.widthPixels - p.width)
        p.y = (p.y + dy).coerceIn(0, screen.heightPixels - p.height)
        update(context, p)
    }

    /** Stays where the user let go, and remembers the spot. */
    private fun settle(context: Context) {
        val p = params ?: return
        Settings(context).apply {
            orbX = p.x
            orbY = p.y
        }
    }

    private fun update(context: Context, p: WindowManager.LayoutParams) {
        val view = host?.view ?: return
        runCatching { context.getSystemService(WindowManager::class.java).updateViewLayout(view, p) }
    }

    /** The app asks for the mic or the key; the orb can't. */
    fun openApp(context: Context) {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Shows the character again, e.g. after its size was changed in Settings. */
    fun reload(context: Context) {
        val app = context.applicationContext
        if (host != null) hide(app)
        sync(app)
    }
}
