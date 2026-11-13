package app.shilex.android.tools

import android.content.Intent
import app.shilex.android.data.KeyVault
import app.shilex.android.Diag
import app.shilex.android.ui.SitePreviewActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Agent mode's two extra abilities, both using the user's own Gemini key:
 * research (Google Search through Gemini) and build_site (a small website or web app in one
 * HTML file, opened in a live preview on the phone).
 */
object AgentTools {
    fun all(): List<PhoneTool> = listOf(Research, BuildSite)
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
        val answer = withContext(Dispatchers.IO) {
            GeminiText.generate(host.http, key, question, SYSTEM, search = true, maxTokens = 1500, seconds = 35)
        } ?: return Results.failed("research_failed", "Couldn't research that just now. Say so and offer to try again.")
        return Results.done(answer.take(2500))
    }

    private const val SYSTEM =
        "You are a careful research assistant. Search the web, then answer in at most eight short sentences with " +
            "the key facts and figures. Say which sources you used. If sources disagree, say so. Never invent facts."
}

object BuildSite : PhoneTool {
    override val name = "build_site"
    const val FOLDER = "sites"

    override fun declaration() = Schema.function(
        name,
        "Builds a small website or web app (one HTML file with CSS and JavaScript) from the user's description and " +
            "opens a live preview on the phone. Use it for a landing page, portfolio, calculator, to-do app, game or any small " +
            "tool. To change it, call again with the full updated request.",
        Schema.string("request", "What to build, in the user's words, with every detail they gave.", required = true),
        Schema.string("title", "A short name for it, e.g. \"Todo app\"."),
    )

    override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
        val request = args.text("request")
        if (request.isEmpty()) return Results.failed("no_request", "Ask the user what to build.")
        val title = args.text("title").ifEmpty { "My site" }
        val key = KeyVault(host.context).read(KeyVault.GEMINI_KEY)
            ?: return Results.failed("no_key", "Building needs the Gemini key.")
        val raw = withContext(Dispatchers.IO) {
            GeminiText.generate(host.http, key, request, SYSTEM, search = false, maxTokens = 14000, seconds = 40)
        } ?: return Results.failed("build_failed", "Couldn't build that just now. Say so and offer to try a smaller version.")
        val html = clean(raw)
        if (!html.contains("<html", ignoreCase = true) && !html.contains("<!doctype", ignoreCase = true)) {
            return Results.failed("build_failed", "The result wasn't a web page. Say so and offer to try again.")
        }
        val folder = File(host.context.filesDir, FOLDER).apply { mkdirs() }
        val file = File(folder, slug(title) + ".html")
        runCatching { file.writeText(html) }.onFailure {
            Diag.w("build_site: couldn't save (${it.javaClass.simpleName})")
            return Results.failed("save_failed", "Couldn't save the page on the phone.")
        }
        val intent = Intent(host.context, SitePreviewActivity::class.java)
            .putExtra(SitePreviewActivity.EXTRA_PATH, file.absolutePath)
            .putExtra(SitePreviewActivity.EXTRA_TITLE, title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return host.openApp(
            intent,
            "Preview",
            "The page \"$title\" is built and its live preview opens on the phone after you speak. " +
                "Tell the user in one sentence what you built, and offer to change it (call build_site again with the full updated request).",
        )
    }

    private fun clean(text: String): String {
        var s = text.trim()
        if (s.startsWith("```")) s = s.substringAfter('\n')
        if (s.endsWith("```")) s = s.removeSuffix("```")
        return s.trim()
    }

    private fun slug(title: String): String =
        title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "site" }.take(40)

    private const val SYSTEM =
        "You are an expert front-end developer. Write ONE complete, self-contained HTML file (inline CSS and " +
            "JavaScript; no external files, no CDN links, no external images, fonts or network calls) for the user's request. " +
            "It must work well on a phone: mobile-first, responsive, large touch targets, good-looking, with a dark theme. " +
            "Make it really work (real logic, no placeholders). Keep data in memory or localStorage. " +
            "Reply with the HTML only, starting with <!DOCTYPE html>: no explanation and no markdown fences."
}

/** One Gemini text request over REST, with the user's own key. Returns the text or null. */
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
    ): String? {
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
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Diag.w("gemini text: HTTP ${response.code}")
                    return null
                }
                val root = JSONObject(response.body!!.string())
                val candidate = root.getJSONArray("candidates").getJSONObject(0)
                val parts = candidate.getJSONObject("content").getJSONArray("parts")
                val text = buildString {
                    for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text"))
                }.trim()
                if (text.isEmpty()) return null
                text + sources(candidate)
            }
        }.onFailure { Diag.w("gemini text: ${it.javaClass.simpleName}") }.getOrNull()
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
