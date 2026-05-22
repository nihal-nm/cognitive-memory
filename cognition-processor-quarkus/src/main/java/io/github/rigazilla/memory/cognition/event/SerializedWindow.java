package io.github.rigazilla.memory.cognition.event;

import java.time.Instant;
import java.util.List;

/**
 * Serializable representation of a DirtyWindow for checkpoint persistence.
 * 
 * @param conversationId Conversation ID
 * @param firstEventCursor First event cursor in this window
 * @param latestEventCursor Latest event cursor in this window
 * @param entryIds List of entry IDs affected by events in this window
 * @param firstObservedAt When the first event was observed
 * @param latestObservedAt When the latest event was observed
 * @param dueAt When this window should be promoted
 * @param eventCount Number of events in this window
 */
public record SerializedWindow(
    String conversationId,
    String firstEventCursor,
    String latestEventCursor,
    List<String> entryIds,
    Instant firstObservedAt,
    Instant latestObservedAt,
    Instant dueAt,
    int eventCount
) {
}
