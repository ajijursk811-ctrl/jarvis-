package app.shilex.android.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.shilex.android.Branding
import app.shilex.android.Diag
import app.shilex.android.MainActivity
import app.shilex.android.local.ActivityLog
import app.shilex.android.local.LocalCommand
import app.shilex.android.local.MediaKey
import app.shilex.android.learn.Habits
import app.shilex.android.data.Settings as AppSettings
import app.shilex.android.R
import app.shilex.android.control.ControlService
import app.shilex.android.live.FunctionCall
import app.shilex.android.live.FunctionResult
import app.shilex.android.tools.AgentTools
import app.shilex.android.tools.AppTools
import app.shilex.android.tools.BookingTools
import app.shilex.android.tools.CalendarTools
import app.shilex.android.tools.ClockTools
import app.shilex.android.tools.CommsTools
import app.shilex.android.tools.DeviceTools
import app.shilex.android.tools.InfoTools
import app.shilex.android.tools.MailTools
import app.shilex.android.tools.MediaTools
import app.shilex.android.tools.MemoryTools
import app.shilex.android.tools.NotifyTools
import app.shilex.android.tools.ReminderTools
import app.shilex.android.tools.Results
import app.shilex.android.tools.ScreenTools
import app.shilex.android.tools.ToolHost
import app.shilex.android.task.Capabilities
import app.shilex.android.task.DuplicateGuard
import app.shilex.android.task.Finish
import app.shilex.android.task.Risk
import app.shilex.android.task.TaskTracker
import app.shilex.android.task.TurnTrust
import app.shilex.android.notify.Replies
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs the model's tool calls in order and answers them. Apps open only after the spoken
 * reply, one per turn; anything that sends or calls is finished by the user in that app.
 * Android blocks opening apps while JARVIS is in the background, so then a notification
 * waits for the user's tap instead.
 */
class ToolRunner(
    override val context: Context,
    override val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val answer: (List<FunctionResult>) -> Unit,
    private val askPermission: (String) -> Unit,
    private val onScreen: () -> Boolean,
    private val note: (String) -> Unit,
    private val endSoon: () -> Unit,
) : ToolHost {
    private val tools = (
        CommsTools.all() + ClockTools.all() + ReminderTools.all() + MediaTools.all() + InfoTools.all() +
            ScreenTools.all() + AppTools.all() + BookingTools.all() + DeviceTools.all() + NotifyTools.all() + CalendarTools.all() + MailTools.all() + MemoryTools.all() + AgentTools.all()
        ).associateBy { it.name }

    private val duplicates = DuplicateGuard()
    private val trust = TurnTrust()
    private val cancelled = Collections.synchronizedSet(mutableSetOf<String>())
    private class Pending(val intent: Intent, val label: String, val then: (suspend () -> String?)?)

    @Volatile private var pending: Pending? = null

    fun declarations(): JSONArray = JSONArray().apply {
        tools.values.filter { Capabilities.of(it.name) != null }.forEach { put(it.declaration()) }
    }

    fun handle(calls: List<FunctionCall>) {
        scope.launch(Dispatchers.Default) {
            val results = calls.map { call ->
                // Only tools in the capability registry run; the model can't invent others.
                val capability = Capabilities.of(call.name)
                val tool = tools[call.name]?.takeIf { capability != null }
                val args = call.args.toString()
                val sensitive = capability != null && capability.risk >= Risk.High
                val clean = trust.mayLearn
                val response = if (tool == null) {
                    Results.failed("unknown_tool", "There is no tool called ${call.name}.")
                } else if (sensitive && !duplicates.allow(call.name, args)) {
                    Diag.w("tool: ${call.name} refused as a duplicate")
                    Results.failed("duplicate", "This exact action was just done. Tell the user; repeat only if they ask again in new words.")
                } else if (trust.verdict(capability!!) == TurnTrust.Verdict.AskUser && !allowedAfterScreen(capability.title)) {
                    Diag.i("tool: ${call.name} held, screen text was read this turn")
                    Results.failed(
                        "not_allowed",
                        "Screen text was read this turn, so this needed the user's Allow tap and it wasn't given. " +
                            "Ask the user in your own words whether they want it; do it only if they say so.",
                    )
                } else {
                    TaskTracker.step(call.name, capability.title)
                    try {
                        withTimeout(if (call.name == "hand_to_user") HANDOVER_TIMEOUT_MS else TIMEOUT_MS) { tool.run(call.args, this@ToolRunner) }
                    } catch (_: TimeoutCancellationException) {
                        Results.failed("timeout", "That took too long. Say so and offer to try again.")
                    } catch (e: Exception) {
                        Diag.w("tool: ${call.name} crashed: ${e.javaClass.simpleName}")
                        Results.failed("error", "Something went wrong on the phone. Say so plainly.")
                    }
                }
                Diag.i("tool: ${call.name} -> ${response.optString("status")} ${response.optString("code")}".trim())
                if (tool != null) trust.after(call.name)
                val status = response.optString("status")
                if (status == "done" && clean) learnFrom(call)
                if (sensitive && status != "done" && status != "needs_user_tap") duplicates.forget(call.name, args)
                if (tool != null && response.optString("code") != "duplicate") {
                    val handover = capability?.handover.orEmpty().takeIf { status == "done" || status == "needs_user_tap" }.orEmpty()
                    TaskTracker.result(call.name, status, handover)
                }
                val outcome = if (capability?.finish == Finish.User && status == "done") "Opened; you finish it." else ActivityLog.statusLabel(status)
                ActivityLog.add(ActivityLog.Entry(capability?.title ?: ActivityLog.toolLabel(call.name), outcome, local = false))
                FunctionResult(call.id, call.name, response)
            }
            answer(results.filterNot { it.id in cancelled })
        }
    }

    fun cancel(ids: List<String>) {
        cancelled += ids
    }

    /** The user spoke or typed: their words lead again ([TurnTrust]) and may answer a reply read-back. */
    fun userSpoke(text: String = "") {
        trust.userSpoke()
        Replies.heard(text)
    }

    /** JARVIS's own Allow card, which only a finger can press. No card (control off): not allowed. */
    private suspend fun allowedAfterScreen(title: String): Boolean {
        val overlay = ControlService.instance?.overlay?.takeUnless { it.stopped } ?: return false
        return overlay.confirm("$title (asked after reading the screen)")
    }

    /** Opens the app a tool queued, once the reply has been spoken. */
    fun openPending() {
        val open = pending ?: return
        pending = null
        if (!canOpenApps()) {
            offerTap(open.intent, open.label)
            return
        }
        Diag.i("tool: opening ${open.label}")
        if (start(open.intent)) open.then?.let { after -> scope.launch { after()?.let(note) } }
    }

    /** A phone command Gemini carried out: counts as use, and teaches the words that led to it. */
    private fun learnFrom(call: FunctionCall) {
        if (!AppSettings(context).learning) return
        val command = when (call.name) {
            "device_control" -> DeviceTools.command(call.args.optString("action"), call.args.optInt("percent", -1))
            "open_app" -> call.args.optString("name").trim().lowercase().takeIf { it.isNotEmpty() }?.let { LocalCommand.OpenApp(it) }
            "media_control" -> MediaKey.entries.firstOrNull { it.name.equals(call.args.optString("action"), ignoreCase = true) }?.let { LocalCommand.Media(it) }
            else -> null
        } ?: return
        val habits = Habits.get(context)
        habits.learn(command)
        habits.record(command, java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY))
    }

    fun reset() {
        pending = null
        cancelled.clear()
        trust.userSpoke()
    }

    override fun openApp(intent: Intent, label: String, done: String, then: (suspend () -> String?)?): JSONObject {
        if (pending != null) {
            return Results.failed(
                "one_app_per_turn",
                "Another app is already opening this turn. Tell the user to ask for this one after.",
            )
        }
        if (!canOpenApps()) return offerTap(intent, label)
        pending = Pending(intent, label, then)
        return Results.done(done)
    }

    override fun endSessionSoon() = endSoon()

    override fun startNow(intent: Intent, label: String, done: String): JSONObject = when {
        !canOpenApps() -> offerTap(intent, label)
        start(intent) -> Results.done(done)
        else -> Results.failed("no_app", "The phone's $label app didn't accept it.")
    }

    override fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    override fun requestPermission(permission: String) {
        askPermission(permission)
        // Android shows the prompt only on JARVIS's own screen; from the orb or the wake word, bring it up.
        if (!onScreen()) start(Intent(context, MainActivity::class.java))
    }

    // Android lets apps with a running Accessibility service open other apps from the background.
    private fun canOpenApps() = onScreen() || ControlService.instance != null || Settings.canDrawOverlays(context)

    /** A heads-up notification that opens [intent] when tapped (Android allows that). */
    private fun offerTap(intent: Intent, label: String): JSONObject {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Diag.w("tool: can't open $label from the background, notifications off")
            return Results.failed(
                "background",
                "Android doesn't let ${Branding.ASSISTANT_NAME} open $label from the background. Ask the user to open ${Branding.ASSISTANT_NAME} and repeat.",
            )
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(TAP_CHANNEL, "Open apps", NotificationManager.IMPORTANCE_HIGH))
        val tap = PendingIntent.getActivity(
            context,
            TAP_ID,
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, TAP_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Open $label")
            .setContentText("Tap to finish what you asked.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setTimeoutAfter(TAP_TIMEOUT_MS)
            .build()
        manager.notify(TAP_ID, notification)
        Diag.i("tool: $label waits for a tap (background)")
        return Results.needsTap(label)
    }

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        Diag.w("tool: no app for ${intent.action}")
        false
    } catch (_: SecurityException) {
        Diag.w("tool: not allowed to open ${intent.action}")
        false
    }

    companion object {
        /** What the model is told after the user answered a permission request. */
        fun permissionNote(permission: String, granted: Boolean): String {
            val what = permission.substringAfterLast('.').replace('_', ' ').lowercase()
            Diag.i("permission: $what ${if (granted) "allowed" else "denied"}")
            return if (granted) {
                "The user allowed $what. Continue the request that needed it."
            } else {
                "The user did not allow $what. Say that this request can't be done without it."
            }
        }

        private const val TIMEOUT_MS = 45_000L // covers waiting for the user's Allow
        private const val HANDOVER_TIMEOUT_MS = 160_000L // the user's turn on a payment or login screen
        private const val TAP_CHANNEL = "open_apps"
        private const val TAP_ID = 2
        private const val TAP_TIMEOUT_MS = 2 * 60_000L
    }
}
