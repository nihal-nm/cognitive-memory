package io.github.rigazilla.memory.topics;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory repository for conversation topics.
 */
@ApplicationScoped
public class TopicRepository {

    private static final Logger LOG = Logger.getLogger(TopicRepository.class);

    private final Map<String, ConversationTopics> topics = new ConcurrentHashMap<>();

    /**
     * Save topics for a conversation.
     */
    public void save(ConversationTopics conversationTopics) {
        LOG.infof("💾 Saving topics for conversation: %s - topics: %s",
                conversationTopics.getConversationId(),
                conversationTopics.getTopics());
        topics.put(conversationTopics.getConversationId(), conversationTopics);
    }

    /**
     * Get topics for a specific conversation.
     */
    public Optional<ConversationTopics> findByConversationId(String conversationId) {
        return Optional.ofNullable(topics.get(conversationId));
    }

    /**
     * Get all processed conversations with their topics.
     */
    public List<ConversationTopics> findAll() {
        return new ArrayList<>(topics.values());
    }

    /**
     * Check if conversation has been processed.
     */
    public boolean exists(String conversationId) {
        return topics.containsKey(conversationId);
    }

    /**
     * Clear all topics (for testing).
     */
    public void clear() {
        LOG.warn("🗑️  Clearing all topics from repository");
        topics.clear();
    }

    /**
     * Get statistics.
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalConversations", topics.size());
        stats.put("totalTopics", topics.values().stream()
                .mapToLong(ct -> ct.getTopics().size())
                .sum());
        return stats;
    }
}
