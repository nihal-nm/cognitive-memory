package io.github.rigazilla.memory.cognition.justify;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/api/memories")
@Tag(name = "Memory Justification",
    description = "Retrieve memories with full provenance and source conversation context")
public class MemoryJustifyResource {

    private static final Logger LOG = Logger.getLogger(MemoryJustifyResource.class);

    @Inject
    MemoryJustifyService justifyService;

    @GET
    @Path("/{memoryId}/justify")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get memory justification",
        description = "Fetches a memory and expands the entry IDs from its provenance "
            + "into full conversation content, showing why the memory was created.")
    @APIResponse(responseCode = "200", description = "Memory with expanded source entries")
    @APIResponse(responseCode = "404", description = "Memory not found")
    @APIResponse(responseCode = "500", description = "Failed to retrieve memory or source entries")
    public Response getMemoryJustify(
            @Parameter(description = "Memory UUID",
                example = "550e8400-e29b-41d4-a716-446655440000")
            @PathParam("memoryId") String memoryId) {
        try {
            LOG.infof("GET /api/memories/%s/justify", memoryId);

            MemoryJustifyResponse response = justifyService.getMemoryJustify(memoryId);

            return Response.ok(response).build();

        } catch (MemoryJustifyService.MemoryNotFoundException e) {
            LOG.warnf("Memory not found: %s", memoryId);
            return Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Memory not found: " + memoryId))
                .build();

        } catch (MemoryJustifyService.JustifyException e) {
            LOG.errorf(e, "Failed to retrieve memory justify: %s", memoryId);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(new ErrorResponse("Failed to retrieve memory justify: " + e.getMessage()))
                .build();

        } catch (Exception e) {
            LOG.errorf(e, "Unexpected error retrieving memory justify: %s", memoryId);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(new ErrorResponse("Unexpected error: " + e.getMessage()))
                .build();
        }
    }

    public record ErrorResponse(String error) {}
}
