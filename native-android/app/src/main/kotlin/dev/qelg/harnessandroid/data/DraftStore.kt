package dev.qelg.harnessandroid.data

import android.content.Context

internal class DraftStore(context: Context) {
    private val preferences = context.getSharedPreferences("chat_drafts", Context.MODE_PRIVATE)

    fun load(namespace: String): Map<String, String> {
        val prefix = prefix(namespace)
        return preferences.all
            .mapNotNull { (key, value) ->
                val text = value as? String
                if (!key.startsWith(prefix) || text.isNullOrBlank()) null
                else key.removePrefix(prefix) to text
            }
            .toMap()
    }

    fun save(namespace: String, sessionId: String, text: String) {
        val key = prefix(namespace) + sessionId
        preferences
            .edit()
            .apply { if (text.isBlank()) remove(key) else putString(key, text) }
            .apply()
    }

    private fun prefix(namespace: String): String = "${namespace.length}:$namespace:"
}
