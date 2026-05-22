package io.github.rigazilla.memory.cognition.event;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.time.Duration;

/**
 * REST endpoint for checking event client status.
 * Phase 2: Enhanced with dirty window metrics.
 */
@Path("/api/events")
public class EventStatusResource {

    @Inject
    GrpcAdminEventClient eventClient;

    @Inject
    DirtyWindowRegistry windowRegistry;

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public EventStatus getStatus() {
        return new EventStatus(
                eventClient.isConnected(),
                eventClient.getEventCount(),
                eventClient.getWindowCount(),
                windowRegistry.getOldestWindowAge().getSeconds()
        );
    }

    public record EventStatus(
            boolean connected,
            long eventCount,
            int activeWindows,
            long oldestWindowAgeSeconds
    ) {
    }
}