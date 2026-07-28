package dev.qelg.harnessandroid.data

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Complete low-level Harness event representation returned by the session event API. */
data class SessionEvent(
    val id: Long?,
    val name: String,
    val timestamp: Instant?,
    val originator: String?,
    val causationId: Long?,
    val raw: JsonObject,
) {
    val displayName: String
        get() = name.takeIf(String::isNotBlank) ?: "Unknown event"

    val prettyJson: String
        get() = prettyEventJson.encodeToString(JsonObject.serializer(), raw)

    companion object {
        fun fromJson(value: JsonObject): SessionEvent =
            SessionEvent(
                id = (value["id"] as? JsonPrimitive)?.longOrNull,
                name = (value["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                timestamp =
                    (value["created_at_ms"] as? JsonPrimitive)
                        ?.longOrNull
                        ?.let(Instant::ofEpochMilli)
                        ?: (value["created_at"] as? JsonPrimitive)?.contentOrNull?.let {
                            runCatching { Instant.parse(it) }.getOrNull()
                        },
                originator =
                    (value["producer"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                        ?: (value["tags"] as? JsonObject)
                            ?.get("originator")
                            ?.let { it as? JsonPrimitive }
                            ?.contentOrNull
                            ?.takeIf(String::isNotBlank),
                causationId = (value["causation_id"] as? JsonPrimitive)?.longOrNull,
                raw = value,
            )
    }
}

private val prettyEventJson = Json { prettyPrint = true }
