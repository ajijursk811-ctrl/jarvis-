package app.shilex.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * User-visible names. The assistant's name can be changed in Settings: App sets it when the
 * app starts and Settings updates it, and screens that show it refresh by themselves.
 */
object Branding {
    const val DEFAULT_NAME = "Masuka AI"

    var ASSISTANT_NAME: String by mutableStateOf(DEFAULT_NAME)
}
