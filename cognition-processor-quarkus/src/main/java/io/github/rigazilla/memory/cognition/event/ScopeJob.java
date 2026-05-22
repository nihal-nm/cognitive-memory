package io.github.rigazilla.memory.cognition.event;

import java.time.Instant;
import java.util.List;

/**
 * Represents a coalesced unit of work for a conversation scope.
 * Created when a DirtyWindow is promoted.
 * 
 * @param conversationId Conversation ID to process
 * @param firstEventCursor First event cursor in the batch
 * @param latestEventCursor Latest event cursor in the batch
 * @param entryIds List of entry IDs to process
 * @param observedAt When the first event was observed
 * @param processAfter When this job should be processed
 * @param trigger What triggered the promotion (debounce_delay, max_batch_age, max_batch_entries, checkpoint_bounded)
 */
public record ScopeJob(
    String conversationId,
    String firstEventCursor,
    String latestEventCursor,
    List<String> entryIds,
    Instant observedAt,
    Instant processAfter,
    String trigger
) {
}
