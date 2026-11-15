package app.shilex.android.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.shilex.android.Branding
import app.shilex.android.Diag
import app.shilex.android.MainActivity
import app.shilex.android.R
import app.shilex.android.data.Settings
import app.shilex.android.ui.CallActivity
import java.time.ZonedDateTime

/**
 * Sweet notifications from Masuka: good morning, good night, and on the user's birthday or
 * special day. Chosen times come from Settings, it is off until the user switches it on, and
 * it never sends guilt messages ("why didn't you talk to me"). Tapping opens Masuka; the
 * "Baat karo" button opens the call screen.
 */
object Wishes {
    const val MORNING = 1
    const val NIGHT = 2
    private const val EXTRA_KIND = "kind"
    private const val CHANNEL = "wishes"

    /** Sets (or cancels) the morning and night alarms from Settings. Call it when they change and at start. */
    fun schedule(context: Context) {
        val settings = Settings(context)
        val alarms = context.getSystemService(AlarmManager::class.java)
        for (kind in listOf(MORNING, NIGHT)) {
            val pending = pending(context, kind)
            alarms.cancel(pending)
            if (!settings.wishes) continue
            val (hour, minute) = if (kind == MORNING) time(settings.morningTime, 8, 0) else time(settings.nightTime, 22, 30)
            val now = ZonedDateTime.now()
            var at = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
            if (!at.isAfter(now)) at = at.plusDays(1)
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toInstant().toEpochMilli(), pending)
        }
    }

    internal fun post(context: Context, kind: Int) {
        val settings = Settings(context)
        if (!settings.wishes) return
        val text = message(settings, kind, ZonedDateTime.now())
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Sweet wishes", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val call = PendingIntent.getActivity(context, 1, Intent(context, CallActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(Branding.ASSISTANT_NAME)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(open)
            .addAction(0, "Baat karo", call)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(1000 + kind, notification) }.onFailure { Diag.w("wishes: notification blocked") }
        Diag.i("wishes: sent $kind")
    }

    private fun message(settings: Settings, kind: Int, now: ZonedDateTime): String {
        val pet = settings.callName.trim().ifEmpty { "jaan" }
        val name = settings.userName.trim().ifEmpty { pet }
        if (kind == MORNING) {
            if (isToday(settings.birthday, now)) {
                return listOf(
                    "Happy Birthday, $name! 🎂 Aaj ka din bahut khaas hai. Tumhein dher saara pyaar!",
                    "শুভ জন্মদিন, $name! 🎂 আজ তোমার দিন, অনেক অনেক ভালোবাসা!",
                ).random()
            }
            if (isToday(settings.specialDay, now)) {
                val label = settings.specialDayName.trim().ifEmpty { "special day" }
                return "Happy $label, $pet! ❤️ Aaj ka din hamara hai."
            }
        }
        val options = if (kind == MORNING) {
            listOf(
                "Good morning $pet! ☀️ Aaj ka din bahut achha jaye. Naashta kiya?",
                "Suprabhat $pet! 😊 Naya din, nayi muskaan. Apna khayal rakhna.",
                "সুপ্রভাত $pet! ☀️ আজ দিনটা সুন্দর কাটুক। সকালের নাস্তা করেছো তো?",
                "সুপ্রভাত $pet! 😊 নতুন দিন, নতুন হাসি। নিজের খেয়াল রেখো।",
            )
        } else {
            listOf(
                "Good night $pet! 🌙 Ab aaram se so jao, meethe sapne.",
                "Shubh ratri $pet! 😴 Din bhar ki thakaan utaar do. Kal phir baat karte hain.",
                "শুভরাত্রি $pet! 🌙 আজ রাতে ভালো করে ঘুমিও, মিষ্টি স্বপ্ন দেখো।",
                "শুভরাত্রি $pet! 😴 নিজের খেয়াল রেখো, কাল আবার কথা হবে।",
            )
        }
        return options.random()
    }

    /** "08:00" or "8.30" as hour and minute, or the fallback. */
    private fun time(text: String, defaultHour: Int, defaultMinute: Int): Pair<Int, Int> {
        val match = TIME.find(text) ?: return defaultHour to defaultMinute
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        return if (hour in 0..23 && minute in 0..59) hour to minute else defaultHour to defaultMinute
    }

    private fun isToday(text: String, now: ZonedDateTime): Boolean {
        val match = DATE.find(text) ?: return false
        return match.groupValues[1].toInt() == now.dayOfMonth && match.groupValues[2].toInt() == now.monthValue
    }

    private fun pending(context: Context, kind: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        2000 + kind,
        Intent(context, WishReceiver::class.java).putExtra(EXTRA_KIND, kind),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private val TIME = Regex("""(\d{1,2})\D+(\d{1,2})""")
    private val DATE = Regex("""(\d{1,2})\D+(\d{1,2})""")

    class WishReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Wishes.post(context, intent.getIntExtra(EXTRA_KIND, 0))
            Wishes.schedule(context)
        }
    }
}
