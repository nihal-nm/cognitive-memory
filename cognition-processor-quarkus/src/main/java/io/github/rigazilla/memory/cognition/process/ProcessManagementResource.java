package io.github.rigazilla.memory.cognition.process;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * REST API for managing registered cognitive processes.
 */
@Path("/api/processes")
@Produces(MediaType.APPLICATION_JSON)
public class ProcessManagementResource {

    @Inject
    CognitiveProcessManager manager;

    @GET
    public List<ManagedProcessInfo> list() {
        return manager.listProcesses();
    }

    @GET
    @Path("/{id}")
    public ManagedProcessInspection inspect(@PathParam("id") String processId) {
        try {
            return manager.inspect(processId);
        } catch (NoSuchElementException e) {
            throw new WebApplicationException(e.getMessage(), 404);
        }
    }

    @POST
    @Path("/{id}/start")
    public ManagedProcessInspection start(@PathParam("id") String processId) {
        try {
            return manager.start(processId);
        } catch (NoSuchElementException e) {
            throw new WebApplicationException(e.getMessage(), 404);
        } catch (UnsupportedOperationException e) {
            throw new WebApplicationException(e.getMessage(), 501);
        }
    }

    @POST
    @Path("/{id}/enable")
    public ManagedProcessInspection enable(@PathParam("id") String processId) {
        try {
            return manager.enable(processId);
        } catch (NoSuchElementException e) {
            throw new WebApplicationException(e.getMessage(), 404);
        } catch (UnsupportedOperationException e) {
            throw new WebApplicationException(e.getMessage(), 501);
        }
    }

    @POST
    @Path("/{id}/disable")
    public ManagedProcessInspection disable(@PathParam("id") String processId) {
        try {
            return manager.disable(processId);
        } catch (NoSuchElementException e) {
            throw new WebApplicationException(e.getMessage(), 404);
        } catch (UnsupportedOperationException e) {
            throw new WebApplicationException(e.getMessage(), 501);
        }
    }
}
