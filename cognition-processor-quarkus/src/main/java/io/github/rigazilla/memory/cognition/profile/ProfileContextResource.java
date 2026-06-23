package io.github.rigazilla.memory.cognition.profile;

import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * REST endpoint for manual profile consolidation triggers.
 * Phase 0 prototype - manual trigger only, no scheduling.
 */
@Path("/api/consolidate")
public class ProfileContextResource {
    
    private static final Logger LOG = Logger.getLogger(ProfileContextResource.class);
    
    @Inject
    ProfileContextService profileContextService;
    
    /**
     * Trigger profile consolidation for a specific user.
     * 
     * @param userId User ID to consolidate profile for
     * @return Response with consolidation status
     */
    @POST
    @Path("/{userId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response consolidateProfile(@PathParam("userId") String userId) {
        LOG.infof("Received consolidation request for user: %s", userId);
        
        try {
            ProfileSnapshot snapshot = profileContextService.consolidateProfile(userId);
            
            return Response.ok()
                .entity(new ConsolidationResponse(
                    "success",
                    "Profile consolidated successfully",
                    userId,
                    snapshot.generatedAt().toString(),
                    snapshot.sections().size()
                ))
                .build();
                
        } catch (Exception e) {
            LOG.errorf(e, "Failed to consolidate profile for user %s", userId);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(new ConsolidationResponse(
                    "error",
                    "Profile consolidation failed: " + e.getMessage(),
                    userId,
                    null,
                    0
                ))
                .build();
        }
    }
    
    /**
     * Response DTO for consolidation endpoint.
     */
    public record ConsolidationResponse(
        String status,
        String message,
        String userId,
        String generatedAt,
        int sectionsCount
    ) {}
}
