package io.github.rigazilla.memory.cognition.justify;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * REST API for retrieving memory justify.
 * Expands entry IDs from provenance into full entry content to show why a memory was created.
 */
@Path("/api/memories")
public class MemoryJustifyResource {
    
    private static final Logger LOG = Logger.getLogger(MemoryJustifyResource.class);
    
    @Inject
    MemoryJustifyService justifyService;
    
    /**
     * Get memory with full justification (expanded source entries).
     *
     * Example: GET /api/memories/550e8400-e29b-41d4-a716-446655440000/justify
     * 
     * Returns:
     * - 200 OK with memory and expanded source entries
     * - 404 Not Found if memory doesn't exist
     * - 500 Internal Server Error on other failures
     *
     * @param memoryId Memory UUID
     * @return Memory with expanded justification
     */
    @GET
    @Path("/{memoryId}/justify")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getMemoryJustify(@PathParam("memoryId") String memoryId) {
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
    
    /**
     * Error response for API errors.
     */
    public record ErrorResponse(String error) {}
}

// Made with Bob
