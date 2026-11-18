package app.shilex.android.data

import android.content.Context
import app.shilex.android.Branding
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

    /** True when the chosen voice is one of the girl voices; the assistant then talks as a girl too. */
    val girlVoice: Boolean
        get() = voice in GIRL_VOICES

    /** The mode to go back to when agent mode is switched off from the Home screen. */
    var previousMode: String
        get() = prefs.getString("previous_mode", "girlfriend") ?: "girlfriend"
        set(value) = prefs.edit().putString("previous_mode", value).apply()

    /** The assistant's name, shown in the app and used in her instructions. */
    var assistantName: String
        get() = prefs.getString("assistant_name", Branding.DEFAULT_NAME) ?: Branding.DEFAULT_NAME
        set(value) = prefs.edit().putString("assistant_name", value).apply()

    /** Which colour theme: 0 Violet night, 1 Ocean, 2 Rose, 3 Forest, 4 Sunset. */
    var themeIndex: Int
        get() = prefs.getInt("theme_index", 0)
        set(value) = prefs.edit().putInt("theme_index", value).apply()

    /** The floating assistant over other apps: "character" or "button" (a small round orb). */
    var floatingStyle: String
        get() = prefs.getString("floating_style", "character") ?: "character"
        set(value) = prefs.edit().putString("floating_style", value).apply()

    /** What she knows about the user. Stays on the phone; goes to Gemini only inside the conversation. */
    var userName: String
        get() = prefs.getString("user_name", "") ?: ""
        set(value) = prefs.edit().putString("user_name", value).apply()

    var callName: String
        get() = prefs.getString("call_name", "jaan") ?: "jaan"
        set(value) = prefs.edit().putString("call_name", value).apply()

    /** "DD-MM", for example 15-08. */
    var birthday: String
        get() = prefs.getString("birthday", "") ?: ""
        set(value) = prefs.edit().putString("birthday", value).apply()

    var specialDay: String
        get() = prefs.getString("special_day", "") ?: ""
        set(value) = prefs.edit().putString("special_day", value).apply()

    var specialDayName: String
        get() = prefs.getString("special_day_name", "") ?: ""
        set(value) = prefs.edit().putString("special_day_name", value).apply()

    var likes: String
        get() = prefs.getString("likes", "") ?: ""
        set(value) = prefs.edit().putString("likes", value).apply()

    /** Sweet good morning, good night and special-day notifications (off until the user switches them on). */
    var wishes: Boolean
        get() = prefs.getBoolean("wishes", false)
        set(value) = prefs.edit().putBoolean("wishes", value).apply()

    /** "HH:MM". */
    var morningTime: String
        get() = prefs.getString("morning_time", "08:00") ?: "08:00"
        set(value) = prefs.edit().putString("morning_time", value).apply()

    var nightTime: String
        get() = prefs.getString("night_time", "22:30") ?: "22:30"
        set(value) = prefs.edit().putString("night_time", value).apply()

    /** Sweet, playful flirting (never explicit). */
    var flirty: Boolean
        get() = prefs.getBoolean("flirty", true)
        set(value) = prefs.edit().putBoolean("flirty", value).apply()

    /** Cute pouting and mock-sulks (never real jealousy). */
    var cutePout: Boolean
        get() = prefs.getBoolean("cute_pout", true)
        set(value) = prefs.edit().putBoolean("cute_pout", value).apply()

    /** "girlfriend", "friend", "companion", "assistant" or "agent". */
    var mode: String
        get() = prefs.getString("mode", "girlfriend") ?: "girlfriend"
        set(value) = prefs.edit().putString("mode", value).apply()

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

    /** Height of the floating character over other apps, in dp. */
    var floatingSize: Int
        get() = prefs.getInt("floating_size", 260)
        set(value) = prefs.edit().putInt("floating_size", value).apply()

    var orbY: Int
        get() = prefs.getInt("orb_y", -1)
        set(value) = prefs.edit().putInt("orb_y", value).apply()

    /** The glowing border light around the screen (needs "Display over other apps"). */
    var borderLight: Boolean
        get() = prefs.getBoolean("border_light", false)
        set(value) = prefs.edit().putBoolean("border_light", value).apply()

    var borderColor: Int
        get() = prefs.getInt("border_color", 0)
        set(value) = prefs.edit().putInt("border_color", value).apply()

    var borderSize: Int
        get() = prefs.getInt("border_size", 10)
        set(value) = prefs.edit().putInt("border_size", value).apply()

    /** Lets the assistant read and look at the screen (needs phone control switched on). */
    var screenReading: Boolean
        get() = prefs.getBoolean("screen_reading", false)
        set(value) = prefs.edit().putBoolean("screen_reading", value).apply()

    /** Taps Send itself after typing a message, instead of waiting for the user's tap. */
    var sendWithoutAsking: Boolean
        get() = prefs.getBoolean("send_without_asking", false)
        set(value) = prefs.edit().putBoolean("send_without_asking", value).apply()

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

        /** Gemini Live voices that sound like a girl, and like a boy (as far as Google describes them). */
        val GIRL_VOICES = listOf("Aoede", "Kore", "Leda", "Zephyr")
        val BOY_VOICES = listOf("Puck", "Charon", "Fenrir", "Orus")
        /** A rough idea of how each voice sounds (a guess from Google's descriptions). */
        val VOICE_HINTS = mapOf(
            "Aoede" to "halki, hawa jaisi", "Kore" to "pakki aur saaf", "Leda" to "jawan, taaza", "Zephyr" to "chamakdaar",
            "Puck" to "khush", "Charon" to "samajhdaar", "Fenrir" to "josheela", "Orus" to "pakki",
        )
        val LANGUAGES = linkedMapOf(
            "auto" to "Auto: jo tum bolo",
            "english" to "English",
            "hindi" to "Hindi",
            "hinglish" to "Hinglish",
            "bangla" to "Bangla",
            "hindi_bangla" to "Hindi + Bangla",
        )
        val MODE_LABELS = linkedMapOf(
            "girlfriend" to "Girlfriend",
            "friend" to "Friend",
            "companion" to "Companion",
            "assistant" to "Assistant",
            "agent" to "Agent",
        )
    }
}
