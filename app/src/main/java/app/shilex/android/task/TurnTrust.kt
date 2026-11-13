package app.shilex.android.task

/**
 * Whose words the model is acting on. A turn starts clean when the user speaks; once a tool
 * has handed the model text from someone else (any app's screen), a request to call, message
 * or book could have been planted there ("tap to message this number"). From then until the
 * user speaks again, High and Financial tools and changes to memory or routines need the
 * user's tap on Allow, and nothing is learned. This is a code rule, not a prompt warning: a fooled model can't talk past it.
 */
class TurnTrust {
    enum class Verdict { Allow, AskUser }

    @Volatile var outsideText = false
        private set

    /** Fresh words from the user (speech or typing): what follows is their request again. */
    fun userSpoke() {
        outsideText = false
    }

    /** Called after every tool, whatever its status: a failed read may still carry screen text. */
    fun after(tool: String) {
        if (tool in READS_OUTSIDE_TEXT) outsideText = true
    }

    fun verdict(capability: Capability): Verdict =
        if (outsideText && (capability.risk >= Risk.High || capability.tool in SELF_WRITES)) Verdict.AskUser else Verdict.Allow

    /** Habits learned now could be steered by the screen, so learning waits for a clean turn. */
    val mayLearn get() = !outsideText

    companion object {
        /** What JARVIS keeps about the user: planted text must not change it. */
        val SELF_WRITES = setOf("remember", "forget", "routine_save")

        /** Tools whose results include another app's screen text or other people's notifications. */
        val READS_OUTSIDE_TEXT = setOf(
            "ui_read", "screen_look", "research", "ui_click", "ui_tap_seen", "ui_type", "ui_scroll", "ui_key", "open_app", "book_tickets",
            "notifications_check", "hand_to_user", "calendar_list",
        )
    }
}
