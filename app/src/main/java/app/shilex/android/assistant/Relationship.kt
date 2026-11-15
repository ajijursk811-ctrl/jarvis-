package app.shilex.android.assistant

import app.shilex.android.data.Settings
import java.time.ZonedDateTime

/**
 * What she knows about the user from Settings (name, pet name, birthday, special day, likes),
 * plus what today means (their birthday, a special day, morning or late night). One line for the
 * instructions. It stays on the phone and only goes to Gemini inside the conversation.
 */
object Relationship {
    fun describe(settings: Settings, now: ZonedDateTime): String {
        val parts = ArrayList<String>()
        val name = settings.userName.trim()
        if (name.isNotEmpty()) parts += "The user's name is $name."
        val pet = settings.callName.trim().ifEmpty { "jaan" }
        parts += "You call them \"$pet\" (say their name or this pet name now and then, not in every sentence)."
        val likes = settings.likes.trim()
        if (likes.isNotEmpty()) parts += "Things they like: ${likes.take(300)}."

        val birthday = settings.birthday.trim()
        if (birthday.isNotEmpty()) {
            parts += when {
                isOn(birthday, now) -> "TODAY IS THEIR BIRTHDAY: start by wishing them a warm, happy birthday."
                isOn(birthday, now.plusDays(1)) -> "Tomorrow is their birthday: you may mention you are excited about it."
                else -> "Their birthday is on $birthday (day-month)."
            }
        }
        val special = settings.specialDay.trim()
        if (special.isNotEmpty()) {
            val label = settings.specialDayName.trim().ifEmpty { "special day" }
            parts += when {
                isOn(special, now) -> "TODAY IS YOUR $label: start by wishing them sweetly."
                isOn(special, now.plusDays(1)) -> "Tomorrow is your $label: you may mention it."
                else -> "Your $label is on $special (day-month)."
            }
        }

        val hour = now.hour
        if (hour in 5..11) parts += "It is morning: if this is the start of a talk, begin with a sweet good morning."
        if (hour >= 22 || hour < 4) {
            parts += "It is late: if they say they are going to sleep, wish them a sweet good night and tell them to rest."
        }
        return parts.joinToString(" ")
    }

    /** True when [text] ("15-08" or "15/8") is the day and month of [day]. */
    private fun isOn(text: String, day: ZonedDateTime): Boolean {
        val match = DATE.find(text) ?: return false
        return match.groupValues[1].toInt() == day.dayOfMonth && match.groupValues[2].toInt() == day.monthValue
    }

    private val DATE = Regex("""(\d{1,2})\D+(\d{1,2})""")
}
