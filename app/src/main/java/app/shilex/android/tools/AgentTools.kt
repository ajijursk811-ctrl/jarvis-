package app.shilex.android.tools

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import app.shilex.android.Branding
import app.shilex.android.control.ControlService
import app.shilex.android.Diag
import app.shilex.android.data.KeyVault
import app.shilex.android.ui.TerminalActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Agent mode's two extra abilities, both using the user's own Gemini key:
 * research (Google Search through Gemini) and build_site (a small website or web app in one
 * HTML file, built in the background by the terminal and opened in Chrome).
 */
object AgentTools {
    fun all(): List<PhoneTool> = listOf(Research, BuildSite, LockPhone)
}

object Research : PhoneTool {
    override val name = "research"

    override fun declaration() = Schema.function(
        name,
        "Researches a topic on the web with Google Search (through Gemini) and returns a short summary with " +
            "its sources. Use it for current facts, news, prices, comparisons, how-tos and anything you are not sure about.",
        Schema.string("question", "What to find out, in full, e.g. \"best phones under 15000 rupees in India\".", required = true),
    )

    override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
        val question = args.text("question")
        if (question.isEmpty()) return Results.failed("no_question", "Ask the user what to research.")
        val key = KeyVault(host.context).read(KeyVault.GEMINI_KEY)
            ?: return Results.failed("no_key", "Researching needs the Gemini key.")
        val gen = withContext(Dispatchers.IO) {
            GeminiText.generate(host.http, key, question, AgentWorkspace.RESEARCH_SYSTEM, search = true, maxTokens = 1500, seconds = 35)
        }
        val answer = gen.text
            ?: return Results.failed("research_failed", "Couldn't research that: ${gen.error}. Say so and offer to try again.")
        return Results.done(answer.take(2500))
    }
}

object BuildSite : PhoneTool {
    override val name = "build_site"

    override fun declaration() = Schema.function(
        name,
        "Builds a small website, web app or game (one HTML file with CSS and JavaScript) from the user's description. " +
            "It is built in the background in the terminal screen (about half a minute) and opens in Chrome when ready, " +
            "so don't wait for it. To change a page you already built, pass its name in edit and the change in request.",
        Schema.string("request", "What to build (or what to change), in the user's words, with every detail they gave.", required = true),
        Schema.string("title", "A short name for it, e.g. \"Snake game\"."),
        Schema.string("edit", "Name of an existing page to change instead of building a new one (optional)."),
    )

    override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
        val request = args.text("request")
        if (request.isEmpty()) return Results.failed("no_request", "Ask the user what to build.")
        val title = args.text("title").ifEmpty { "My site" }
        val edit = args.text("edit").ifEmpty { null }
        AgentWorkspace.startBuild(host.context, host.http, request, title, edit)
        val intent = Intent(host.context, TerminalActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return host.openApp(
            intent,
            "Terminal",
            "I started building \"$title\". The terminal screen opens after you speak and shows the progress; it takes " +
                "about half a minute and then opens in Chrome. Tell the user that in one short sentence and don't wait.",
        )
    }
}

/** "Masuka, phone lock kar do": locks the screen like the power button, through phone control. */
object LockPhone : PhoneTool {
    override val name = "lock_phone"

    override fun declaration() = Schema.function(
        name,
        "Locks the phone screen, like pressing the power button. Use it when the user says lock the phone, " +
            "screen off, or \"phone lock kar do\".",
    )

    override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
        val service = ControlService.instance ?: return Results.failed(
            "control_off",
            "Phone control isn't connected. Tell the user: in Android Accessibility, switch " +
                "${Branding.ASSISTANT_NAME} off and on again.",
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return Results.failed("unsupported", "This Android is too old to lock the screen this way.")
        }
        return if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) {
            Results.done("The phone is locked.")
        } else {
            Results.failed("lock_failed", "The phone didn't lock.")
        }
    }
}

/** What one Gemini text request returned: the text, or why it failed. */
internal class Gen(val text: String?, val error: String = "")

/** One Gemini text request over REST, with the user's own key. */
internal object GeminiText {
    private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"
    private const val MODEL = "gemini-flash-latest"

    fun generate(
        http: OkHttpClient,
        key: String,
        prompt: String,
        system: String,
        search: Boolean,
        maxTokens: Int,
        seconds: Long,
    ): Gen {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("temperature", if (search) 0.3 else 0.6).put("maxOutputTokens", maxTokens))
        if (search) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        val request = Request.Builder()
            .url("$ENDPOINT/$MODEL:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val client = http.newBuilder().callTimeout(seconds, TimeUnit.SECONDS).readTimeout(seconds, TimeUnit.SECONDS).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Diag.w("gemini text: HTTP ${response.code}")
                    return Gen(null, httpReason(response.code))
                }
                val root = JSONObject(response.body!!.string())
                val candidate = root.getJSONArray("candidates").getJSONObject(0)
                val parts = candidate.getJSONObject("content").getJSONArray("parts")
                val text = buildString {
                    for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text"))
                }.trim()
                if (text.isEmpty()) Gen(null, "Gemini sent an empty reply") else Gen(text + sources(candidate))
            }
        } catch (e: IOException) {
            Diag.w("gemini text: ${e.javaClass.simpleName}")
            Gen(null, "no internet, or it took too long (${e.javaClass.simpleName})")
        } catch (e: Exception) {
            Diag.w("gemini text: ${e.javaClass.simpleName}")
            Gen(null, "Gemini sent a reply I couldn't read (${e.javaClass.simpleName})")
        }
    }

    private fun httpReason(code: Int): String = when (code) {
        400 -> "Gemini refused the request (HTTP 400)"
        401, 403 -> "the Gemini key was rejected (HTTP $code). Check it in Settings"
        429 -> "the free Gemini limit is used up for now (HTTP 429). Wait a minute and try again"
        in 500..599 -> "Gemini is busy right now (HTTP $code). Try again"
        else -> "Gemini answered HTTP $code"
    }

    /** The first few web sources Gemini used, as "Sources: a, b, c". */
    private fun sources(candidate: JSONObject): String {
        val chunks = candidate.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks") ?: return ""
        val names = (0 until chunks.length())
            .mapNotNull { chunks.optJSONObject(it)?.optJSONObject("web")?.optString("title")?.takeIf { t -> t.isNotBlank() } }
            .distinct()
            .take(3)
        return if (names.isEmpty()) "" else "\nSources: " + names.joinToString(", ")
    }
}
