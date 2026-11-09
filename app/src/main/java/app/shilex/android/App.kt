package app.shilex.android

import android.app.Application
import app.shilex.android.orb.FloatingOrb

/**
 * Starts the diagnostic log file before any screen, service or receiver runs, and brings the
 * floating orb back whenever the process starts (after an update, a kill or a reboot, Android
 * restarts phone control and notification access, which starts the process).
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Diag.start(DiagFile(filesDir))
        FloatingOrb.sync(this)
     app.shilex.android.orb.BorderLight.sync(this)
    }
}
