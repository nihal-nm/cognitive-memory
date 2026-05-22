package io.github.rigazilla.memory.cognition.event;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Registry for managing dirty conversation windows.
 * Thread-safe registry that collects events into conversation-scoped debounce windows.
 * 
 * Synchronization strategy:
 * - ConcurrentHashMap for concurrent window updates
 * - ReentrantLock for promotion operations
 */
@ApplicationScoped
public class DirtyWindowRegistry {
    
    private static final Logger LOG = Logger.getLogger(DirtyWindowRegistry.class);
    
    // Active windows: conversationId -> DirtyWindow
    private final ConcurrentHashMap<String, DirtyWindow> windows = new ConcurrentHashMap<>();
    
    // Lock for promotion operations
    private final ReentrantLock promotionLock = new ReentrantLock();
    
    @ConfigProperty(name = "cognition.scheduler.debounce-delay", defaultValue = "PT1M")
    Duration debounceDelay;
    
    @ConfigProperty(name = "cognition.scheduler.max-batch-age", defaultValue = "PT5M")
    Duration maxBatchAge;
    
    @ConfigProperty(name = "cognition.scheduler.max-batch-entries", defaultValue = "24")
    int maxBatchEntries;
    
    @ConfigProperty(name = "cognition.scheduler.max-checkpoint-windows", defaultValue = "1000")
    int maxCheckpointWindows;
    
    @Inject
    ScopeJobDispatcher jobDispatcher;
    
    /**
     * Accept an event into the registry.
     * Creates a new window or extends an existing one.
     * 
     * @param conversationId Conversation ID
     * @param eventCursor Event cursor
     * @param entryId Entry ID (may be null)
     * @param observedAt When the event was observed
     * @return true if a window was promoted due to max entries
     */
    public boolean acceptEvent(String conversationId, String eventCursor, String entryId, Instant observedAt) {
        // Check if we're at capacity
        while (windows.size() >= maxCheckpointWindows) {
            LOG.warnf("Checkpoint window limit reached (%d), promoting oldest due window", maxCheckpointWindows);
            DirtyWindow oldest = findOldestDueWindow();
            if (oldest != null) {
                promoteWindow(oldest, "checkpoint_bounded");
            } else {
                LOG.warnf("All windows are fresh, cannot make room for new event");
                break;
            }
        }
        
        // Create or extend window
        boolean[] promoted = {false};
        windows.compute(conversationId, (k, existing) -> {
            if (existing == null) {
                // Create new window
                DirtyWindow newWindow = new DirtyWindow(conversationId, eventCursor, entryId, observedAt, debounceDelay);
                
                LOG.debugf("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                LOG.debugf("Window Created");
                LOG.debugf("  Conversation ID:    %s", conversationId);
                LOG.debugf("  First Cursor:       %s", eventCursor);
                LOG.debugf("  Entry ID:           %s", entryId != null ? entryId : "(none)");
                LOG.debugf("  Observed At:        %s", observedAt);
                LOG.debugf("  Due At:             %s", newWindow.getDueAt());
                LOG.debugf("  Debounce Delay:     %s", debounceDelay);
                LOG.debugf("  Initial Event Count: 1");
                LOG.debugf("  Initial Entry Count: %d", entryId != null ? 1 : 0);
                LOG.debugf("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                
                return newWindow;
            } else {
                // Extend existing window
                int oldEventCount = existing.getEventCount();
                int oldEntryCount = existing.getEntryCount();
                
                existing.extend(eventCursor, entryId, observedAt);
                
                LOG.debugf("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                LOG.debugf("Window Extended");
                LOG.debugf("  Conversation ID:    %s", conversationId);
                LOG.debugf("  Latest Cursor:      %s", eventCursor);
                LOG.debugf("  Entry ID:           %s", entryId != null ? entryId : "(none)");
                LOG.debugf("  Observed At:        %s", observedAt);
                LOG.debugf("  Event Count:        %d → %d", oldEventCount, existing.getEventCount());
                LOG.debugf("  Entry Count:        %d → %d", oldEntryCount, existing.getEntryCount());
                LOG.debugf("  Window Age:         %s", existing.getAge(observedAt));
                LOG.debugf("  Due At:             %s", existing.getDueAt());
                LOG.debugf("  Time Until Due:     %s", Duration.between(observedAt, existing.getDueAt()));
                LOG.debugf("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                
                // Check if we should promote immediately due to max entries
                if (existing.isMaxEntriesReached(maxBatchEntries)) {
                    LOG.infof("Window for conversation %s reached max entries (%d), promoting immediately", 
                             conversationId, maxBatchEntries);
                    promoted[0] = true;
                    return null; // Remove from map, will be promoted
                }
                
                return existing;
            }
        });
        
        // If we removed the window due to max entries, promote it now
        if (promoted[0]) {
            // We need to recreate the window for promotion since we removed it
            // This is safe because we're the only thread that removed it
            DirtyWindow windowToPromote = new DirtyWindow(
                conversationId, eventCursor, entryId, observedAt, debounceDelay
            );
            promoteWindow(windowToPromote, "max_entries");
        }
        
        return promoted[0];
    }
    
    /**
     * Find windows that are ready for promotion.
     * Called by the promotion scheduler.
     * 
     * @param now Current time
     * @return List of windows to promote
     */
    public List<DirtyWindow> findReadyWindows(Instant now) {
        List<DirtyWindow> ready = new ArrayList<>();
        
        for (DirtyWindow window : windows.values()) {
            if (shouldPromote(window, now)) {
                ready.add(window);
            }
        }
        
        // Sort by dueAt (oldest first)
        ready.sort(Comparator.comparing(DirtyWindow::getDueAt));
        
        if (!ready.isEmpty()) {
            LOG.debugf("Found %d windows ready for promotion:", ready.size());
            for (DirtyWindow window : ready) {
                String trigger = determinePromotionTrigger(window, now);
                LOG.debugf("  - %s (trigger: %s, age: %s, entries: %d)", 
                          window.getConversationId(), trigger, window.getAge(now), window.getEntryCount());
            }
        }
        
        return ready;
    }
    
    /**
     * Check if a window should be promoted.
     */
    private boolean shouldPromote(DirtyWindow window, Instant now) {
        // Trigger 1: Debounce delay expired
        if (window.isDue(now)) {
            return true;
        }
        
        // Trigger 2: Max batch age reached
        if (window.isMaxAgeReached(now, maxBatchAge)) {
            return true;
        }
        
        // Trigger 3: Max batch entries reached
        if (window.isMaxEntriesReached(maxBatchEntries)) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Determine which trigger condition caused promotion.
     */
    private String determinePromotionTrigger(DirtyWindow window, Instant now) {
        if (window.isMaxEntriesReached(maxBatchEntries)) {
            return "max_batch_entries";
        } else if (window.isMaxAgeReached(now, maxBatchAge)) {
            return "max_batch_age";
        } else if (window.isDue(now)) {
            return "debounce_delay";
        } else {
            return "unknown";
        }
    }
    
    /**
     * Promote a list of windows to scope jobs.
     * Thread-safe operation using promotion lock.
     * 
     * @param windowsToPromote Windows to promote
     */
    public void promoteWindows(List<DirtyWindow> windowsToPromote) {
        promotionLock.lock();
        try {
            for (DirtyWindow window : windowsToPromote) {
                // Remove from registry
                DirtyWindow removed = windows.remove(window.getConversationId());
                if (removed != null) {
                    // Determine trigger type
                    Instant now = Instant.now();
                    String trigger = determinePromotionTrigger(removed, now);
                    
                    LOG.debugf("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    LOG.debugf("Window Promoted");
                    LOG.debugf("  Conversation ID:    %s", removed.getConversationId());
                    LOG.debugf("  Trigger:            %s", trigger);
                    LOG.debugf("  Event Cursors:      %s → %s", removed.getFirstEventCursor(), removed.getLatestEventCursor());
                    LOG.debugf("  Event Count:        %d", removed.getEventCount());
                    LOG.debugf("  Entry Count:        %d", removed.getEntryCount());
                    LOG.debugf("  Entry IDs:          %s", removed.getEntryIds());
                    LOG.debugf("  Window Age:         %s", removed.getAge(now));
                    LOG.debugf("  First Observed:     %s", removed.getFirstObservedAt());
                    LOG.debugf("  Latest Observed:    %s", removed.getLatestObservedAt());
                    LOG.debugf("  Due At:             %s", removed.getDueAt());
                    LOG.debugf("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    
                    promoteWindow(removed, trigger);
                }
            }
        } finally {
            promotionLock.unlock();
        }
    }
    
    /**
     * Promote a single window to a scope job.
     */
    private void promoteWindow(DirtyWindow window, String trigger) {
        LOG.infof("Promoting window for conversation %s (trigger: %s): %s", 
                 window.getConversationId(), trigger, window);
        
        // Create scope job
        ScopeJob job = new ScopeJob(
            window.getConversationId(),
            window.getFirstEventCursor(),
            window.getLatestEventCursor(),
            window.getEntryIds(),
            window.getFirstObservedAt(),
            Instant.now(),
            trigger
        );
        
        // Dispatch job
        jobDispatcher.dispatch(job);
    }
    
    /**
     * Find the oldest due window (for checkpoint bounds enforcement).
     */
    private DirtyWindow findOldestDueWindow() {
        Instant now = Instant.now();
        return windows.values().stream()
            .filter(w -> w.isDue(now))
            .min(Comparator.comparing(DirtyWindow::getDueAt))
            .orElse(null);
    }
    
    /**
     * Restore windows from checkpoint.
     */
    public void restoreWindows(List<SerializedWindow> serializedWindows) {
        promotionLock.lock();
        try {
            LOG.infof("Restoring %d windows from checkpoint", serializedWindows.size());
            
            for (SerializedWindow sw : serializedWindows) {
                DirtyWindow window = new DirtyWindow(
                    sw.conversationId(),
                    sw.firstEventCursor(),
                    sw.latestEventCursor(),
                    sw.entryIds(),
                    sw.firstObservedAt(),
                    sw.latestObservedAt(),
                    sw.dueAt(),
                    sw.eventCount()
                );
                
                windows.put(sw.conversationId(), window);
                
                LOG.debugf("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                LOG.debugf("Window Restored");
                LOG.debugf("  Conversation ID:    %s", window.getConversationId());
                LOG.debugf("  Event Cursors:      %s → %s", window.getFirstEventCursor(), window.getLatestEventCursor());
                LOG.debugf("  Event Count:        %d", window.getEventCount());
                LOG.debugf("  Entry Count:        %d", window.getEntryCount());
                LOG.debugf("  Entry IDs:          %s", window.getEntryIds());
                LOG.debugf("  First Observed:     %s", window.getFirstObservedAt());
                LOG.debugf("  Latest Observed:    %s", window.getLatestObservedAt());
                LOG.debugf("  Due At:             %s", window.getDueAt());
                LOG.debugf("  Time Until Due:     %s", Duration.between(Instant.now(), window.getDueAt()));
                LOG.debugf("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            }
            
            LOG.infof("Successfully restored %d windows", serializedWindows.size());
        } finally {
            promotionLock.unlock();
        }
    }
    
    /**
     * Get all windows for checkpoint serialization.
     */
    public List<SerializedWindow> serializeWindows() {
        List<SerializedWindow> serialized = new ArrayList<>();
        
        for (DirtyWindow window : windows.values()) {
            serialized.add(new SerializedWindow(
                window.getConversationId(),
                window.getFirstEventCursor(),
                window.getLatestEventCursor(),
                window.getEntryIds(),
                window.getFirstObservedAt(),
                window.getLatestObservedAt(),
                window.getDueAt(),
                window.getEventCount()
            ));
        }
        
        if (!serialized.isEmpty()) {
            LOG.debugf("Serializing %d windows for checkpoint:", serialized.size());
            for (SerializedWindow sw : serialized) {
                LOG.debugf("  - %s: %d events, %d entries, due in %s", 
                          sw.conversationId(), sw.eventCount(), sw.entryIds().size(),
                          Duration.between(Instant.now(), sw.dueAt()));
            }
        }
        
        return serialized;
    }
    
    /**
     * Get current window count.
     */
    public int getWindowCount() {
        return windows.size();
    }
    
    /**
     * Get the age of the oldest window.
     */
    public Duration getOldestWindowAge() {
        Instant now = Instant.now();
        return windows.values().stream()
            .map(w -> w.getAge(now))
            .max(Comparator.naturalOrder())
            .orElse(Duration.ZERO);
    }
    
    /**
     * Clear all windows (for testing).
     */
    public void clear() {
        windows.clear();
    }
}