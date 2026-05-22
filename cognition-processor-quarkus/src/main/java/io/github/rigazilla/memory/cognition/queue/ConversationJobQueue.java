package io.github.rigazilla.memory.cognition.queue;

import io.github.rigazilla.memory.cognition.event.ScopeJob;
import org.jboss.logging.Logger;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Job queue for a single conversation.
 * Ensures singleton processing: only one job processes at a time per conversation.
 * Multiple conversations can process in parallel via separate queue instances.
 */
public class ConversationJobQueue {
    
    private static final Logger LOG = Logger.getLogger(ConversationJobQueue.class);
    
    private final String conversationId;
    private final BlockingQueue<ScopeJob> queue;
    private final AtomicBoolean processing;
    
    public ConversationJobQueue(String conversationId) {
        this.conversationId = conversationId;
        this.queue = new LinkedBlockingQueue<>();
        this.processing = new AtomicBoolean(false);
    }
    
    /**
     * Enqueue a job for processing.
     * 
     * @param job The job to enqueue
     * @return true if job was enqueued, false if queue is full
     */
    public boolean enqueue(ScopeJob job) {
        boolean added = queue.offer(job);
        if (added) {
            LOG.debugf("Job enqueued for conversation %s: %s (queue size: %d)", 
                conversationId, job, queue.size());
        } else {
            LOG.warnf("Failed to enqueue job for conversation %s: queue full", conversationId);
        }
        return added;
    }
    
    /**
     * Poll the next job from the queue.
     * Blocks until a job is available.
     * 
     * @return The next job, or null if interrupted
     */
    public ScopeJob poll() {
        try {
            return queue.take();
        } catch (InterruptedException e) {
            LOG.warnf("Job polling interrupted for conversation %s", conversationId);
            Thread.currentThread().interrupt();
            return null;
        }
    }
    
    /**
     * Check if this queue is currently processing a job.
     */
    public boolean isProcessing() {
        return processing.get();
    }
    
    /**
     * Mark this queue as processing.
     * Returns true if successfully marked, false if already processing.
     */
    public boolean startProcessing() {
        return processing.compareAndSet(false, true);
    }
    
    /**
     * Mark this queue as no longer processing.
     */
    public void stopProcessing() {
        processing.set(false);
    }
    
    /**
     * Get current queue size.
     */
    public int size() {
        return queue.size();
    }
    
    /**
     * Check if queue is empty.
     */
    public boolean isEmpty() {
        return queue.isEmpty();
    }
    
    public String getConversationId() {
        return conversationId;
    }
    
    @Override
    public String toString() {
        return String.format("ConversationJobQueue{conversationId=%s, size=%d, processing=%s}",
            conversationId, queue.size(), processing.get());
    }
}
