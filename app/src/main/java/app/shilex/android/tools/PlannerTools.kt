package app.shilex.android.tools

import org.json.JSONObject

/**
 * Self-planning and learning, as tools the agent can call: a to-do list (task_add, task_list,
 * task_done, task_remove) and the user's stated preferences (save_preference, forget_preference).
 * Everything stays on the phone; see TaskStore.
 */
object PlannerTools {
    fun all(): List<PhoneTool> = listOf(TaskAdd, TaskList, TaskDone, TaskRemove, SavePreference, ForgetPreference)
}

object TaskAdd : PhoneTool {
    override val name = "task_add"

    override fun declaration() = Schema.function(
        name,
        "Adds a task or one step of a plan to the user's to-do list (shown on the Tasks screen). Use it for each step when " +
            "the user asks for a plan, a list, or help getting something done.",
        Schema.string("title", "The task, short and clear, e.g. \"Maths ke 10 sawal\".", required = true),
        Schema.string("due", "When it is due, in words, e.g. \"kal 5 baje\" (optional)."),
    )

    override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
        val task = TaskStore.add(host.context, args.text("title"), args.text("due"))
            ?: return Results.failed("no_title", "Ask the user what the task is.")
        val due = if (task.due.isEmpty()) "" else " (due ${task.due})"
        return Results.done("Added to the list: ${task.title}$due")
    }
}

object TaskList : PhoneTool {
    override val name = "task_list"

    override fun declaration() = Schema.function(
        name,
        "Reads out the user's to-do list. Use it for \"mere tasks\", \"what's next\" or \"what is left\".",
    )

    override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
        val all = TaskStore.tasks(host.context)
        val open = all.filter { !it.done }
        if (open.isEmpty()) return Results.done(if (all.isEmpty()) "The to-do list is empty." else "Everything on the list is done.")
        val lines = open.take(15).joinToString("\n") { "- ${it.title}" + if (it.due.isEmpty()) "" else " (due ${it.due})" }
        val doneCount = all.size - open.size
        return Results.done("Open tasks:\n$lines" + if (doneCount > 0) "\n($doneCount already done)" else "")
    }
}

object TaskDone : PhoneTool {
    override val name = "task_done"

    override fun declaration() = Schema.function(
        name,
        "Marks a task as done when the user says they finished it.",
        Schema.string("task", "The task's name, or part of it.", required = true),
    )

    override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
        val task = TaskStore.setDone(host.context, args.text("task"), true)
            ?: return Results.failed("not_found", "No task like that on the list. Offer to read the list.")
        return Results.done("Marked done: ${task.title}")
    }
}

object TaskRemove : PhoneTool {
    override val name = "task_remove"

    override fun declaration() = Schema.function(
        name,
        "Removes a task from the to-do list.",
        Schema.string("task", "The task's name, or part of it.", required = true),
    )

    override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
        val task = TaskStore.remove(host.context, args.text("task"))
            ?: return Results.failed("not_found", "No task like that on the list. Offer to read the list.")
        return Results.done("Removed: ${task.title}")
    }
}

object SavePreference : PhoneTool {
    override val name = "save_preference"

    override fun declaration() = Schema.function(
        name,
        "Saves one thing the user clearly said about their taste, habits or style (for example \"likes spicy food\", " +
            "\"wants short answers\", \"studies at night\") so you can give better recommendations later. Never save " +
            "passwords, PINs, money or ID details, health conditions, or private things about other people, and never save " +
            "something the user did not say.",
        Schema.string("preference", "One short sentence about the user.", required = true),
    )

    override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
        return if (TaskStore.addPreference(host.context, args.text("preference"))) {
            Results.done("Saved. You will remember it.")
        } else {
            Results.failed("not_saved", "That wasn't saved (empty, or it looks private). Don't mention it again.")
        }
    }
}

object ForgetPreference : PhoneTool {
    override val name = "forget_preference"

    override fun declaration() = Schema.function(
        name,
        "Forgets something you saved about the user's preferences, when they ask you to forget it.",
        Schema.string("preference", "The preference, or part of it.", required = true),
    )

    override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
        return if (TaskStore.removePreference(host.context, args.text("preference"))) {
            Results.done("Forgotten.")
        } else {
            Results.failed("not_found", "Nothing like that was saved.")
        }
    }
}
