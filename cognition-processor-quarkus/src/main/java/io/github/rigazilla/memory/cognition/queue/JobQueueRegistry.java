package io.github.rigazilla.memory.cognition.queue;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of conversation job queues.
 * Maintains one queue per conversation ID for singleton processing.
 * Thread-safe via ConcurrentHashMap.
 */
@ApplicationScoped
public class JobQueueRegistry {
    
    private static final Logger LOG = Logger.getLogger(JobQueueRegistry.class);
    
    private final ConcurrentHashMap<String, ConversationJobQueue> queues = new ConcurrentHashMap<>();
    
    /**
     * Get or create a job queue for a conversation.
     * Thread-safe: multiple threads can call this concurrently.
     * 
     * @param conversationId Conversation ID
     * @return The job queue for this conversation
     */
    public ConversationJobQueue getOrCreateQueue(String conversationId) {
        return queues.computeIfAbsent(conversationId, id -> {
            LOG.debugf("Creating new job queue for conversation: %s", id);
            return new ConversationJobQueue(id);
        });
    }
    
    /**
     * Get an existing queue, or null if none exists.
     * 
     * @param conversationId Conversation ID
     * @return The job queue, or null if not found
     */
    public ConversationJobQueue getQueue(String conversationId) {
        return queues.get(conversationId);
    }
    
    /**
     * Remove a queue from the registry.
     * Should be called when a conversation is no longer active.
     * 
     * @param conversationId Conversation ID
     * @return The removed queue, or null if not found
     */
    public ConversationJobQueue removeQueue(String conversationId) {
        ConversationJobQueue removed = queues.remove(conversationId);
        if (removed != null) {
            LOG.debugf("Removed job queue for conversation: %s", conversationId);
        }
        return removed;
    }
    
    /**
     * Get total number of active queues.
     */
    public int getQueueCount() {
        return queues.size();
    }
    
    /**
     * Get total number of pending jobs across all queues.
     */
    public int getTotalPendingJobs() {
        return queues.values().stream()
            .mapToInt(ConversationJobQueue::size)
            .sum();
    }
    
    /**
     * Get number of queues currently processing jobs.
     */
    public int getActiveQueueCount() {
        return (int) queues.values().stream()
            .filter(ConversationJobQueue::isProcessing)
            .count();
    }
    
    /**
     * Get registry statistics for monitoring.
     */
    public RegistryStats getStats() {
        return new RegistryStats(
            getQueueCount(),
            getActiveQueueCount(),
            getTotalPendingJobs()
        );
    }
    
    /**
     * Registry statistics record.
     */
    public record RegistryStats(
        int totalQueues,
        int activeQueues,
        int pendingJobs
    ) {
        @Override
        public String toString() {
            return String.format("RegistryStats{totalQueues=%d, activeQueues=%d, pendingJobs=%d}",
                totalQueues, activeQueues, pendingJobs);
        }
    }
}
