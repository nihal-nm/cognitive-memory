package io.github.rigazilla.memory.api;

import io.github.rigazilla.memory.topics.ConversationTopics;
import io.github.rigazilla.memory.topics.TopicDetectionService;
import io.github.rigazilla.memory.topics.TopicRepository;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * REST API for topic detection.
 */
@Path("/api/topics")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TopicResource {

    private static final Logger LOG = Logger.getLogger(TopicResource.class);

    @Inject
    TopicRepository topicRepository;

    @Inject
    TopicDetectionService topicDetectionService;

    /**
     * Get all processed conversations with their topics.
     *
     * GET /api/topics
     */
    @GET
    public List<ConversationTopics> listAll() {
        LOG.info("📋 Listing all conversation topics");
        return topicRepository.findAll();
    }

    /**
     * Get topics for a specific conversation.
     *
     * GET /api/topics/{conversationId}
     */
    @GET
    @Path("/{conversationId}")
    public Response getTopics(@PathParam("conversationId") String conversationId) {
        LOG.infof("🔍 Getting topics for conversation: %s", conversationId);

        return topicRepository.findByConversationId(conversationId)
                .map(topics -> Response.ok(topics).build())
                .orElse(Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Topics not found for conversation: " + conversationId))
                        .build());
    }

    /**
     * Manually trigger topic detection for a conversation.
     *
     * POST /api/topics/{conversationId}/detect
     */
    @POST
    @Path("/{conversationId}/detect")
    public Response detectTopics(@PathParam("conversationId") String conversationId) {
        LOG.infof("🚀 Manually triggering topic detection for: %s", conversationId);

        topicDetectionService.detectTopicsForConversation(conversationId)
                .thenAccept(result -> {
                    if (result != null) {
                        LOG.infof("✅ Topics detected for %s: %s", conversationId, result.getTopics());
                    }
                });

        return Response.accepted()
                .entity(Map.of(
                        "message", "Topic detection started",
                        "conversationId", conversationId
                ))
                .build();
    }

    /**
     * Get statistics about topic detection.
     *
     * GET /api/topics/stats
     */
    @GET
    @Path("/stats")
    public Map<String, Object> getStats() {
        LOG.info("📊 Getting topic detection statistics");
        return topicRepository.getStats();
    }

    /**
     * Clear all topics (for testing).
     *
     * DELETE /api/topics
     */
    @DELETE
    public Response clearAll() {
        LOG.warn("🗑️  Clearing all topics");
        topicRepository.clear();
        return Response.ok(Map.of("message", "All topics cleared")).build();
    }
}
