package dev.qelg.harnessandroid.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

data class GatewayEvent(
    val type: String,
    val sessionId: String?,
    val payload: Map<String, JsonElement>,
    /** The original durable event, when this notification came from Harness. */
    val rawEvent: JsonObject? = null,
    /** Durable resume cursor. Transient events deliberately have no cursor. */
    val cursor: Long? = null,
)
