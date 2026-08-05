package io.github.rigazilla.memory.cognition.profile;

import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
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

@Path("/api/consolidate")
@Tag(name = "Profile Consolidation",
    description = "Trigger LLM-based consolidation of user memories into structured profiles")
public class ProfileContextResource {

    private static final Logger LOG = Logger.getLogger(ProfileContextResource.class);

    @Inject
    ProfileContextService profileContextService;

    @POST
    @Path("/{userId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Consolidate user profile",
        description = "Loads all cognition memories for the user, consolidates them into "
            + "a structured profile using an LLM, and writes the result back to Memory Service.")
    @APIResponse(responseCode = "200", description = "Profile consolidated successfully")
    @APIResponse(responseCode = "500", description = "Consolidation failed")
    public Response consolidateProfile(
            @Parameter(description = "User ID to consolidate", example = "alice")
            @PathParam("userId") String userId) {
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

    public record ConsolidationResponse(
        String status,
        String message,
        String userId,
        String generatedAt,
        int sectionsCount
    ) {}
}
