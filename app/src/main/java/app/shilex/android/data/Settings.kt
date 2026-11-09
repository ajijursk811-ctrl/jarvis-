package app.shilex.android.data

import android.content.Context
import android.content.SharedPreferences

/** Plain preferences (no secrets). Defaults match the desktop app. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var model: String
        get() = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString("model", value).apply()

    var voice: String
        get() = prefs.getString("voice", "Aoede") ?: "Aoede"
        set(value) = prefs.edit().putString("voice", value).apply()

    /** "auto", "english", "hindi" or "hinglish", as on desktop. */
    var replyLanguage: String
        get() = prefs.getString("reply_language", "auto") ?: "auto"
        set(value) = prefs.edit().putString("reply_language", value).apply()

    /** Mute the mic while the assistant speaks, unless headphones are in use. */
    var halfDuplex: Boolean
        get() = prefs.getBoolean("half_duplex", true)
        set(value) = prefs.edit().putBoolean("half_duplex", value).apply()

    /** Talk over a reply on the loudspeaker (audio/TalkOver, with the echo canceller). */
    var talkOver: Boolean
        get() = prefs.getBoolean("talk_over", true)
        set(value) = prefs.edit().putBoolean("talk_over", value).apply()

    /** Wave over the proximity sensor to cut a reply off (audio/WaveStop). */
    var waveToStop: Boolean
        get() = prefs.getBoolean("wave_to_stop", true)
        set(value) = prefs.edit().putBoolean("wave_to_stop", value).apply()

    /** Listen on the phone for the wake phrase between sessions (debug builds only for now). */
    var wakeWord: Boolean
        get() = prefs.getBoolean("wake_word", false)
        set(value) = prefs.edit().putBoolean("wake_word", value).apply()

    /** After the wake word, simple commands run on the phone (no Gemini) when it can hear them. */
    var localCommands: Boolean
        get() = prefs.getBoolean("local_commands", true)
        set(value) = prefs.edit().putBoolean("local_commands", value).apply()

    /**
     * Let local commands use the phone's network speech service when it has no on-device one.
     * Off by default: that service sends the audio to its provider (usually Google).
     */
    var networkSpeech: Boolean
        get() = prefs.getBoolean("network_speech", false)
        set(value) = prefs.edit().putBoolean("network_speech", value).apply()

    /** Learn on the phone how this user uses JARVIS (learn/Habits). */
    var learning: Boolean
        get() = prefs.getBoolean("learning", true)
        set(value) = prefs.edit().putBoolean("learning", value).apply()

    /** The first-run setup was finished (or skipped) once. */
    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(value) = prefs.edit().putBoolean("onboarded", value).apply()

    /** The floating orb over other apps (needs "Display over other apps"). */
    var floatingOrb: Boolean
        get() = prefs.getBoolean("floating_orb", false)
        set(value) = prefs.edit().putBoolean("floating_orb", value).apply()

    /** Where the user last left the floating orb, in screen pixels; -1 = not moved yet. */
    var orbX: Int
        get() = prefs.getInt("orb_x", -1)
        set(value) = prefs.edit().putInt("orb_x", value).apply()

    var orbY: Int
        get() = prefs.getInt("orb_y", -1)
        set(value) = prefs.edit().putInt("orb_y", value).apply()

    /** The last city asked about; weather questions without a city use it. */
    var homeCity: String
        get() = prefs.getString("home_city", "") ?: ""
        set(value) = prefs.edit().putString("home_city", value).apply()

    /** Calls [onChange] after any setting changes, from any screen or the notification. */
    fun watch(onChange: () -> Unit): SharedPreferences.OnSharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange() }
            .also(prefs::registerOnSharedPreferenceChangeListener)

    fun unwatch(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    val speechLanguages: List<String> = listOf("en-IN", "hi-IN")

    companion object {
        const val DEFAULT_MODEL = "gemini-3.1-flash-live-preview"
    }
}
