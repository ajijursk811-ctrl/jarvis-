package app.shilex.android.live

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * JSON for the Gemini Live WebSocket (BidiGenerateContent). Pure functions, so they
 * are unit-tested without a phone. The session settings match the desktop app.
 */
object LiveProtocol {
    const val INPUT_SAMPLE_RATE = 16_000
    private const val DEFAULT_OUTPUT_RATE = 24_000
    private val RATE = Regex("""rate=(\d+)""")

    data class Setup(
        val model: String,
        val systemInstruction: String,
        val voice: String?,
        val languages: List<String>,
        val resumeHandle: String?,
        /** Function declarations (name, description, parameters) the model may call. */
        val tools: JSONArray = JSONArray(),
    )

    fun setupMessage(setup: Setup): String {
        val generation = JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
        if (!setup.voice.isNullOrBlank()) {
            generation.put(
                "speechConfig",
                JSONObject().put(
                    "voiceConfig",
                    JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", setup.voice)),
                ),
            )
        }
        val inputTranscription = JSONObject()
        if (setup.languages.isNotEmpty()) {
            inputTranscription.put("languageCodes", JSONArray(setup.languages))
        }
        val resumption = JSONObject()
        if (!setup.resumeHandle.isNullOrBlank()) resumption.put("handle", setup.resumeHandle)

        val body = JSONObject()
            .put("model", "models/${setup.model}")
            .put("generationConfig", generation)
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", setup.systemInstruction))),
            )
            .put("inputAudioTranscription", inputTranscription)
            .apply {
                if (setup.tools.length() > 0) {
                    put("tools", JSONArray().put(JSONObject().put("functionDeclarations", setup.tools)))
                }
            }
            .put("outputAudioTranscription", JSONObject())
            .put("sessionResumption", resumption)
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
            .put(
                "realtimeInputConfig",
                JSONObject().put(
                    "automaticActivityDetection",
                    JSONObject().put("prefixPaddingMs", 200).put("silenceDurationMs", 700),
                ),
            )
        return JSONObject().put("setup", body).toString()
    }

    fun audioMessage(pcm: ByteArray): String {
        val audio = JSONObject()
            .put("data", Base64.getEncoder().encodeToString(pcm))
            .put("mimeType", "audio/pcm;rate=$INPUT_SAMPLE_RATE")
        return JSONObject().put("realtimeInput", JSONObject().put("audio", audio)).toString()
    }

    /** One picture from the live camera, sent as video (about one a second). */
    fun videoMessage(jpeg: ByteArray): String {
        val video = JSONObject()
            .put("data", Base64.getEncoder().encodeToString(jpeg))
            .put("mimeType", "image/jpeg")
        return JSONObject().put("realtimeInput", JSONObject().put("video", video)).toString()
    }

    /** Words the user already said, heard by the phone's own recogniser. */
    fun userTextMessage(text: String): String {
        val turn = JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))
        return JSONObject().put("clientContent", JSONObject().put("turns", JSONArray().put(turn)).put("turnComplete", true))
            .toString()
    }

    /** A note from the app itself (not the user's words), e.g. a permission answer. */
    fun appNoteMessage(note: String): String {
        val turn = JSONObject().put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", "[Note from the app, not the user] $note")))
        return JSONObject().put("clientContent", JSONObject().put("turns", JSONArray().put(turn)).put("turnComplete", true))
            .toString()
    }

    fun toolResponseMessage(results: List<FunctionResult>): String {
        val responses = JSONArray()
        results.forEach {
            responses.put(JSONObject().put("id", it.id).put("name", it.name).put("response", it.response))
        }
        return JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)).toString()
    }

    /** One server message can carry several events; they come out in a stable order. */
    fun parse(text: String): List<LiveEvent> {
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val events = mutableListOf<LiveEvent>()
        if (message.has("setupComplete")) events += LiveEvent.SetupComplete
        message.optJSONObject("sessionResumptionUpdate")?.let { update ->
            val handle = update.optString("newHandle")
            if (update.optBoolean("resumable", true) && handle.isNotEmpty()) {
                events += LiveEvent.ResumeHandle(handle)
            }
        }
        message.optJSONObject("serverContent")?.let { events += parseContent(it) }
        message.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
            val parsed = (0 until calls.length()).mapNotNull { i ->
                val call = calls.optJSONObject(i) ?: return@mapNotNull null
                val name = call.optString("name").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                FunctionCall(call.optString("id"), name, call.optJSONObject("args") ?: JSONObject())
            }
            if (parsed.isNotEmpty()) events += LiveEvent.ToolCall(parsed)
        }
        message.optJSONObject("toolCallCancellation")?.optJSONArray("ids")?.let { ids ->
            events += LiveEvent.ToolCancelled((0 until ids.length()).map { ids.optString(it) })
        }
        if (message.has("goAway")) events += LiveEvent.GoAway
        return events
    }

    private fun parseContent(content: JSONObject): List<LiveEvent> {
        val events = mutableListOf<LiveEvent>()
        if (content.optBoolean("interrupted")) events += LiveEvent.Interrupted
        content.optJSONObject("inputTranscription")?.optString("text")
            ?.takeIf { it.isNotEmpty() }?.let { events += LiveEvent.UserText(it) }
        val parts = content.optJSONObject("modelTurn")?.optJSONArray("parts")
        for (i in 0 until (parts?.length() ?: 0)) {
            val blob = parts!!.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
            val mime = blob.optString("mimeType")
            val data = blob.optString("data")
            if (mime.startsWith("audio/") && data.isNotEmpty()) {
                events += LiveEvent.Audio(Base64.getDecoder().decode(data), sampleRate(mime))
            }
        }
        content.optJSONObject("outputTranscription")?.optString("text")
            ?.takeIf { it.isNotEmpty() }?.let { events += LiveEvent.AssistantText(it) }
        if (content.optBoolean("turnComplete")) events += LiveEvent.TurnComplete
        return events
    }

    private fun sampleRate(mime: String): Int =
        RATE.find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: DEFAULT_OUTPUT_RATE

    /** A short, user-facing reason for a closed session. Never includes the key. */
    fun describeClose(code: Int, reason: String): String {
        val lower = reason.lowercase()
        return when {
            "api key" in lower || "permission" in lower || "unauth" in lower ->
                "Gemini rejected the key. Check it in settings."
            "quota" in lower || "exceeded" in lower || "rate" in lower ->
                "Gemini rate limit reached. Try again in a minute."
            "not found" in lower || "model" in lower -> "That Gemini voice model wasn't found."
            code == 1000 -> "Gemini ended the session."
            else -> "Lost the connection to Gemini."
        }
    }
}
