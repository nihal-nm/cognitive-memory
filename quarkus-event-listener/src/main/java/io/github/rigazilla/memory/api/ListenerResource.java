package io.github.rigazilla.memory.api;

import io.github.rigazilla.memory.listener.ListenerConfig;
import io.github.rigazilla.memory.listener.ListenerInfo;
import io.github.rigazilla.memory.listener.ListenerManager;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Collection;
import java.util.Map;

@Path("/api/listeners")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ListenerResource {

    @Inject
    ListenerManager listenerManager;

    /**
     * Start a new listener.
     *
     * POST /api/listeners
     * {
     *   "token": "alice",
     *   "apiKey": "test-key",           // optional, uses default if not provided
     *   "conversationId": "conv-123"     // optional, listens to all conversations if not provided
     * }
     */
    @POST
    public Response startListener(ListenerConfig config) {
        if (config.getToken() == null || config.getToken().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "token is required"))
                    .build();
        }

        ListenerInfo info = listenerManager.startListener(config);
        return Response.status(Response.Status.CREATED)
                .entity(info)
                .build();
    }

    /**
     * Get all active listeners.
     *
     * GET /api/listeners
     */
    @GET
    public Collection<ListenerInfo> getAllListeners() {
        return listenerManager.getAllListeners();
    }

    /**
     * Get a specific listener by ID.
     *
     * GET /api/listeners/{id}
     */
    @GET
    @Path("/{id}")
    public Response getListener(@PathParam("id") String id) {
        ListenerInfo info = listenerManager.getListener(id);
        if (info == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Listener not found"))
                    .build();
        }
        return Response.ok(info).build();
    }

    /**
     * Stop a listener.
     *
     * DELETE /api/listeners/{id}
     */
    @DELETE
    @Path("/{id}")
    public Response stopListener(@PathParam("id") String id) {
        boolean stopped = listenerManager.stopListener(id);
        if (!stopped) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Listener not found"))
                    .build();
        }
        return Response.ok(Map.of("message", "Listener stopped", "id", id)).build();
    }

    /**
     * Stop all listeners.
     *
     * DELETE /api/listeners
     */
    @DELETE
    public Response stopAllListeners() {
        listenerManager.stopAll();
        return Response.ok(Map.of("message", "All listeners stopped")).build();
    }
}
