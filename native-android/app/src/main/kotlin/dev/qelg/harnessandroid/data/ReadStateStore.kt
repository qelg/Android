package dev.qelg.harnessandroid.data

import android.content.Context

internal class ReadStateStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("session_read_state", Context.MODE_PRIVATE)

    fun load(namespace: String): Map<String, String> {
        val prefix = prefix(namespace)
        return preferences.all
            .mapNotNull { (key, value) ->
                val readAt = value as? String
                if (!key.startsWith(prefix) || readAt.isNullOrBlank()) null
                else key.removePrefix(prefix) to readAt
            }
            .toMap()
    }

    fun save(namespace: String, sessionId: String, readAt: String) {
        preferences.edit().putString(prefix(namespace) + sessionId, readAt).apply()
    }

    private fun prefix(namespace: String): String = "${namespace.length}:$namespace:"
}
