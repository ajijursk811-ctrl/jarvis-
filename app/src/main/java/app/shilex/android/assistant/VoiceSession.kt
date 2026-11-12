package app.shilex.android.assistant

import android.app.Application
import android.content.Context
import android.os.SystemClock
import app.shilex.android.Diag
import app.shilex.android.audio.MicRecorder
import app.shilex.android.audio.TalkOver
import app.shilex.android.audio.WaveStop
import app.shilex.android.audio.MusicPause
import app.shilex.android.control.ControlService
import app.shilex.android.audio.Speaker
import app.shilex.android.audio.VOICE_LEVEL
import app.shilex.android.audio.headphonesConnected
import app.shilex.android.audio.pcmLevel
import app.shilex.android.data.KeyCheckResult
import app.shilex.android.data.KeyVault
import app.shilex.android.data.Settings
import app.shilex.android.data.checkGeminiKey
import app.shilex.android.live.LiveClient
import app.shilex.android.live.LiveEvent
import app.shilex.android.learn.Habits
import app.shilex.android.live.LiveProtocol
import app.shilex.android.task.TaskTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

enum class Phase { Off, Connecting, Listening, Speaking }

data class UiState(
    val hasKey: Boolean,
    val phase: Phase = Phase.Off,
    val lines: List<Line> = emptyList(),
    val problem: String? = null,
    /** A permission a tool needs; the screen asks for it, then calls permissionAnswered. */
    val permissionNeeded: String? = null,
)

/**
 * Voice session: mic → Gemini Live → speaker, with reconnects and an idle timeout. One per
 * process, so it keeps running in VoiceService after the user leaves the app.
 */
class VoiceSession private constructor(private val app: Application) : LiveClient.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val vault = KeyVault(app)
    private val settings = Settings(app)
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val client = LiveClient(http)
    private val speaker = Speaker()
    private val mic = MicRecorder(::onMicChunk)
    private val wave = WaveStop(app) { interrupt("a wave") }
    private val talkOver = TalkOver(speaker)
    private val transcript = Transcript()
    private val tools = ToolRunner(
        app,
        http,
        scope,
        answer = { client.send(LiveProtocol.toolResponseMessage(it)) },
        askPermission = { permission -> _state.update { it.copy(permissionNeeded = permission) } },
        onScreen = { screenVisible },
        note = { if (wanted) client.send(LiveProtocol.appNoteMessage(it)) },
        endSoon = ::endAfterReply,
    )
    private val musicPause = MusicPause(app)

    /** Set by MainActivity; apps can only be opened directly while JARVIS is on screen. */
    @Volatile var screenVisible = false

    private val _state = MutableStateFlow(UiState(hasKey = vault.read(KeyVault.GEMINI_KEY) != null))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _level = MutableStateFlow(0f)

    /** Loudness of the user's voice or the reply, 0..1, so the orb can move with it. */
    val level: StateFlow<Float> = _level.asStateFlow()

    @Volatile private var wanted = false
    @Volatile private var musicAsked = false // a tool started music: don't pause it again
    @Volatile private var pendingText: String? = null

    /** True from start() until stop(). */
    val active: Boolean get() = wanted
    @Volatile private var resumeHandle: String? = null
    @Volatile private var droppingReply = false
    @Volatile private var lastActivity = 0L
    private var retries = 0
    private var watchdog: Job? = null

    // Timings for the diagnostic log (never the words themselves).
    private var connectAt = 0L
    @Volatile private var heardUser = false
    @Volatile private var voiceAt = 0L
    @Volatile private var replyStarted = false
    @Volatile private var mutedChunks = 0
    @Volatile private var loudest = 0

    suspend fun saveKey(key: String): String? {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return "Paste your Gemini key first."
        return when (val result = checkGeminiKey(http, trimmed, settings.model)) {
            KeyCheckResult.Ok -> {
                vault.write(KeyVault.GEMINI_KEY, trimmed)
                _state.update { it.copy(hasKey = true, problem = null) }
                null
            }
            is KeyCheckResult.Problem -> result.message
        }
    }

    fun forgetKey() {
        stop("key removed")
        vault.remove(KeyVault.GEMINI_KEY)
        _state.update { it.copy(hasKey = false) }
    }

    /** Call only after the mic is allowed. [firstText], heard or typed, goes to Gemini as the user's turn. */
    fun start(firstText: String? = null) {
        // Typed during a session (floating orb): send now, or once connected.
        if (wanted && firstText != null) return tools.userSpoke(firstText).also {
            if (_state.value.phase == Phase.Connecting) pendingText = firstText else client.send(LiveProtocol.userTextMessage(firstText))
        }
        pendingText = firstText
        // Music playing would drown the user's voice; it resumes when the session ends.
        musicPause.pause()
        if (wanted) return
        wanted = true
        retries = 0
        resumeHandle = null
        resetTurnLog()
        musicAsked = false
        Diag.i("session: start (headphones=${headphonesConnected(app)})")
        talkOver.reset(newConversation = true)
        VoiceService.start(app)
        ControlService.instance?.overlay?.stopped = false
        lastActivity = SystemClock.elapsedRealtime()
        connect()
        watchdog = scope.launch {
            var musicChecks = 0
            var repaused = false
            while (isActive) {
                delay(WATCH_MS)
                // Music sounds like speech and keeps a session open forever. YouTube Music sometimes resumes by itself
                // after pausing, so pause it once more; if it plays again the user wants it, so end and let "Hey Jarvis" listen.
                musicChecks = if (musicPause.othersPlaying() && !speaker.isAudible(tailMs = 1_500)) musicChecks + 1 else 0
                if (musicAsked) {
                    musicChecks = 0
                } else if (musicChecks >= 1 && !repaused) {
                    repaused = true
                    musicChecks = 0
                    Diag.i("audio: music resumed by itself, pausing again")
                    musicPause.pause()
                } else if (musicChecks >= 2) {
                    stop("music playing")
                } else if (SystemClock.elapsedRealtime() - lastActivity > IDLE_MS) {
                    stop("idle for 3 minutes")
                    _state.update { it.copy(problem = "Stopped listening after 3 quiet minutes.") }
                }
            }
        }
    }

    /** Ends the session; [reason] goes to the diagnostic log. */
    fun stop(reason: String) {
        if (wanted) Diag.i("session: stop ($reason)")
        wanted = false
        wave.watch(false)
        VoiceService.stop(app)
        musicPause.resume()
        tools.reset()
        ControlService.instance?.overlay?.cancelPending()
        watchdog?.cancel()
        mic.stop()
        client.close()
        speaker.flush()
        transcript.endTurn()
        _state.update { it.copy(phase = Phase.Off) }
        _level.value = 0f
    }

    private fun loudness(level: Int) = (level / LOUD_LEVEL).coerceIn(0f, 1f)

    /** Ends the session once the reply in progress has been spoken. */
    private fun endAfterReply() {
        musicAsked = true
        scope.launch {
            delay(300)
            while (speaker.isAudible(tailMs = 0) || _state.value.phase == Phase.Connecting) delay(100)
            if (wanted) {
                stop("music started")
            }
        }
    }

    /** Tap while it speaks: silence the rest of this reply. */
    fun interrupt(how: String = "tap") {
        if (_state.value.phase != Phase.Speaking) return
        Diag.i("reply: stopped by $how")
        wave.watch(false)
        droppingReply = true
        speaker.flush()
        _state.update { it.copy(phase = Phase.Listening) }
    }

    /** The user answered a tool's permission request; tell the model so it can carry on. */
    fun permissionAnswered(granted: Boolean) {
        val permission = _state.value.permissionNeeded ?: return
        _state.update { it.copy(permissionNeeded = null) }
        if (wanted) client.send(LiveProtocol.appNoteMessage(ToolRunner.permissionNote(permission, granted)))
    }

    fun micUnavailable() {
        stop("mic permission refused")
        _state.update { it.copy(problem = "The microphone is needed to talk. Allow it in the app's settings.") }
    }

    private fun connect() {
        val key = vault.read(KeyVault.GEMINI_KEY)
        if (key == null) {
            wanted = false
            _state.update { it.copy(hasKey = false, phase = Phase.Off) }
            return
        }
        _state.update { it.copy(phase = Phase.Connecting, problem = null) }
        connectAt = SystemClock.elapsedRealtime()
        Diag.i("socket: connecting (try ${retries + 1}, resume=${resumeHandle != null}, model=${settings.model})")
        val setup = LiveProtocol.Setup(
            model = settings.model,
            systemInstruction = Instructions.build(
                settings.replyLanguage,
                java.time.ZonedDateTime.now(),
                if (settings.learning) Habits.get(app).summary(java.time.LocalTime.now().hour) else "",
                settings.mode,
                settings.girlVoice,
            ),
            voice = settings.voice,
            languages = settings.speechLanguages,
            resumeHandle = resumeHandle,
            tools = tools.declarations(),
        )
        client.connect(key, setup, this)
    }

    override fun onEvent(event: LiveEvent) {
        when (event) {
            LiveEvent.SetupComplete -> {
                Diag.i("socket: ready in ${SystemClock.elapsedRealtime() - connectAt} ms")
                retries = 0
                if (!mic.start()) {
                    stop("mic busy")
                    _state.update { it.copy(problem = "Another app is using the microphone.") }
                    return
                }
                _state.update { it.copy(phase = Phase.Listening) }
                pendingText?.let {
                    pendingText = null
                    client.send(LiveProtocol.userTextMessage(it))
                }
            }
            is LiveEvent.Audio -> if (!droppingReply) {
                if (!replyStarted) {
                    replyStarted = true
                    val wait = if (voiceAt > 0) "${SystemClock.elapsedRealtime() - voiceAt} ms after you stopped talking" else "with no voice heard"
                    Diag.i("reply: first audio $wait")
                }
                speaker.play(event.pcm, event.sampleRate)
                _level.value = loudness(pcmLevel(event.pcm))
                _state.update { it.copy(phase = Phase.Speaking) }
                wave.watch(settings.waveToStop)
            }
            is LiveEvent.UserText -> {
                lastActivity = SystemClock.elapsedRealtime()
                if (!heardUser) {
                    Diag.i("user: speech heard")
                    TaskTracker.heard()
                }
                heardUser = true
                tools.userSpoke(event.text)
                _state.update { it.copy(lines = transcript.add(fromUser = true, event.text)) }
            }
            is LiveEvent.AssistantText -> if (!droppingReply) {
                lastActivity = SystemClock.elapsedRealtime()
                _state.update { it.copy(lines = transcript.add(fromUser = false, event.text)) }
            }
            LiveEvent.Interrupted -> {
                // The server cut the old reply itself; audio after this is a new one.
                Diag.i("reply: interrupted by the user's voice")
                wave.watch(false)
                droppingReply = false
                replyStarted = false
                speaker.flush()
                transcript.endTurn()
            }
            LiveEvent.TurnComplete -> {
                speaker.endReply()
                logTurnEnd()
                droppingReply = false
                transcript.endTurn()
                scope.launch {
                    while (speaker.isAudible(tailMs = 0)) delay(100)
                    wave.watch(false)
                    if (wanted && _state.value.phase == Phase.Speaking) {
                        _state.update { it.copy(phase = Phase.Listening) }
                    }
                    if (wanted) tools.openPending()
                }
            }
            is LiveEvent.ToolCall -> {
                lastActivity = SystemClock.elapsedRealtime()
                tools.handle(event.calls)
            }
            is LiveEvent.ToolCancelled -> tools.cancel(event.ids)
            is LiveEvent.ResumeHandle -> resumeHandle = event.handle
            LiveEvent.GoAway -> if (wanted) {
                Diag.i("socket: server asked to reconnect")
                client.close()
                connect()
            }
        }
    }

    override fun onClosed(problem: String?) {
        mic.stop()
        if (!wanted) return
        if (retries < MAX_RETRIES) {
            retries++
            Diag.w("socket: lost, retry $retries of $MAX_RETRIES")
            scope.launch {
                delay(1_000L * retries)
                if (wanted) connect()
            }
        } else {
            Diag.w("socket: giving up after $MAX_RETRIES retries")
            stop("connection lost")
            _state.update { it.copy(problem = problem ?: "Lost the connection to Gemini.") }
        }
    }

    private fun onMicChunk(raw: ByteArray) {
        val pcm = talkOver.clean(raw)
        // Without headphones the mic would hear the reply and answer itself.
        val echoRisk = settings.halfDuplex && !headphonesConnected(app)
        if (echoRisk && speaker.isAudible()) {
            val voice = if (settings.talkOver) talkOver.duringReply(raw, pcm) else null
            if (voice == null) {
                mutedChunks++
                return
            }
            interrupt("you talked over it")
            voice.forEach(client::sendAudio)
            return
        }
        val level = pcmLevel(pcm)
        _level.value = loudness(level)
        if (level > loudest) loudest = level
        if (!replyStarted && level >= VOICE_LEVEL) voiceAt = SystemClock.elapsedRealtime()
        client.sendAudio(pcm)
    }

    private fun logTurnEnd() {
        Diag.i(
            "turn: done, mic muted ${mutedChunks * 100} ms for echo (peak ${talkOver.rawPeak}, after canceller " +
                "${talkOver.cleanPeak}, talk-over from ${talkOver.readyAfterMs} ms), speaker gaps ${speaker.takeGaps()}, " +
                "loudest mic level $loudest",
        )
        resetTurnLog()
    }

    private fun resetTurnLog() {
        speaker.takeGaps()
        mutedChunks = 0
        loudest = 0
        talkOver.reset()

        heardUser = false
        voiceAt = 0
        replyStarted = false
    }

    companion object {
        private const val MAX_RETRIES = 3
        private const val IDLE_MS = 3 * 60_000L
        private const val LOUD_LEVEL = 3_000f // a clear voice near the phone
        private const val WATCH_MS = 3_000L

        @Volatile private var instance: VoiceSession? = null

        fun get(context: Context): VoiceSession = instance ?: synchronized(this) {
            instance ?: VoiceSession(context.applicationContext as Application).also { instance = it }
        }
    }
}
