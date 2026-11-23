package app.shilex.android.assistant

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.shilex.android.Branding
import app.shilex.android.Diag
import app.shilex.android.R
import app.shilex.android.data.Settings
import app.shilex.android.ui.CallActivity
import kotlin.math.sqrt

/**
 * Shake the phone and the assistant wakes up: the call screen opens and she starts listening.
 * It only reads the motion sensor, never the microphone, so the green mic dot appears only after
 * the shake, when she really starts to listen. It reacts only while the phone is on and unlocked
 * (a phone swinging in a pocket or bag does nothing).
 */
class ShakeService : Service(), SensorEventListener {
    private var threshold = 2.5f
    private val hits = ArrayDeque<Long>()
    private var lastHit = 0L
    private var coolUntil = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Settings(this).shakeWake = false
            stopSelf()
            return START_NOT_STICKY
        }
        threshold = when (Settings(this).shakeSensitivity) {
            0 -> 3.2f
            2 -> 2.0f
            else -> 2.5f
        }
        goForeground()
        val sensors = getSystemService(SensorManager::class.java)
        sensors.unregisterListener(this)
        val accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometer == null) {
            Diag.w("shake: no accelerometer on this phone")
            stopSelf()
            return START_NOT_STICKY
        }
        sensors.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        return START_STICKY
    }

    override fun onDestroy() {
        getSystemService(SensorManager::class.java).unregisterListener(this)
        super.onDestroy()
    }

    private fun goForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Shake to wake", NotificationManager.IMPORTANCE_MIN))
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, ShakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${Branding.ASSISTANT_NAME}: shake to talk")
            .setContentText("Shake the phone to wake her. The mic stays off until then.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .addAction(0, "Turn off", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, notification)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (g < threshold) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastHit < 150) return
        lastHit = now
        while (hits.isNotEmpty() && now - hits.first() > 1_200) hits.removeFirst()
        hits.addLast(now)
        if (hits.size >= 3 && now >= coolUntil) {
            hits.clear()
            coolUntil = now + 4_000
            wakeUp()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun wakeUp() {
        val power = getSystemService(PowerManager::class.java)
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!power.isInteractive || keyguard.isKeyguardLocked) return
        if (VoiceSession.get(application).active) return
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
        runCatching {
            startActivity(Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Diag.w("shake: couldn't open the call screen (${it.javaClass.simpleName})") }
    }

    companion object {
        const val ACTION_STOP = "app.masuka.ai.SHAKE_STOP"
        private const val CHANNEL = "shake"
        private const val ID = 7301
    }
}

object ShakeWake {
    /** Starts or stops the shake listener to match Settings. Call it at start and when the switch or sensitivity changes. */
    fun sync(context: Context) {
        val app = context.applicationContext
        if (Settings(app).shakeWake) {
            runCatching { ContextCompat.startForegroundService(app, Intent(app, ShakeService::class.java)) }
                .onFailure { Diag.w("shake: couldn't start (${it.javaClass.simpleName})") }
        } else {
            app.stopService(Intent(app, ShakeService::class.java))
        }
    }
}
