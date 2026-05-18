package io.github.rigazilla.memory.topics;

import io.github.rigazilla.memory.client.MemoryServiceClient;
import io.github.rigazilla.memory.client.model.Conversation;
import io.github.rigazilla.memory.client.model.Entry;
import io.github.rigazilla.memory.client.model.EntriesResponse;
import io.github.rigazilla.memory.config.MemoryServiceConfig;
import io.github.rigazilla.memory.ollama.OllamaClient;
import io.github.rigazilla.memory.ollama.OllamaRequest;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

/**
 * Service for detecting topics in conversations using LLM.
 */
@ApplicationScoped
public class TopicDetectionService {

    private static final Logger LOG = Logger.getLogger(TopicDetectionService.class);

    @Inject
    @RestClient
    OllamaClient ollamaClient;

    @Inject
    @RestClient
    MemoryServiceClient memoryClient;

    @Inject
    MemoryServiceConfig config;

    @Inject
    TopicRepository topicRepository;

    @ConfigProperty(name = "topic-detection.model", defaultValue = "qwen2.5:1.5b")
    String model;

    @ConfigProperty(name = "topic-detection.enabled", defaultValue = "true")
    boolean enabled;

    /**
     * Detect topics for a conversation asynchronously.
     * This fetches all entries for the conversation and analyzes them.
     */
    public CompletionStage<ConversationTopics> detectTopicsForConversation(String conversationId) {
        return detectTopicsForConversation(conversationId, null, null);
    }

    /**
     * Detect topics for a conversation asynchronously.
     * This fetches all entries for the conversation and analyzes them.
     *
     * @param conversationId the conversation ID
     * @param token optional authentication token (uses config if null)
     * @param apiKey optional API key (uses config if null)
     */
    public CompletionStage<ConversationTopics> detectTopicsForConversation(
            String conversationId, String token, String apiKey) {
        if (!enabled) {
            LOG.info("Topic detection is disabled");
            return CompletableFuture.completedFuture(null);
        }

        // Use provided auth or fall back to config
        String authToken = token != null ? token : config.token();
        String authApiKey = apiKey != null ? apiKey : config.apiKey();

        LOG.infof("🔍 Starting topic detection for conversation: %s", conversationId);

        return fetchConversationContent(conversationId, authToken, authApiKey)
                .thenCompose(content -> {
                    if (content == null || content.isEmpty()) {
                        LOG.warnf("No content found for conversation: %s", conversationId);
                        return CompletableFuture.completedFuture(null);
                    }

                    return analyzeTopics(conversationId, content);
                })
                .thenApply(conversationTopics -> {
                    if (conversationTopics != null) {
                        topicRepository.save(conversationTopics);
                    }
                    return conversationTopics;
                })
                .exceptionally(throwable -> {
                    LOG.errorf(throwable, "Failed to detect topics for conversation: %s", conversationId);
                    return null;
                });
    }

    /**
     * Fetch conversation and all its entries.
     */
    private CompletionStage<ConversationContent> fetchConversationContent(
            String conversationId, String token, String apiKey) {
        try {
            String auth = "Bearer " + token;

            // Fetch conversation details
            Conversation conversation = memoryClient.getConversation(conversationId, auth, apiKey);

            // Fetch all entries
            EntriesResponse entriesResponse = memoryClient.getEntries(conversationId, auth, apiKey);

            ConversationContent content = new ConversationContent();
            content.conversationId = conversationId;
            content.title = conversation.getTitle();
            content.entries = entriesResponse.getData() != null ? entriesResponse.getData() : List.of();

            LOG.infof("Fetched conversation content: %d entries", content.entries.size());

            return CompletableFuture.completedFuture(content);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to fetch conversation content: %s", conversationId);
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Analyze conversation content and extract topics using LLM.
     */
    private CompletionStage<ConversationTopics> analyzeTopics(String conversationId, ConversationContent content) {
        String prompt = buildTopicDetectionPrompt(content);

        LOG.debugf("Sending prompt to Ollama (model: %s): %s", model, prompt);

        OllamaRequest request = new OllamaRequest(model, prompt);
        request.setTemperature(0.3); // Lower temperature for more focused output

        return ollamaClient.generate(request)
                .thenApply(response -> {
                    LOG.debugf("Ollama response: %s", response.getResponse());
                    List<String> topics = parseTopics(response.getResponse());

                    return new ConversationTopics(
                            conversationId,
                            content.title,
                            topics,
                            content.entries.size()
                    );
                });
    }

    /**
     * Build prompt for topic detection.
     */
    private String buildTopicDetectionPrompt(ConversationContent content) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("Analyze the following conversation and extract the main topics discussed.\n\n");
        prompt.append("Conversation Title: ").append(content.title).append("\n\n");
        prompt.append("Messages:\n");

        // Include conversation entries
        for (Entry entry : content.entries) {
            prompt.append("- [").append(entry.getRole()).append("]: ");
            // Extract text from content (simplified - you may need to parse JSON)
            prompt.append(entry.getContent()).append("\n");
        }

        prompt.append("\nExtract 3-5 main topics from this conversation.\n");
        prompt.append("Return ONLY the topics as a comma-separated list, nothing else.\n");
        prompt.append("Example format: artificial intelligence, machine learning, neural networks\n");
        prompt.append("\nTopics:");

        return prompt.toString();
    }

    /**
     * Parse topics from LLM response.
     */
    private List<String> parseTopics(String llmResponse) {
        if (llmResponse == null || llmResponse.trim().isEmpty()) {
            return List.of();
        }

        // Clean up the response and split by comma
        String cleaned = llmResponse.trim()
                .replaceAll("^Topics?:\\s*", "")  // Remove "Topics:" prefix
                .replaceAll("[\\n\\r]+", ",")      // Replace newlines with commas
                .toLowerCase();

        return Arrays.stream(cleaned.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Helper class to hold conversation content.
     */
    private static class ConversationContent {
        String conversationId;
        String title;
        List<Entry> entries;

        boolean isEmpty() {
            return entries == null || entries.isEmpty();
        }
    }
}
