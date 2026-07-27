package dev.qelg.harnessandroid.data

import kotlinx.serialization.json.*

object ProtocolCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun request(
        id: String,
        method: String,
        params: Map<String, JsonElement> = emptyMap(),
    ): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        put("params", JsonObject(params))
    }

    fun event(frame: String): GatewayEvent? {
        val root =
            runCatching { json.parseToJsonElement(frame).jsonObject }.getOrNull() ?: return null
        if (root["method"]?.jsonPrimitive?.contentOrNull != "event") return null
        val params = root["params"]?.jsonObject ?: return null
        val type = params["type"]?.jsonPrimitive?.contentOrNull ?: return null
        val payload = params["payload"] as? JsonObject ?: JsonObject(emptyMap())
        return GatewayEvent(type, params["session_id"]?.jsonPrimitive?.contentOrNull, payload)
    }
}

data class GatewayEvent(
    val type: String,
    val sessionId: String?,
    val payload: Map<String, JsonElement>,
)
