package com.zhizhou.xxtassistant.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class TodayTodo(
    val id: Long,
    val text: String,
    val done: Boolean = false,
    val scheduledAt: Long? = null
)

/** Small account-scoped local store for one day's ad-hoc tasks. */
class TodayTodoStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("xxt_today_todos", Context.MODE_PRIVATE)

    fun list(accountId: String, dayKey: String): List<TodayTodo> = runCatching {
        val array = JSONArray(prefs.getString(key(accountId, dayKey), "[]"))
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            TodayTodo(
                id = item.optLong("id"),
                text = item.optString("text"),
                done = item.optBoolean("done"),
                scheduledAt = item.optLong("scheduledAt").takeIf { it > 0L }
            )
        }.sortedWith(compareBy<TodayTodo> { it.scheduledAt ?: Long.MAX_VALUE }.thenBy { it.id })
    }.getOrDefault(emptyList())

    fun find(accountId: String, dayKey: String, id: Long): TodayTodo? =
        list(accountId, dayKey).firstOrNull { it.id == id }

    fun add(accountId: String, dayKey: String, text: String, scheduledAt: Long? = null): TodayTodo? {
        val clean = text.trim().take(120)
        if (clean.isBlank()) return null
        val next = TodayTodo(
            id = (list(accountId, dayKey).maxOfOrNull { it.id } ?: 0L) + 1L,
            text = clean,
            scheduledAt = scheduledAt?.takeIf { it > 0L }
        )
        save(accountId, dayKey, list(accountId, dayKey) + next)
        return next
    }

    fun setDone(accountId: String, dayKey: String, id: Long, done: Boolean) {
        save(accountId, dayKey, list(accountId, dayKey).map { if (it.id == id) it.copy(done = done) else it })
    }

    fun delete(accountId: String, dayKey: String, id: Long) {
        save(accountId, dayKey, list(accountId, dayKey).filterNot { it.id == id })
    }

    private fun save(accountId: String, dayKey: String, todos: List<TodayTodo>) {
        val array = JSONArray()
        todos.forEach { todo ->
            array.put(JSONObject()
                .put("id", todo.id)
                .put("text", todo.text)
                .put("done", todo.done)
                .put("scheduledAt", todo.scheduledAt ?: JSONObject.NULL))
        }
        prefs.edit().putString(key(accountId, dayKey), array.toString()).apply()
    }

    private fun key(accountId: String, dayKey: String): String = "${accountId.take(80)}:$dayKey"
}
