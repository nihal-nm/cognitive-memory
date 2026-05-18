package io.github.rigazilla.memory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rigazilla.memory.client.MemoryServiceClient;
import io.github.rigazilla.memory.client.model.Conversation;
import io.github.rigazilla.memory.client.model.Entry;
import io.github.rigazilla.memory.config.MemoryServiceConfig;
import io.github.rigazilla.memory.model.ConversationEventData;
import io.github.rigazilla.memory.model.Event;
import io.github.rigazilla.memory.model.EventContext;
import io.github.rigazilla.memory.processor.ContentProcessor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.io.IOException;

/**
 * Default CDI bean that handles conversation events.
 * Customize this class to implement your cache invalidation or business logic.
 */
@ApplicationScoped
public class ConversationEventHandler implements EventHandler {

    private static final Logger LOG = Logger.getLogger(ConversationEventHandler.class);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    MemoryServiceConfig config;

    @Inject
    @RestClient
    MemoryServiceClient apiClient;

    @Inject
    ContentProcessor contentProcessor;

    private Counter eventsReceivedCounter;
    private Counter conversationCreatedCounter;
    private Counter conversationUpdatedCounter;
    private Counter conversationDeletedCounter;

    public void init() {
        eventsReceivedCounter = meterRegistry.counter("memory_service.events.received");
        conversationCreatedCounter = meterRegistry.counter("memory_service.conversations.created");
        conversationUpdatedCounter = meterRegistry.counter("memory_service.conversations.updated");
        conversationDeletedCounter = meterRegistry.counter("memory_service.conversations.deleted");
    }

    @Override
    public void onEvent(Event event) {
        // For backward compatibility - use config auth
        onEvent(new EventContext(event, config.token(), config.apiKey()));
    }

    @Override
    public void onEvent(EventContext eventContext) {
        if (eventsReceivedCounter != null) {
            eventsReceivedCounter.increment();
        }

        Event event = eventContext.getEvent();
        LOG.debugf("Received event - kind: %s, event: %s, data: %s",
                   event.getKind(), event.getEvent(), event.getData());

        if (event.isStreamEvent()) {
            handleStreamEvent(event);
        } else if (event.isConversationEvent()) {
            handleConversationEvent(event, eventContext.getToken(), eventContext.getApiKey());
        } else if (event.isEntryEvent()) {
            handleEntryEvent(event, eventContext.getToken(), eventContext.getApiKey());
        } else {
            LOG.warnf("⚠️  Unknown event kind: %s", event.getKind());
        }
    }

    @Override
    public void onOpen() {
        LOG.info("✅ Connected to memory-service event stream");
        LOG.info("👂 Listening for conversation events...");
    }

    @Override
    public void onError(Throwable throwable) {
        LOG.error("❌ Stream error occurred", throwable);
    }

    @Override
    public void onClosed() {
        LOG.info("🔌 Stream connection closed");
    }

    private void handleStreamEvent(Event event) {
        String phase = event.getData().get("phase") != null ?
                event.getData().get("phase").asText() : "unknown";
        LOG.infof("📡 Stream phase: %s", phase);
    }

    private void handleConversationEvent(Event event, String token, String apiKey) {
        try {
            ConversationEventData data = objectMapper.treeToValue(
                    event.getData(), ConversationEventData.class);

            switch (event.getEvent()) {
                case "created":
                    onConversationCreated(data, token, apiKey);
                    if (conversationCreatedCounter != null) {
                        conversationCreatedCounter.increment();
                    }
                    break;

                case "updated":
                    onConversationUpdated(data, token, apiKey);
                    if (conversationUpdatedCounter != null) {
                        conversationUpdatedCounter.increment();
                    }
                    break;

                case "deleted":
                    onConversationDeleted(data);
                    if (conversationDeletedCounter != null) {
                        conversationDeletedCounter.increment();
                    }
                    break;

                default:
                    LOG.warnf("⚠️  Unknown conversation event: %s", event.getEvent());
            }
        } catch (IOException e) {
            LOG.error("❌ Failed to parse conversation event data", e);
        }
    }

    private void handleEntryEvent(Event event, String token, String apiKey) {
        try {
            var data = event.getData();

            // With detail=full, the data IS the full Entry object
            // Parse it directly
            Entry entry = objectMapper.treeToValue(data, Entry.class);

            LOG.infof("📨 Entry %s: id=%s, conversation=%s, role=%s",
                    event.getEvent(), entry.getId(), entry.getConversationId(), entry.getRole());

            if ("created".equals(event.getEvent())) {
                // Entry already has full content - pass directly to processor with auth
                contentProcessor.processEntryCreated(entry, token, apiKey);
            }
        } catch (Exception e) {
            LOG.errorf(e, "❌ Failed to handle entry event. Event data: %s", event.getData());
        }
    }

    /**
     * Called when a conversation is created.
     * Fetches full content and passes to processor.
     */
    protected void onConversationCreated(ConversationEventData data, String token, String apiKey) {
        LOG.infof("✨ Conversation CREATED: id=%s, group=%s",
                data.getConversationId(),
                data.getConversationGroupId());

        // Fetch full conversation content
        Conversation conversation = fetchConversation(data.getConversationId(), token, apiKey);
        if (conversation != null) {
            // Pass to processor for business logic
            contentProcessor.processConversationCreated(conversation);
        }
    }

    /**
     * Called when a conversation is updated.
     * Fetches full content and passes to processor.
     */
    protected void onConversationUpdated(ConversationEventData data, String token, String apiKey) {
        LOG.infof("📝 Conversation UPDATED: id=%s, title=%s",
                data.getConversationId(),
                data.getTitle());

        // Fetch full conversation content
        Conversation conversation = fetchConversation(data.getConversationId(), token, apiKey);
        if (conversation != null) {
            // Pass to processor for business logic
            contentProcessor.processConversationUpdated(conversation);
        }
    }

    /**
     * Called when a conversation is deleted.
     * Passes to processor (no API call needed - conversation is gone).
     */
    protected void onConversationDeleted(ConversationEventData data) {
        LOG.infof("🗑️  Conversation DELETED: id=%s",
                data.getConversationId());

        // Pass to processor for cleanup logic
        contentProcessor.processConversationDeleted(data.getConversationId());
    }

    /**
     * Fetch full conversation from API.
     */
    private Conversation fetchConversation(String conversationId, String token, String apiKey) {
        try {
            String auth = "Bearer " + token;
            return apiClient.getConversation(conversationId, auth, apiKey);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to fetch conversation %s", conversationId);
            return null;
        }
    }

    /**
     * Fetch full entry from API.
     */
    private Entry fetchEntry(String conversationId, String entryId, String token, String apiKey) {
        try {
            String auth = "Bearer " + token;
            return apiClient.getEntry(conversationId, entryId, auth, apiKey);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to fetch entry %s in conversation %s", entryId, conversationId);
            return null;
        }
    }
}
