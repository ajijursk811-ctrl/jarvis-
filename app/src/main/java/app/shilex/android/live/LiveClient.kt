package app.shilex.android.live

import app.shilex.android.Diag
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** One Gemini Live WebSocket session. Callbacks arrive on OkHttp's thread. */
class LiveClient(private val http: OkHttpClient) {

    interface Listener {
        fun onEvent(event: LiveEvent)

        /** [problem] is null when we closed it ourselves. */
        fun onClosed(problem: String?)
    }

    @Volatile private var socket: WebSocket? = null

    fun connect(apiKey: String, setup: LiveProtocol.Setup, listener: Listener) {
        close()
        // The key goes in a header, not the URL, so it never shows up in logs.
        val request = Request.Builder()
            .url(URL)
            .header("x-goog-api-key", apiKey)
            .build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(LiveProtocol.setupMessage(setup))
            }

            override fun onMessage(webSocket: WebSocket, text: String) = deliver(text)

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = deliver(bytes.utf8())

            private fun deliver(text: String) {
                if (socket == null) return
                LiveProtocol.parse(text).forEach(listener::onEvent)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket !== webSocket) return
                socket = null
                Diag.w("socket closed by server: code=$code reason=${Diag.clean(reason)}")
                listener.onClosed(LiveProtocol.describeClose(code, reason))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket !== webSocket) return
                socket = null
                Diag.w(
                    "socket failed: ${t.javaClass.simpleName} ${Diag.clean(t.message)} " +
                        "http=${response?.code ?: "-"} ${Diag.clean(response?.message)}",
                )
                listener.onClosed(LiveProtocol.describeClose(response?.code ?: -1, response?.message.orEmpty()))
            }
        })
    }

    fun send(message: String) {
        socket?.send(message)
    }

    fun sendAudio(pcm: ByteArray) {
        socket?.send(LiveProtocol.audioMessage(pcm))
    }

    fun sendVideo(jpeg: ByteArray) {
        socket?.send(LiveProtocol.videoMessage(jpeg))
    }

    fun close() {
        val current = socket ?: return
        socket = null
        current.close(1000, null)
    }

    private companion object {
        const val URL = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }
}
