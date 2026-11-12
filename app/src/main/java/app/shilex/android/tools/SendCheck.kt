package app.shilex.android.tools

import app.shilex.android.control.ControlService
import app.shilex.android.control.ScreenElement
import app.shilex.android.task.TaskTracker
import kotlinx.coroutines.delay

/**
 * Was a message really sent? Only what the screen shows counts: the text sits in the chat
 * and no longer in the typing box. Opening the chat with the text typed in is not "sent".
 */
object SendCheck {
    fun sent(elements: List<ScreenElement>, message: String): Boolean {
        val head = key(message)
        if (head.isEmpty()) return false
        val stillTyped = elements.any { it.role == "field" && head in key(it.value) }
        val inChat = elements.any { it.role != "field" && head in key(it.label) }
        return inChat && !stillTyped
    }

    private fun key(text: String) = text.lowercase().filter { it.isLetterOrDigit() }.take(40)

    /**
     * Watches WhatsApp for up to [timeoutMs] after it opens; tells the task and the model what
     * really happened. Needs phone control; without it the result stays "you tap Send".
     */
    suspend fun watchWhatsApp(message: String, autoSend: Boolean = false, timeoutMs: Long = 90_000L): String? {
        val service = ControlService.instance ?: return null
        val until = System.currentTimeMillis() + timeoutMs
        var tapped = false
        while (System.currentTimeMillis() < until) {
            delay(1_500)
            val snap = service.snapshot() ?: continue
            if (snap.packageName !in WHATSAPP) continue
            if (sent(snap.elements, message)) {
                TaskTracker.verified(true, "Sent: the message now shows in the chat.")
                return "The phone saw the message appear in the WhatsApp chat, so it was sent."
            }
            if (autoSend && !tapped && !service.overlay.stopped) {
                val typed = snap.elements.any { it.role == "field" && key(message) in key(it.value) }
                val send = snap.elements.firstOrNull { it.role == "button" && SEND_LABEL.matches(it.label.trim()) }
                val node = send?.let { service.node(it.number) }
                if (typed && node != null && service.click(node)) tapped = true
            }
        }
        TaskTracker.verified(false, "Not seen as sent. If you still want it, tap Send in WhatsApp.")
        return "The message was not seen as sent. Don't tell the user it was sent."
    }

    private val WHATSAPP = setOf("com.whatsapp", "com.whatsapp.w4b")
    private val SEND_LABEL = Regex("(?i)(send|भेजें|भेजे|bhejo|bhejein)")
}
