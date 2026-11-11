package app.shilex.android.tools

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import app.shilex.android.Branding
import app.shilex.android.control.ControlRules
import app.shilex.android.control.ControlService
import app.shilex.android.control.ScreenText
import app.shilex.android.control.SightAim
import app.shilex.android.data.KeyVault
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Phone control through Accessibility, same tool names as desktop. The model reads the
 * screen, then taps / types / scrolls by element number. Risky taps wait for the user's
 * Allow on JARVIS's own card; money, password and settings apps are never touched.
 */
object ScreenTools {
    fun all(): List<PhoneTool> = listOf(UiRead, UiClick, UiTapSeen, UiType, UiScroll, UiKey, HandToUser, ScreenLook)

    /** The control service, or the tool result explaining why it can't be used. */
    private fun control(): Pair<ControlService?, JSONObject?> {
        val service = ControlService.instance
            ?: return null to Results.failed(
                "control_off",
                "Phone control isn't connected. Tell the user: in Android Accessibility, switch ${Branding.ASSISTANT_NAME} off and on again.",
            )
        if (!app.shilex.android.data.Settings(service.applicationContext).screenReading) {
            return null to Results.failed(
                "screen_reading_off",
                "Screen reading is off. Tell the user to turn on Screen reading in ${Branding.ASSISTANT_NAME} Settings.",
            )
        }
        if (service.overlay.stopped) {
            return null to Results.failed("stopped_by_user", "The user pressed Stop. Don't control the phone until they ask again.")
        }
        return service to null
    }

    /** Reads the screen after an action, so the model sees the result without another call. */
    private suspend fun afterAction(service: ControlService, did: String): JSONObject {
        service.overlay.showActive()
        delay(SETTLE_MS)
        val snap = service.snapshot() ?: return Results.done("$did The screen couldn't be read.")
        if (snap.blocked) return Results.done("$did Now in ${snap.appLabel}, which ${Branding.ASSISTANT_NAME} doesn't read.")
        return Results.done("$did Screen now:\n" + ScreenText.describe(snap.appLabel, snap.elements, snap.more))
    }

    private fun blocked(app: String) = Results.failed(
        "blocked",
        "${Branding.ASSISTANT_NAME} doesn't read or touch $app (money, passwords, app store or settings). The user does this part.",
    )

    /** Paying is always the user's: a pay or buy tap is refused, not offered on the Allow card. */
    private fun payment(label: String) = Results.failed(
        "payment_is_users",
        "\"${ScreenText.shorten(label.ifEmpty { "This" })}\" pays. Stop here: tell the user the total on screen and that they " +
            "tap it and pay themselves. Don't try another way.",
    )

    private object UiRead : PhoneTool {
        override val name = "ui_read"

        override fun declaration() = Schema.function(
            name,
            "Reads the app on screen as a numbered list of buttons, fields and text. Use before tapping.",
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val (service, problem) = control()
            service ?: return problem!!
            val snap = service.snapshot() ?: return Results.failed("no_screen", "The screen couldn't be read.")
            if (snap.packageName == host.context.packageName) {
                return Results.failed(
                    "own_screen",
                    "The user is looking at ${Branding.ASSISTANT_NAME} itself. Ask them to open the app they mean, or offer open_app.",
                )
            }
            if (snap.blocked) return blocked(snap.appLabel)
            return Results.done(ScreenText.describe(snap.appLabel, snap.elements, snap.more))
        }
    }

    private object UiClick : PhoneTool {
        override val name = "ui_click"

        override fun declaration() = Schema.function(
            name,
            "Taps an element from the last ui_read, by number (preferred) or exact label. Send, call, " +
                "delete, book and similar taps show the user an Allow button first; pay and buy taps are refused.",
            Schema.integer("number", "Element number from ui_read."),
            Schema.string("label", "The element's label, if no number."),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val (service, problem) = control()
            service ?: return problem!!
            val node = target(service, args) ?: return Results.failed(
                "not_found",
                "No such element on screen now. Call ui_read and use a number from it.",
            )
            node.second?.let { return it }
            val target = node.first!!
            val pkg = target.packageName?.toString().orEmpty()
            if (ControlRules.blocked(pkg)) return blocked(pkg)
            val label = service.labelOf(target)
            val risk = ControlRules.clickRisk(label, target.viewIdResourceName.orEmpty())
            if (risk == ControlRules.Risk.Money) return payment(label)
            if (risk != ControlRules.Risk.Safe) {
                val what = "Tap \"${ScreenText.shorten(label.ifEmpty { "this button" })}\""
                if (!service.overlay.confirm(what)) {
                    return Results.failed("cancelled", "The user didn't allow it. Don't try again unless they ask.")
                }
            }
            if (!service.click(target)) return Results.failed("tap_failed", "The tap didn't go through.")
            return afterAction(service, "Tapped \"${ScreenText.shorten(label)}\".")
        }

        /** (node, null) when found, (null, question) when several labels match, null when none. */
        private fun target(service: ControlService, args: JSONObject): Pair<AccessibilityNodeInfo?, JSONObject?>? {
            val number = args.optInt("number", 0)
            if (number > 0) return service.node(number)?.let { it to null }
            val label = args.text("label")
            val snap = service.snapshot() ?: return null
            if (snap.blocked) return null to blocked(snap.appLabel)
            val matches = ScreenText.find(snap.elements, label)
            return when {
                matches.isEmpty() -> null
                matches.size > 1 -> null to Results.ask(
                    "Several elements match. Ask which, or use a number.",
                    matches.take(6).map { "${it.number}. ${it.label}" },
                )
                else -> service.node(matches.single().number)?.let { it to null }
            }
        }
    }

    private object UiType : PhoneTool {
        override val name = "ui_type"

        override fun declaration() = Schema.function(
            name,
            "Types text into a field (replacing what's there). Doesn't send; tap Send separately.",
            Schema.string("text", "Exactly what to type, in the user's words.", required = true),
            Schema.integer("number", "Field number from ui_read; empty for the focused field."),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val (service, problem) = control()
            service ?: return problem!!
            val number = args.optInt("number", 0)
            val field = (if (number > 0) service.node(number) else service.focusedField())
                ?.takeIf { it.isEditable }
                ?: return Results.failed("no_field", "No text field selected. Call ui_read and give the field's number.")
            if (ControlRules.blocked(field.packageName?.toString().orEmpty())) return blocked("this app")
            if (!service.type(field, args.text("text"))) return Results.failed("type_failed", "Typing didn't work in that field.")
            return afterAction(service, "Typed it.")
        }
    }

    /**
     * Click-by-sight: for buttons ui_read can't name (games, image-only buttons, canvases).
     * The tap's risk is the worst of what the model asked for, what Gemini saw there and the
     * control Android reports at that point, so a mislabelled Send still asks for Allow.
     */
    private object UiTapSeen : PhoneTool {
        override val name = "ui_tap_seen"

        override fun declaration() = Schema.function(
            name,
            "Taps something by looking at the screen. Only when ui_read doesn't list it (image buttons, " +
                "games). Sends a screenshot to Gemini. Send, call, delete and similar taps show Allow first; pay is refused.",
            Schema.string("target", "What to tap, as seen: e.g. \"the red play button in the middle\".", required = true),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val (service, problem) = control()
            service ?: return problem!!
            val target = args.text("target").ifEmpty { return Results.failed("no_target", "Say what to tap.") }
            val pkg = service.foregroundPackage()
            if (pkg == host.context.packageName) return Results.failed("own_screen", "That's ${Branding.ASSISTANT_NAME} itself.")
            if (ControlRules.blocked(pkg)) return blocked("this app")
            val key = KeyVault(host.context).read(KeyVault.GEMINI_KEY)
                ?: return Results.failed("no_key", "Looking at the screen needs the Gemini key.")
            val shot = service.screenshot() ?: return Results.failed(
                "no_sight",
                "This screen can't be looked at (protected, or Android older than 11). The user taps this part.",
            )
            val seen = when (val found = SightAim(host.http, key).locate(shot, target)) {
                is SightAim.Outcome.Found -> found
                SightAim.Outcome.NotFound -> return Results.failed("not_seen", "Couldn't see \"$target\" on screen. Say what you see instead.")
                is SightAim.Outcome.Failed -> return Results.failed("sight_failed", found.message)
            }
            val node = service.nodeAt(seen.x, seen.y)
            val there = node?.packageName?.toString().orEmpty()
            if (there == host.context.packageName) return Results.failed("own_screen", "That spot is ${Branding.ASSISTANT_NAME}'s own button.")
            if (ControlRules.blocked(there)) return blocked("this app")
            val label = node?.let(service::labelOf).orEmpty()
            val risk = listOf(
                ControlRules.clickRisk(target, ""),
                ControlRules.clickRisk(seen.sees, ""),
                ControlRules.clickRisk(label, node?.viewIdResourceName.orEmpty()),
            ).maxBy { it.ordinal }
            val name = ScreenText.shorten(label.ifEmpty { seen.sees }.ifEmpty { target })
            if (risk == ControlRules.Risk.Money) return payment(name)
            if (risk != ControlRules.Risk.Safe && !service.overlay.confirm("Tap \"$name\"")) {
                return Results.failed("cancelled", "The user didn't allow it. Don't try again unless they ask.")
            }
            if (!service.tapAt(seen.x, seen.y)) return Results.failed("tap_failed", "The tap didn't go through.")
            return afterAction(service, "Tapped \"$name\" (by sight).")
        }
    }

    /**
     * Secure-screen handover: the steps JARVIS must not do (paying, a PIN, a password, an OTP,
     * a banking or UPI app) go to the user with a "Your turn" card; after Done, JARVIS reads
     * the screen again and carries on with the rest of the task.
     */
    private object HandToUser : PhoneTool {
        override val name = "hand_to_user"

        override fun declaration() = Schema.function(
            name,
            "Gives one step to the user and waits (up to 2.5 min) until they tap Done: paying, a PIN, " +
                "password or OTP, a banking/UPI app, or anything a tool said the user must do. Then " +
                "returns the screen so you can finish the rest.",
            Schema.string("step", "The step in a few words, e.g. \"Enter your UPI PIN and pay\".", required = true),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val (service, problem) = control()
            service ?: return problem!!
            val step = ScreenText.shorten(args.text("step").ifEmpty { "Finish this step" })
            if (!service.overlay.handover(step)) {
                return Results.failed("stopped", "The user stopped or didn't finish. Ask what they'd like; don't carry on by yourself.")
            }
            val pkg = service.foregroundPackage()
            if (pkg == host.context.packageName || ControlRules.blocked(pkg)) {
                return Results.done("The user tapped Done. The screen now is one ${Branding.ASSISTANT_NAME} doesn't read; ask what's next if unsure.")
            }
            return afterAction(service, "The user tapped Done.")
        }
    }

    private object UiScroll : PhoneTool {
        override val name = "ui_scroll"

        override fun declaration() = Schema.function(
            name,
            "Scrolls the screen to show more.",
            Schema.string("direction", "down shows what's further down.", required = true, choices = listOf("down", "up")),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val (service, problem) = control()
            service ?: return problem!!
            if (ControlRules.blocked(service.foregroundPackage())) return blocked("this app")
            if (!service.scroll(down = args.text("direction") != "up")) return Results.failed("no_scroll", "Nothing to scroll here.")
            return afterAction(service, "Scrolled.")
        }
    }

    private object UiKey : PhoneTool {
        override val name = "ui_key"

        override fun declaration() = Schema.function(
            name,
            "Presses a phone key: back, home, recents, notifications (pull down the shade).",
            Schema.string("key", "Which key.", required = true, choices = KEYS.keys.toList()),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val (service, problem) = control()
            service ?: return problem!!
            val action = KEYS[args.text("key")] ?: return Results.failed("bad_key", "Use back, home, recents or notifications.")
            if (!service.performGlobalAction(action)) return Results.failed("key_failed", "The phone didn't take that key.")
            return afterAction(service, "Pressed ${args.text("key")}.")
        }

        private val KEYS = mapOf(
            "back" to AccessibilityService.GLOBAL_ACTION_BACK,
            "home" to AccessibilityService.GLOBAL_ACTION_HOME,
            "recents" to AccessibilityService.GLOBAL_ACTION_RECENTS,
            "notifications" to AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
        )
    }

    private const val SETTLE_MS = 900L
}
