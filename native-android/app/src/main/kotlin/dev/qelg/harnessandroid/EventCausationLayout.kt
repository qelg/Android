package dev.qelg.harnessandroid

import dev.qelg.harnessandroid.data.SessionEvent
import kotlin.math.max
import kotlin.math.min

internal data class EventCausationArrow(val sourceIndex: Int, val targetIndex: Int, val lane: Int)

/** Assign overlapping causal ranges to distinct drawing lanes in the event-list gutter. */
internal fun eventCausationArrows(events: List<SessionEvent>): List<EventCausationArrow> {
    val indexById =
        events.mapIndexedNotNull { index, event -> event.id?.let { it to index } }.toMap()
    val ranges =
        events.mapIndexedNotNull { sourceIndex, event ->
            val targetIndex =
                event.causationId?.let(indexById::get) ?: return@mapIndexedNotNull null
            if (sourceIndex == targetIndex) return@mapIndexedNotNull null
            sourceIndex to targetIndex
        }
    val laneEnds = mutableListOf<Int>()
    return ranges
        .sortedWith(
            compareBy<Pair<Int, Int>> { min(it.first, it.second) }
                .thenByDescending { max(it.first, it.second) }
        )
        .map { (sourceIndex, targetIndex) ->
            val start = min(sourceIndex, targetIndex)
            val end = max(sourceIndex, targetIndex)
            val lane = laneEnds.indexOfFirst { it < start }.takeIf { it >= 0 } ?: laneEnds.size
            if (lane == laneEnds.size) laneEnds += end else laneEnds[lane] = end
            EventCausationArrow(sourceIndex, targetIndex, lane)
        }
        .sortedBy(EventCausationArrow::sourceIndex)
}
