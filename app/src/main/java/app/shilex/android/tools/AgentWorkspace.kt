package app.shilex.android.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.shilex.android.data.KeyVault
import app.shilex.android.ui.SitePreviewActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * The agent's workspace: the pages it builds live in files/sites, a tiny web server on this
 * phone (127.0.0.1 only) lets Chrome open them, and the terminal screen shows what it is doing.
 * It is not a Linux shell: the commands are the small set in [HELP].
 */
object AgentWorkspace {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _log = MutableStateFlow(listOf("Masuka terminal. Type what to build (for example: a calculator website), or help."))
    val log: StateFlow<List<String>> = _log.asStateFlow()

    fun say(line: String) {
        _log.update { (it + line.split('\n')).takeLast(400) }
    }

    private fun folder(context: Context): File = File(context.filesDir, "sites").apply { mkdirs() }

    private fun slug(title: String): String =
        title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "site" }.take(40)

    private fun titleOf(text: String): String = text.split(Regex("\\s+")).take(4).joinToString(" ")

    /** Builds (or changes) a page in the background; progress goes to the terminal, Chrome opens at the end. */
    fun startBuild(context: Context, http: OkHttpClient, request: String, title: String, editOf: String?) {
        val app = context.applicationContext
        scope.launch {
            say("▶ build: ${title.ifEmpty { titleOf(request) }}")
            val key = KeyVault(app).read(KeyVault.GEMINI_KEY)
            if (key == null) {
                say("✖ no Gemini key. Add it in Masuka Settings.")
                return@launch
            }
            val dir = folder(app)
            val old = editOf?.let { File(dir, slug(it) + ".html").takeIf { f -> f.isFile }?.readText() }
            if (editOf != null && old == null) say("! no page called $editOf, building a new one")
            val prompt = if (old != null) {
                "Here is the current page:\n$old\n\nChange it like this: $request\nReturn the complete updated HTML file."
            } else {
                request
            }
            say("▶ asking Gemini to write the code (up to two minutes)...")
            val gen = GeminiText.generate(http, key, prompt, BUILD_SYSTEM, search = false, maxTokens = 16000, seconds = 120)
            val raw = gen.text
            if (raw == null) {
                say("✖ failed: ${gen.error}")
                return@launch
            }
            val html = clean(raw)
            if (!html.contains("<html", ignoreCase = true) && !html.contains("<!doctype", ignoreCase = true)) {
                say("✖ the reply wasn't a web page. Try again, or ask for a smaller version.")
                return@launch
            }
            val name = slug(if (old != null) editOf!! else title.ifEmpty { titleOf(request) })
            runCatching { File(dir, "$name.html").writeText(html) }.onFailure {
                say("✖ couldn't save the page on the phone")
                return@launch
            }
            say("✔ saved $name.html (${html.length} characters)")
            show(app, name, chrome = true)
        }
    }

    /** Shows a saved page in Chrome (through the local server) or in the built-in preview. */
    private fun show(context: Context, name: String, chrome: Boolean) {
        if (chrome) {
            if (!SiteServer.start(folder(context))) {
                say("✖ couldn't start the preview server (port ${SiteServer.PORT} is busy)")
                return
            }
            val url = "http://127.0.0.1:${SiteServer.PORT}/$name.html"
            say("✔ preview: $url")
            if (openInChrome(context, url)) {
                say("  opened in Chrome. Keep Masuka running in the background while you look.")
            } else {
                say("  couldn't open Chrome from here. Open it yourself: $url")
            }
        } else {
            val intent = Intent(context, SitePreviewActivity::class.java)
                .putExtra(SitePreviewActivity.EXTRA_PATH, File(folder(context), "$name.html").absolutePath)
                .putExtra(SitePreviewActivity.EXTRA_TITLE, name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }.onFailure { say("✖ couldn't open the preview") }
        }
    }

    private fun openInChrome(context: Context, url: String): Boolean {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val chrome = Intent(view).setPackage("com.android.chrome")
        return runCatching { context.startActivity(chrome); true }
            .recoverCatching { context.startActivity(view); true }
            .getOrDefault(false)
    }

    /** Runs one line typed in the terminal. */
    fun exec(context: Context, http: OkHttpClient, input: String) {
        val app = context.applicationContext
        val text = input.trim()
        if (text.isEmpty()) return
        say("$ $text")
        val word = text.substringBefore(' ').lowercase()
        val rest = text.substringAfter(' ', "").trim()
        when (word) {
            "help" -> say(HELP)
            "clear" -> _log.value = emptyList()
            "ls" -> list(app)
            "cat" -> cat(app, rest)
            "rm" -> remove(app, rest)
            "open" -> withPage(app, rest) { show(app, it, chrome = true) }
            "preview" -> withPage(app, rest) { show(app, it, chrome = false) }
            "research" -> research(app, http, rest)
            "edit" -> {
                val name = rest.substringBefore(' ')
                val change = rest.substringAfter(' ', "").trim()
                if (name.isEmpty() || change.isEmpty()) say("usage: edit <name> <what to change>") else startBuild(app, http, change, name, name)
            }
            "build" -> if (rest.isEmpty()) say("usage: build <what to build>") else startBuild(app, http, rest, titleOf(rest), null)
            else -> startBuild(app, http, text, titleOf(text), null)
        }
    }

    private fun pages(context: Context): List<File> =
        folder(context).listFiles { f -> f.isFile && f.extension == "html" }.orEmpty().sortedByDescending { it.lastModified() }

    /** The named page, or the newest one when no name is given. */
    private fun withPage(context: Context, name: String, action: (String) -> Unit) {
        val all = pages(context)
        val file = if (name.isEmpty()) all.firstOrNull() else all.firstOrNull { it.nameWithoutExtension == slug(name) }
        if (file == null) say("no such page. Type ls to see what is there.") else action(file.nameWithoutExtension)
    }

    private fun list(context: Context) {
        val all = pages(context)
        if (all.isEmpty()) say("(nothing built yet)") else all.forEach { say("• ${it.nameWithoutExtension}  ${it.length()} bytes") }
    }

    private fun cat(context: Context, name: String) = withPage(context, name) { page ->
        val lines = File(folder(context), "$page.html").readLines()
        say(lines.take(60).joinToString("\n"))
        if (lines.size > 60) say("... ${lines.size - 60} more lines")
    }

    private fun remove(context: Context, name: String) {
        if (name.isEmpty()) {
            say("usage: rm <name>")
            return
        }
        withPage(context, name) { page ->
            say(if (File(folder(context), "$page.html").delete()) "deleted $page" else "couldn't delete $page")
        }
    }

    private fun research(context: Context, http: OkHttpClient, question: String) {
        if (question.isEmpty()) {
            say("usage: research <question>")
            return
        }
        scope.launch {
            val key = KeyVault(context).read(KeyVault.GEMINI_KEY)
            if (key == null) {
                say("✖ no Gemini key. Add it in Masuka Settings.")
                return@launch
            }
            say("▶ searching the web...")
            val gen = GeminiText.generate(http, key, question, RESEARCH_SYSTEM, search = true, maxTokens = 1500, seconds = 60)
            say(gen.text ?: "✖ failed: ${gen.error}")
        }
    }

    const val RESEARCH_SYSTEM =
        "You are a careful research assistant. Search the web, then answer in at most eight short sentences with " +
            "the key facts and figures. Say which sources you used. If sources disagree, say so. Never invent facts."

    private const val BUILD_SYSTEM =
        "You are an expert front-end developer. Write ONE complete, self-contained HTML file (inline CSS and " +
            "JavaScript; no external files, no CDN links, no external images, fonts or network calls) for the user's request. " +
            "It must work well on a phone: mobile-first, responsive, large touch targets, good-looking, with a dark theme. " +
            "Make it really work (real logic, no placeholders). Keep data in memory or localStorage. " +
            "Reply with the HTML only, starting with <!DOCTYPE html>: no explanation and no markdown fences."

    private fun clean(text: String): String {
        var s = text.trim()
        if (s.startsWith("```")) s = s.substringAfter('\n')
        if (s.endsWith("```")) s = s.removeSuffix("```")
        return s.trim()
    }

    private const val HELP =
        "Just type what to build, e.g. a snake game  (or: build <what>)\n" +
            "edit <name> <change>   change a page you built\n" +
            "open [name]            open in Chrome (newest if no name)\n" +
            "preview [name]         open in the built-in preview\n" +
            "ls                     list your pages\n" +
            "cat [name]             show the code\n" +
            "rm <name>              delete a page\n" +
            "research <question>    search the web\n" +
            "clear                  clear this screen"
}

/** A tiny web server for the pages, reachable only from this phone (127.0.0.1). */
internal object SiteServer {
    const val PORT = 8765
    private val NAME = Regex("[a-z0-9-]{1,40}")

    @Volatile
    private var socket: ServerSocket? = null

    @Synchronized
    fun start(root: File): Boolean {
        if (socket?.isClosed == false) return true
        val s = runCatching { ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1")) }.getOrNull() ?: return false
        socket = s
        thread(isDaemon = true, name = "site-server") {
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { handle(client, root) } }
            }
        }
        return true
    }

    private fun handle(client: Socket, root: File) {
        client.use { sock ->
            sock.soTimeout = 5_000
            val reader = sock.getInputStream().bufferedReader()
            val request = reader.readLine() ?: return
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val parts = request.split(' ')
            val path = parts.getOrNull(1).orEmpty().substringBefore('?').trim('/')
            val out = sock.getOutputStream()
            if (parts.getOrNull(0) != "GET") {
                reply(out, 405, "text/plain", "Method not allowed".toByteArray())
                return
            }
            if (path.isEmpty()) {
                reply(out, 200, "text/html; charset=utf-8", index(root).toByteArray())
                return
            }
            val name = path.removeSuffix(".html")
            val file = File(root, "$name.html")
            if (!NAME.matches(name) || !file.isFile) {
                reply(out, 404, "text/plain", "Not found".toByteArray())
                return
            }
            reply(out, 200, "text/html; charset=utf-8", file.readBytes())
        }
    }

    private fun index(root: File): String {
        val links = root.listFiles { f -> f.isFile && f.extension == "html" }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .joinToString("") { "<li><a href=\"/${it.name}\">${it.nameWithoutExtension}</a></li>" }
        return "<!DOCTYPE html><meta name=viewport content=\"width=device-width,initial-scale=1\">" +
            "<body style=\"font-family:sans-serif;background:#0a0620;color:#fff;padding:24px\"><h2>Masuka pages</h2><ul>$links</ul></body>"
    }

    private fun reply(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val status = when (code) {
            200 -> "OK"
            404 -> "Not Found"
            else -> "Method Not Allowed"
        }
        val head = "HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Content-Security-Policy: default-src 'self' 'unsafe-inline' data: blob:\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }
}
