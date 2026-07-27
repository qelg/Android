package dev.qelg.harnessandroid.data

import kotlinx.serialization.json.JsonElement

data class GatewayEvent(
    val type: String,
    val sessionId: String?,
    val payload: Map<String, JsonElement>,
)
