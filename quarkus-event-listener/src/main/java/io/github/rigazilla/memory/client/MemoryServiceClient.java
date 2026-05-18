package io.github.rigazilla.memory.client;

import io.github.rigazilla.memory.client.model.Conversation;
import io.github.rigazilla.memory.client.model.Entry;
import io.github.rigazilla.memory.client.model.EntriesResponse;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * REST client for memory-service API.
 * Used to fetch full content after receiving summary events.
 */
@Path("/v1")
@RegisterRestClient(configKey = "memory-service-api")
public interface MemoryServiceClient {

    /**
     * Get conversation details by ID.
     */
    @GET
    @Path("/conversations/{conversationId}")
    @Produces(MediaType.APPLICATION_JSON)
    Conversation getConversation(@PathParam("conversationId") String conversationId,
                                  @HeaderParam("Authorization") String authorization,
                                  @HeaderParam("X-API-Key") String apiKey);

    /**
     * Get entry details by ID.
     */
    @GET
    @Path("/conversations/{conversationId}/entries/{entryId}")
    @Produces(MediaType.APPLICATION_JSON)
    Entry getEntry(@PathParam("conversationId") String conversationId,
                   @PathParam("entryId") String entryId,
                   @HeaderParam("Authorization") String authorization,
                   @HeaderParam("X-API-Key") String apiKey);

    /**
     * Get all entries for a conversation.
     */
    @GET
    @Path("/conversations/{conversationId}/entries")
    @Produces(MediaType.APPLICATION_JSON)
    EntriesResponse getEntries(@PathParam("conversationId") String conversationId,
                                @HeaderParam("Authorization") String authorization,
                                @HeaderParam("X-API-Key") String apiKey);
}
