package app.shilex.android.tools

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The agent's to-do list and the preferences it has learned about the user, kept only on this
 * phone. They go to Gemini only as a short summary when a conversation starts. The user can see
 * and delete everything on the Tasks screen.
 */
object TaskStore {
    data class Task(val id: Long, val title: String, val due: String, val done: Boolean)

    private const val FILE = "planner"
    private const val MAX_TASKS = 100
    private const val MAX_PREFERENCES = 40
    private val SENSITIVE = Regex("""(?i)(password|passcode|\bpin\b|\botp\b|aadhaar|card number|account number|\d{6,})""")

    private val _version = MutableStateFlow(0)
    /** Goes up on every change, so screens can refresh. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    fun tasks(context: Context): List<Task> {
        val raw = prefs(context).getString("tasks", "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Task(o.getLong("id"), o.getString("title"), o.optString("due"), o.optBoolean("done"))
            }
        }.getOrDefault(emptyList())
    }

    private fun saveTasks(context: Context, list: List<Task>) {
        val array = JSONArray()
        list.takeLast(MAX_TASKS).forEach {
            array.put(JSONObject().put("id", it.id).put("title", it.title).put("due", it.due).put("done", it.done))
        }
        prefs(context).edit().putString("tasks", array.toString()).apply()
        _version.value = _version.value + 1
    }

    @Synchronized
    fun add(context: Context, title: String, due: String): Task? {
        val clean = title.trim().take(160)
        if (clean.isEmpty()) return null
        val list = tasks(context)
        list.firstOrNull { !it.done && it.title.equals(clean, ignoreCase = true) }?.let { return it }
        val id = maxOf(System.currentTimeMillis(), (list.maxOfOrNull { it.id } ?: 0L) + 1)
        val task = Task(id, clean, due.trim().take(60), false)
        saveTasks(context, list + task)
        return task
    }

    /** The task named by [key]: its id, or its title, or part of its title. */
    private fun find(list: List<Task>, key: String): Task? {
        val k = key.trim()
        if (k.isEmpty()) return null
        k.toLongOrNull()?.let { id -> list.firstOrNull { it.id == id }?.let { return it } }
        return list.firstOrNull { it.title.equals(k, ignoreCase = true) }
            ?: list.firstOrNull { !it.done && it.title.contains(k, ignoreCase = true) }
            ?: list.firstOrNull { it.title.contains(k, ignoreCase = true) }
    }

    @Synchronized
    fun setDone(context: Context, key: String, done: Boolean): Task? {
        val list = tasks(context)
        val target = find(list, key) ?: return null
        saveTasks(context, list.map { if (it.id == target.id) it.copy(done = done) else it })
        return target
    }

    @Synchronized
    fun remove(context: Context, key: String): Task? {
        val list = tasks(context)
        val target = find(list, key) ?: return null
        saveTasks(context, list.filter { it.id != target.id })
        return target
    }

    @Synchronized
    fun clearDone(context: Context) {
        saveTasks(context, tasks(context).filter { !it.done })
    }

    @Synchronized
    fun preferences(context: Context): List<String> {
        val raw = prefs(context).getString("preferences", "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.getString(it) }
        }.getOrDefault(emptyList())
    }

    private fun savePreferences(context: Context, list: List<String>) {
        prefs(context).edit().putString("preferences", JSONArray(list.takeLast(MAX_PREFERENCES)).toString()).apply()
        _version.value = _version.value + 1
    }

    /** Saves one thing the user said they like or do. Private things (numbers, passwords) are refused. */
    @Synchronized
    fun addPreference(context: Context, text: String): Boolean {
        val clean = text.trim().take(120)
        if (clean.isEmpty() || SENSITIVE.containsMatchIn(clean)) return false
        val list = preferences(context)
        if (list.any { it.equals(clean, ignoreCase = true) }) return true
        savePreferences(context, list + clean)
        return true
    }

    @Synchronized
    fun removePreference(context: Context, key: String): Boolean {
        val k = key.trim()
        if (k.isEmpty()) return false
        val list = preferences(context)
        val keep = list.filterNot { it.equals(k, ignoreCase = true) || it.contains(k, ignoreCase = true) }
        if (keep.size == list.size) return false
        savePreferences(context, keep)
        return true
    }
}
