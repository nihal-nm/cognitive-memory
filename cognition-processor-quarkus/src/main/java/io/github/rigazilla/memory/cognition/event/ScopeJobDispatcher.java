package io.github.rigazilla.memory.cognition.event;

import io.github.rigazilla.memory.cognition.queue.ConversationJobQueue;
import io.github.rigazilla.memory.cognition.queue.JobProcessor;
import io.github.rigazilla.memory.cognition.queue.JobQueueRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Dispatcher for scope jobs.
 * Receives promoted DirtyWindows as ScopeJobs and dispatches them for processing.
 * Enqueues jobs and triggers singleton-per-conversation processing on virtual threads.
 */
@ApplicationScoped
public class ScopeJobDispatcher {
    
    private static final Logger LOG = Logger.getLogger(ScopeJobDispatcher.class);
    
    @Inject
    JobQueueRegistry registry;
    
    @Inject
    JobProcessor processor;
    
    /**
     * Dispatch a scope job for processing.
     * Enqueues the job and starts processing if not already active.
     * 
     * @param job The scope job to dispatch
     */
    public void dispatch(ScopeJob job) {
        LOG.infof("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        LOG.infof("Scope Job Dispatched");
        LOG.infof("  Conversation ID:  %s", job.conversationId());
        LOG.infof("  Trigger:          %s", job.trigger());
        LOG.infof("  Event Cursors:    %s -> %s", job.firstEventCursor(), job.latestEventCursor());
        LOG.infof("  Entry Count:      %d", job.entryIds().size());
        LOG.infof("  Entry IDs:        %s", job.entryIds());
        LOG.infof("  Observed At:      %s", job.observedAt());
        LOG.infof("  Process After:    %s", job.processAfter());
        LOG.infof("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        // Get or create queue for this conversation
        ConversationJobQueue queue = registry.getOrCreateQueue(job.conversationId());
        
        // Enqueue the job
        boolean enqueued = queue.enqueue(job);
        if (!enqueued) {
            LOG.errorf("Failed to enqueue job for conversation %s: queue full", job.conversationId());
            return;
        }
        
        // Start processing if not already active (async on virtual thread)
        if (!queue.isProcessing()) {
            LOG.debugf("Starting job processing for conversation: %s", job.conversationId());
            processor.startProcessingAsync(job.conversationId());
        } else {
            LOG.debugf("Job processing already active for conversation: %s", job.conversationId());
        }
    }
}
