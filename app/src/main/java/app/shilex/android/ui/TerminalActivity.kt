package app.shilex.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shilex.android.tools.AgentWorkspace
import okhttp3.OkHttpClient

/**
 * The agent's terminal: type what to build (or a command) and watch it work. Pages it builds
 * open in Chrome. See AgentWorkspace for the commands.
 */
class TerminalActivity : ComponentActivity() {
    private val http = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShilexTheme {
                val lines by AgentWorkspace.log.collectAsStateWithLifecycle()
                var input by remember { mutableStateOf("") }
                val list = rememberLazyListState()
                LaunchedEffect(lines.size) {
                    if (lines.isNotEmpty()) list.animateScrollToItem(lines.lastIndex)
                }
                val submit = {
                    val text = input.trim()
                    input = ""
                    if (text.isNotEmpty()) AgentWorkspace.exec(applicationContext, http, text)
                }
                Column(Modifier.fillMaxSize().background(BACKGROUND).statusBarsPadding().imePadding()) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Masuka terminal", color = GREEN, fontFamily = FontFamily.Monospace, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { finish() }) { Text("Close") }
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = list) {
                        items(lines) { line ->
                            Text(
                                line,
                                color = if (line.startsWith("$ ")) Color.White else if (line.startsWith("✖")) Color(0xFFFF8A80) else GREEN,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("$ ", color = GREEN, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
                        BasicTextField(
                            value = input,
                            onValueChange = { input = it },
                            textStyle = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 15.sp),
                            cursorBrush = SolidColor(GREEN),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { submit() }),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { submit() }) { Text("Run") }
                    }
                }
            }
        }
    }

    private companion object {
        val BACKGROUND = Color(0xFF07070D)
        val GREEN = Color(0xFF7CFF9A)
    }
}
