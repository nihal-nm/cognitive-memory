package io.github.rigazilla.memory.cognition.event;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/api/events")
@Tag(name = "Event Status", description = "Monitor the gRPC event stream and debounce windows")
public class EventStatusResource {

    @Inject
    GrpcAdminEventClient eventClient;

    @Inject
    DirtyWindowRegistry windowRegistry;

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get event stream status",
        description = "Returns the current state of the gRPC event stream connection "
            + "and pending debounce windows.")
    public EventStatus getStatus() {
        return new EventStatus(
                eventClient.isConnected(),
                eventClient.getEventCount(),
                eventClient.getWindowCount(),
                windowRegistry.getOldestWindowAge().getSeconds()
        );
    }

    @Schema(description = "Event stream status")
    public record EventStatus(
            @Schema(description = "Whether the gRPC event stream to Memory Service is active")
            boolean connected,
            @Schema(description = "Total events received since startup")
            long eventCount,
            @Schema(description = "Debounce windows currently accumulating events")
            int activeWindows,
            @Schema(description = "Age in seconds of the oldest active window")
            long oldestWindowAgeSeconds
    ) {
    }
}
