package io.github.rigazilla.memory.cognition.event;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Represents a debounce window for a single conversation.
 * Collects multiple events for the same conversation before promoting to a ScopeJob.
 *
 * Thread-safety: This class is NOT thread-safe. Synchronization must be handled
 * by the DirtyWindowRegistry.
 */
public class DirtyWindow {

    private final String conversationId;

    // Event tracking
    private String firstEventCursor;
    private String latestEventCursor;
    private final Set<String> entryIds;  // LinkedHashSet preserves insertion order

    // Entry ID from the previous promoted window (null for first window)
    private final String previousEntryId;

    // Timing
    private Instant firstObservedAt;
    private Instant latestObservedAt;
    private Instant dueAt;

    // Metadata
    private int eventCount;
    
    /**
     * Create a new dirty window from the first event.
     */
    public DirtyWindow(String conversationId, String eventCursor, String entryId,
                       Instant observedAt, Duration debounceDelay, String previousEntryId) {
        this.conversationId = conversationId;
        this.firstEventCursor = eventCursor;
        this.latestEventCursor = eventCursor;
        this.entryIds = new LinkedHashSet<>();  // Preserves insertion order
        if (entryId != null) {
            this.entryIds.add(entryId);
        }
        this.previousEntryId = previousEntryId;
        this.firstObservedAt = observedAt;
        this.latestObservedAt = observedAt;
        this.dueAt = observedAt.plus(debounceDelay);
        this.eventCount = 1;
    }
    
    /**
     * Restore a window from checkpoint data.
     */
    public DirtyWindow(String conversationId, String firstEventCursor, String latestEventCursor,
                       List<String> entryIds, String previousEntryId, Instant firstObservedAt,
                       Instant latestObservedAt, Instant dueAt, int eventCount) {
        this.conversationId = conversationId;
        this.firstEventCursor = firstEventCursor;
        this.latestEventCursor = latestEventCursor;
        this.entryIds = new LinkedHashSet<>(entryIds);  // Preserves order from checkpoint
        this.previousEntryId = previousEntryId;
        this.firstObservedAt = firstObservedAt;
        this.latestObservedAt = latestObservedAt;
        this.dueAt = dueAt;
        this.eventCount = eventCount;
    }
    
    /**
     * Extend this window with a new event.
     */
    public void extend(String eventCursor, String entryId, Instant observedAt) {
        this.latestEventCursor = eventCursor;
        if (entryId != null) {
            this.entryIds.add(entryId);
        }
        this.latestObservedAt = observedAt;
        this.eventCount++;
        // Note: dueAt is NOT updated - we keep the original debounce deadline
    }
    
    /**
     * Check if this window should be promoted based on timing.
     */
    public boolean isDue(Instant now) {
        return now.isAfter(dueAt) || now.equals(dueAt);
    }
    
    /**
     * Check if this window should be promoted based on age.
     */
    public boolean isMaxAgeReached(Instant now, Duration maxBatchAge) {
        Duration age = Duration.between(firstObservedAt, now);
        return age.compareTo(maxBatchAge) >= 0;
    }
    
    /**
     * Check if this window should be promoted based on entry count.
     */
    public boolean isMaxEntriesReached(int maxBatchEntries) {
        return entryIds.size() >= maxBatchEntries;
    }
    
    /**
     * Get the age of this window.
     */
    public Duration getAge(Instant now) {
        return Duration.between(firstObservedAt, now);
    }
    
    // Getters
    
    public String getConversationId() {
        return conversationId;
    }
    
    public String getFirstEventCursor() {
        return firstEventCursor;
    }
    
    public String getLatestEventCursor() {
        return latestEventCursor;
    }
    
    public List<String> getEntryIds() {
        return new ArrayList<>(entryIds);
    }

    public int getEntryCount() {
        return entryIds.size();
    }

    public String getPreviousEntryId() {
        return previousEntryId;
    }

    public Instant getFirstObservedAt() {
        return firstObservedAt;
    }
    
    public Instant getLatestObservedAt() {
        return latestObservedAt;
    }
    
    public Instant getDueAt() {
        return dueAt;
    }
    
    public int getEventCount() {
        return eventCount;
    }
    
    @Override
    public String toString() {
        return String.format("DirtyWindow[conv=%s, events=%d, entries=%d, age=%s, dueIn=%s]",
                conversationId, eventCount, entryIds.size(),
                Duration.between(firstObservedAt, Instant.now()),
                Duration.between(Instant.now(), dueAt));
    }
}
