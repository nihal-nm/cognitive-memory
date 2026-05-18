package io.github.rigazilla.memory.ollama;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.concurrent.CompletionStage;

/**
 * REST client for Ollama API.
 */
@Path("/api")
@RegisterRestClient(configKey = "ollama")
public interface OllamaClient {

    @POST
    @Path("/generate")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    CompletionStage<OllamaResponse> generate(OllamaRequest request);
}
