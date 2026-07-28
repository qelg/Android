package dev.qelg.harnessandroid

import dev.qelg.harnessandroid.data.SessionEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventCausationLayoutTest {
    @Test
    fun overlappingRangesUseSeparateLanesAndReleasedLanesAreReused() {
        val events =
            listOf(
                event(10),
                event(11),
                event(12, cause = 10),
                event(13, cause = 11),
                event(14),
                event(15, cause = 13),
            )

        val arrows = eventCausationArrows(events)

        assertEquals(listOf(2, 3, 5), arrows.map { it.sourceIndex })
        assertEquals(listOf(0, 1, 0), arrows.map { it.lane })
        arrows.forEachIndexed { index, arrow ->
            arrows
                .drop(index + 1)
                .filter { it.lane == arrow.lane }
                .forEach { other ->
                    val firstRange =
                        minOf(arrow.sourceIndex, arrow.targetIndex)..maxOf(
                                arrow.sourceIndex,
                                arrow.targetIndex,
                            )
                    val secondRange =
                        minOf(other.sourceIndex, other.targetIndex)..maxOf(
                                other.sourceIndex,
                                other.targetIndex,
                            )
                    assertTrue(
                        firstRange.last < secondRange.first || secondRange.last < firstRange.first
                    )
                }
        }
    }

    @Test
    fun unresolvedAndSelfCausationDoNotCreateMisleadingArrows() {
        val events = listOf(event(1, cause = 99), event(2, cause = 2))

        assertEquals(emptyList<EventCausationArrow>(), eventCausationArrows(events))
    }

    private fun event(id: Long, cause: Long? = null): SessionEvent {
        val causation = cause?.let { ",\"causation_id\":$it" }.orEmpty()
        return SessionEvent.fromJson(
            Json.parseToJsonElement("""{"id":$id,"name":"event.$id"$causation}""").jsonObject
        )
    }
}
