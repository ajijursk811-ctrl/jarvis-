package app.shilex.android.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.shilex.android.notify.Replies
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import org.json.JSONObject
import java.util.Locale

/**
 * Calls and messages. Each one opens the right app with everything filled in and the
 * user taps Call or Send there, so the model never completes these on its own.
 */
object CommsTools {
    fun all(): List<PhoneTool> = listOf(CallContact, WhatsAppSend, SmsSend)

    private val who = arrayOf(
        Schema.string("name", "Contact name as the user said it. Leave empty if they gave a number."),
        Schema.string("number", "Phone number, only if the user said one."),
        Schema.string("which", "Which of the contact's numbers, e.g. mobile, work, or the last digits."),
    )

    private object CallContact : PhoneTool {
        override val name = "call_contact"

        override fun declaration() = Schema.function(
            name,
            "Opens the phone dialer with a contact's number filled in; the user taps Call.",
            *who,
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val to = when (val r = Recipients.resolve(args, host, "call")) {
                is Recipients.Result.Found -> r
                is Recipients.Result.Answer -> return r.json
            }
            val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", to.number, null))
            return host.openApp(intent, "Phone", "The dialer will open with ${to.name} after you speak; the user taps Call.")
        }
    }

    private object WhatsAppSend : PhoneTool {
        override val name = "whatsapp_send"

        override fun declaration() = Schema.function(
            name,
            "Opens a WhatsApp chat with the message typed in. Normally the user taps Send; if the result says the assistant sends it itself, never ask the user to tap Send.",
            *who,
            Schema.string("message", "The message, in the user's words and language.", required = true),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val message = args.text("message")
            if (message.isEmpty()) return Results.failed("no_message", "Ask what the message should say.")
            handsFree(args, WHATSAPP)?.let { return it }
            val to = when (val r = Recipients.resolve(args, host, "message")) {
                is Recipients.Result.Found -> r
                is Recipients.Result.Answer -> return r.json
            }
            val digits = internationalDigits(host.context, to.number)
                ?: return Results.failed("bad_number", "That number doesn't look complete. Ask for it with the country code.")
            val link = Uri.parse("https://api.whatsapp.com/send?phone=$digits&text=${Uri.encode(message)}")
            val intent = Intent(Intent.ACTION_VIEW, link)
            val settings = app.shilex.android.data.Settings(host.context)
            val auto = settings.sendWithoutAsking && settings.screenReading &&
                app.shilex.android.control.ControlService.instance != null
            val note = if (auto) {
                "WhatsApp will open with the message to ${to.name} after you speak, and ${app.shilex.android.Branding.ASSISTANT_NAME} taps Send itself. " +
                    "Don't ask the user to tap Send. It is not confirmed yet: never say it was sent unless the app tells you it saw it in the chat."
            } else {
                "WhatsApp will open with the message to ${to.name} after you speak; the user checks it and taps Send. " +
                    "It is not sent yet: never say it was sent unless the app tells you it saw it in the chat."
            }
            return host.openApp(intent, "WhatsApp", note) { SendCheck.watchWhatsApp(message, auto) }
        }
    }

    private object SmsSend : PhoneTool {
        override val name = "sms_send"

        override fun declaration() = Schema.function(
            name,
            "Opens the Messages app with an SMS typed in; the user taps Send.",
            *who,
            Schema.string("message", "The message, in the user's words and language.", required = true),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val message = args.text("message")
            if (message.isEmpty()) return Results.failed("no_message", "Ask what the message should say.")
            handsFree(args, SMS_APPS)?.let { return it }
            val to = when (val r = Recipients.resolve(args, host, "message")) {
                is Recipients.Result.Found -> r
                is Recipients.Result.Answer -> return r.json
            }
            val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", to.number, null))
                .putExtra("sms_body", message)
            return host.openApp(intent, "Messages", "Messages will open with the SMS to ${to.name} after you speak; the user taps Send.")
        }
    }

    /** E.164 digits without "+", using the SIM's country for local numbers. */
    private fun internationalDigits(context: Context, number: String): String? {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val region = (telephony?.simCountryIso?.takeIf { it.isNotEmpty() } ?: Locale.getDefault().country)
            .uppercase()
        val e164 = PhoneNumberUtils.formatNumberToE164(number, region)
            ?: number.takeIf { it.trim().startsWith("+") }
        return e164?.filter(Char::isDigit)?.takeIf { it.length >= 8 }
    }
}

/** Turns "Rahul" / "Rahul's work number" / a spoken number into one number, or a question. */
/**
 * If [args]' person just messaged in one of [packages] and the notification has a Reply box,
 * the answer goes through it hands-free (Replies) instead of opening the app.
 */
private fun handsFree(args: JSONObject, packages: Set<String>): JSONObject? {
    val name = args.text("name")
    val id = Replies.idFor(name, packages, System.currentTimeMillis()) ?: return null
    return Results.failed(
        "use_reply",
        "$name just messaged and can be answered without opening the app: call reply_prepare with reply_id $id " +
            "and this message, read its line back and wait for the user's yes.",
    )
}

private val WHATSAPP = setOf("com.whatsapp", "com.whatsapp.w4b")
private val SMS_APPS = setOf("com.google.android.apps.messaging", "com.samsung.android.messaging")

object Recipients {
    sealed interface Result {
        data class Found(val name: String, val number: String) : Result
        data class Answer(val json: JSONObject) : Result
    }

    fun resolve(args: JSONObject, host: ToolHost, verb: String): Result {
        val spoken = args.text("number")
        if (spoken.count(Char::isDigit) >= 6) return Result.Found(args.text("name").ifEmpty { spoken }, spoken)
        val name = args.text("name")
        if (name.isEmpty()) return Result.Answer(Results.failed("no_recipient", "Ask who to $verb."))
        if (!host.hasPermission(ContactsReader.PERMISSION)) {
            host.requestPermission(ContactsReader.PERMISSION)
            return Result.Answer(Results.needsPermission("Contacts"))
        }
        val matches = ContactsReader(host.context).search(name)
        return when {
            matches.isEmpty() -> Result.Answer(
                Results.failed("not_found", "No contact called $name. Ask the user to spell it or say the number."),
            )
            matches.size > 1 -> Result.Answer(
                Results.ask("Several contacts match \"$name\". Ask which one.", matches.take(6).map { it.name }),
            )
            else -> pick(matches.single(), args.text("which"))
        }
    }

    /** One contact: its only number, the one [which] names, or a question. */
    fun pick(contact: Contact, which: String): Result {
        val numbers = contact.numbers
        if (numbers.isEmpty()) return Result.Answer(Results.failed("no_number", "${contact.name} has no phone number saved."))
        if (numbers.size == 1) return Result.Found(contact.name, numbers.single().number)
        val w = which.lowercase().trim()
        val chosen = if (w.isEmpty()) {
            emptyList()
        } else {
            numbers.filter { w in it.label || (w.any(Char::isDigit) && it.number.filter(Char::isDigit).endsWith(w.filter(Char::isDigit))) }
        }
        if (chosen.size == 1) return Result.Found(contact.name, chosen.single().number)
        return Result.Answer(
            Results.ask(
                "${contact.name} has several numbers. Ask which one.",
                numbers.map { "${it.label} ending ${it.number.filter(Char::isDigit).takeLast(4)}" },
            ),
        )
    }
}
