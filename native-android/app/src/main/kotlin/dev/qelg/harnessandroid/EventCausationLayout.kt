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

internal data class VisibleEventCausationSegment(
    val arrow: EventCausationArrow,
    val sourceY: Float,
    val targetY: Float,
    val sourceVisible: Boolean,
    val targetVisible: Boolean,
)

/** Clip causal relationships to the viewport while retaining either visible endpoint. */
internal fun visibleEventCausationSegments(
    arrows: List<EventCausationArrow>,
    visibleCenters: Map<Int, Float>,
    viewportTop: Float,
    viewportBottom: Float,
): List<VisibleEventCausationSegment> {
    val firstVisible = visibleCenters.keys.minOrNull() ?: return emptyList()
    val lastVisible = visibleCenters.keys.maxOrNull() ?: return emptyList()
    fun clippedY(index: Int): Float =
        visibleCenters[index] ?: if (index < firstVisible) viewportTop else viewportBottom

    return arrows.mapNotNull { arrow ->
        val sourceVisible = arrow.sourceIndex in visibleCenters
        val targetVisible = arrow.targetIndex in visibleCenters
        if (!sourceVisible && !targetVisible) return@mapNotNull null
        VisibleEventCausationSegment(
            arrow = arrow,
            sourceY = clippedY(arrow.sourceIndex),
            targetY = clippedY(arrow.targetIndex),
            sourceVisible = sourceVisible,
            targetVisible = targetVisible,
        )
    }
}
