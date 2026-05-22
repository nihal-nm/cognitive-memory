package io.github.rigazilla.memory.cognition.event;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;

/**
 * Scheduler for promoting dirty windows to scope jobs.
 * Runs periodically to check for windows that are ready for promotion.
 * 
 * Promotion triggers:
 * 1. Debounce delay expired (now >= dueAt)
 * 2. Max batch age reached (now - firstObservedAt >= max-batch-age)
 * 3. Max batch entries reached (entryCount >= max-batch-entries)
 */
@ApplicationScoped
public class DebounceScheduler {
    
    private static final Logger LOG = Logger.getLogger(DebounceScheduler.class);
    
    @Inject
    DirtyWindowRegistry windowRegistry;
    
    /**
     * Scan for ready windows every 5 seconds.
     * This provides a good balance between responsiveness and overhead.
     */
    @Scheduled(every = "5s")
    void promoteReadyWindows() {
        try {
            Instant now = Instant.now();
            
            // Find windows ready for promotion
            List<DirtyWindow> readyWindows = windowRegistry.findReadyWindows(now);
            
            if (readyWindows.isEmpty()) {
                LOG.trace("No windows ready for promotion");
                return;
            }
            
            LOG.infof("Found %d windows ready for promotion", readyWindows.size());
            
            // Promote all ready windows
            windowRegistry.promoteWindows(readyWindows);
            
        } catch (Exception e) {
            LOG.errorf(e, "Error during window promotion scan");
        }
    }
    
    /**
     * Log registry status every minute for monitoring.
     */
    @Scheduled(every = "60s")
    void logRegistryStatus() {
        try {
            int windowCount = windowRegistry.getWindowCount();
            
            if (windowCount > 0) {
                LOG.infof("Registry status: %d active windows, oldest age: %s", 
                         windowCount, windowRegistry.getOldestWindowAge());
            }
            
        } catch (Exception e) {
            LOG.errorf(e, "Error logging registry status");
        }
    }
}