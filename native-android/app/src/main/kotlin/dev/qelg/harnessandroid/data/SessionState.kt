package dev.qelg.harnessandroid.data

import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class HarnessSessionState(
    val sessionId: String,
    val state: String,
    val read: String? = null,
    val archive: String? = null,
    val outcome: String? = null,
    val sourceEventId: Long? = null,
    val eventId: Long? = null,
    val createdAtMs: Long? = null,
) {
    val running: Boolean
        get() = state == "running"

    val finished: Boolean
        get() = state == "finished"

    val unread: Boolean
        get() = finished && read == "unread"

    val archived: Boolean
        get() = archive == "true"

    val updatedAt: String?
        get() = createdAtMs?.let { Instant.ofEpochMilli(it).toString() }

    companion object {
        fun fromJson(value: JsonObject): HarnessSessionState =
            HarnessSessionState(
                sessionId = value.string("session_id").orEmpty(),
                state = value.string("state").orEmpty(),
                read = value.string("read"),
                archive = value.string("archive"),
                outcome = value.string("outcome"),
                sourceEventId = value["source_event_id"]?.jsonPrimitive?.longOrNull,
                eventId = value["event_id"]?.jsonPrimitive?.longOrNull,
                createdAtMs = value["created_at_ms"]?.jsonPrimitive?.longOrNull,
            )
    }
}

fun applySessionStates(
    sessions: List<HarnessSession>,
    states: List<HarnessSessionState>,
): List<HarnessSession> {
    val bySession = states.associateBy(HarnessSessionState::sessionId)
    return sessions.map { session ->
        val state = bySession[session.id] ?: return@map session
        session.copy(
            updatedAt = state.updatedAt ?: session.updatedAt,
            active = state.running,
            endReason = state.outcome ?: session.endReason,
            sessionState = state,
        )
    }
}

fun isSessionRead(session: HarnessSession, legacyReadAt: String?): Boolean =
    session.sessionState?.let { it.finished && it.read == "read" }
        ?: isSessionUpdateRead(session, legacyReadAt)

fun formatSessionState(state: HarnessSessionState): String =
    when {
        state.running -> "Running"
        state.finished && !state.outcome.isNullOrBlank() -> "Finished · ${state.outcome}"
        state.finished -> "Finished"
        else -> state.state.replaceFirstChar { it.uppercase() }
    }

fun filterArchivedSessions(
    sessions: List<HarnessSession>,
    showArchived: Boolean,
): List<HarnessSession> =
    if (showArchived) sessions else sessions.filterNot { it.sessionState?.archived == true }
