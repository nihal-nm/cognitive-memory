package io.github.rigazilla.memory.processor;

import io.github.rigazilla.memory.client.model.Conversation;
import io.github.rigazilla.memory.client.model.Entry;
import io.github.rigazilla.memory.topics.TopicDetectionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Content processor - handles the actual business logic for conversations and entries.
 * This is a reusable service that can be called from:
 * - Event listener (when events arrive)
 * - REST endpoints (manual triggers)
 * - Scheduled jobs
 * - Other services
 */
@ApplicationScoped
public class ContentProcessor {

    private static final Logger LOG = Logger.getLogger(ContentProcessor.class);

    @Inject
    TopicDetectionService topicDetectionService;

    /**
     * Process a conversation that was created.
     * Implement your business logic here.
     *
     * @param conversation the conversation object with full details
     */
    public void processConversationCreated(Conversation conversation) {
        LOG.infof("📦 Processing conversation created: %s", conversation);

        // TODO: Implement your logic here
        // Examples:
        // - Index conversation in search engine
        // - Send notifications
        // - Update analytics
        // - Invalidate cache
        // - Trigger workflows

        LOG.infof("   Title: %s", conversation.getTitle());
        LOG.infof("   Owner: %s", conversation.getOwnerUserId());
        LOG.infof("   Created: %s", conversation.getCreatedAt());
    }

    /**
     * Process a conversation that was updated.
     *
     * @param conversation the conversation object with full details
     */
    public void processConversationUpdated(Conversation conversation) {
        LOG.infof("📦 Processing conversation updated: %s", conversation);

        // TODO: Implement your logic here
        // Examples:
        // - Re-index conversation
        // - Update cache
        // - Log audit trail

        LOG.infof("   Title: %s", conversation.getTitle());
        LOG.infof("   Updated: %s", conversation.getUpdatedAt());
    }

    /**
     * Process a conversation that was deleted.
     *
     * @param conversationId the ID of the deleted conversation
     */
    public void processConversationDeleted(String conversationId) {
        LOG.infof("📦 Processing conversation deleted: %s", conversationId);

        // TODO: Implement your logic here
        // Examples:
        // - Remove from search index
        // - Clear cache
        // - Archive data
        // - Send notifications
    }

    /**
     * Process an entry (message) that was created.
     *
     * @param entry the entry object with full details
     */
    public void processEntryCreated(Entry entry) {
        processEntryCreated(entry, null, null);
    }

    /**
     * Process an entry (message) that was created.
     *
     * @param entry the entry object with full details
     * @param token the authentication token to use for API calls
     * @param apiKey the API key to use for API calls
     */
    public void processEntryCreated(Entry entry, String token, String apiKey) {
        LOG.infof("📦 Processing entry created: %s", entry);

        LOG.infof("   Role: %s", entry.getRole());
        LOG.infof("   Content: %s", entry.getContent());
        LOG.infof("   Conversation: %s", entry.getConversationId());

        // Trigger async topic detection for the conversation
        // This will analyze the entire conversation so far
        topicDetectionService.detectTopicsForConversation(entry.getConversationId(), token, apiKey)
                .thenAccept(topics -> {
                    if (topics != null) {
                        LOG.infof("🏷️  Topics detected for conversation %s: %s",
                                entry.getConversationId(), topics.getTopics());
                    }
                })
                .exceptionally(throwable -> {
                    LOG.errorf(throwable, "Failed to detect topics for conversation: %s",
                            entry.getConversationId());
                    return null;
                });
    }
}
