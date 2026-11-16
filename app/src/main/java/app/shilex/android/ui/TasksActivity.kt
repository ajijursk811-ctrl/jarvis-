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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shilex.android.Branding
import app.shilex.android.tools.TaskStore

/**
 * The to-do list the agent builds when you ask for a plan, plus what it has learned about your
 * likes. Everything here is stored only on this phone, and you can delete any of it.
 */
class TasksActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ShilexTheme { TasksScreen(this) } }
    }
}

@Composable
private fun TasksScreen(activity: TasksActivity) {
    val context = LocalContext.current
    val version by TaskStore.version.collectAsStateWithLifecycle()
    val tasks = remember(version) { TaskStore.tasks(context) }
    val learned = remember(version) { TaskStore.preferences(context) }
    var title by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Tasks", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { activity.finish() }) { Text("Close") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
            if (tasks.isEmpty()) {
                item {
                    Text(
                        "Koi task nahi. ${Branding.ASSISTANT_NAME} se bolo \"kal ka plan banao\" ya neeche likho.",
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
            items(tasks, key = { it.id }) { task ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = task.done, onCheckedChange = { TaskStore.setDone(context, task.id.toString(), it) })
                    Column(Modifier.weight(1f)) {
                        Text(
                            task.title,
                            textDecoration = if (task.done) TextDecoration.LineThrough else null,
                            color = if (task.done) Color.Gray else Color.Unspecified,
                        )
                        if (task.due.isNotEmpty()) Text(task.due, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    TextButton(onClick = { TaskStore.remove(context, task.id.toString()) }) { Text("Hatao") }
                }
            }
            if (tasks.any { it.done }) {
                item { TextButton(onClick = { TaskStore.clearDone(context) }) { Text("Poore hue sab hatao") } }
            }
            item {
                Text(
                    "${Branding.ASSISTANT_NAME} ne tumhare baare mein ye seekha",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
                )
                Text(
                    "Ye sirf is phone mein hai. Tum jo baat-cheet mein saaf kehte ho wahi yaad hota hai. Kuch galat ho to \"Bhool jao\" dabao.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
            }
            if (learned.isEmpty()) {
                item { Text("Abhi kuch nahi.", modifier = Modifier.padding(vertical = 8.dp)) }
            }
            items(learned) { preference ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(preference, modifier = Modifier.weight(1f).padding(vertical = 4.dp))
                    TextButton(onClick = { TaskStore.removePreference(context, preference) }) { Text("Bhool jao") }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                placeholder = { Text("Naya task likho") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    TaskStore.add(context, title, "")
                    title = ""
                },
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("Jodo") }
        }
    }
}
