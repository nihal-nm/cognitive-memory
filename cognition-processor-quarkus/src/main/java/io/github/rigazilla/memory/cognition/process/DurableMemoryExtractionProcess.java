package io.github.rigazilla.memory.cognition.process;

import io.github.rigazilla.memory.cognition.event.GrpcAdminEventClient;
import io.github.rigazilla.memory.cognition.queue.JobQueueRegistry;
import io.github.rigazilla.memory.cognition.resource.LlmResourceConfiguration;
import io.github.rigazilla.memory.cognition.resource.ResourceRequirements;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Managed process adapter for the durable memory extraction pipeline.
 */
@ApplicationScoped
public class DurableMemoryExtractionProcess implements CognitiveProcess {

    private static final Logger LOG = Logger.getLogger(DurableMemoryExtractionProcess.class);
    public static final String PROCESS_ID = "durable-memory-extraction";

    @Inject
    GrpcAdminEventClient eventClient;

    @Inject
    JobQueueRegistry jobQueueRegistry;

    @Override
    public String id() {
        return PROCESS_ID;
    }

    @Override
    public String displayName() {
        return "Durable Memory Extraction";
    }

    @Override
    public String description() {
        return "Event-driven extraction, verification, and writing of durable memories";
    }

    @Override
    public boolean supportsStart() {
        return true;
    }

    @Override
    public boolean supportsEnable() {
        return false;
    }

    @Override
    public boolean supportsDisable() {
        return false;
    }

    @Override
    public ManagedProcessState state() {
        return ManagedProcessState.ENABLED;
    }

    @Override
    public ManagedProcessInspection inspect() {
        JobQueueRegistry.RegistryStats stats = jobQueueRegistry.getStats();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("eventStreamConnected", eventClient.isConnected());
        details.put("eventsAccepted", eventClient.getEventCount());
        details.put("activeWindows", eventClient.getWindowCount());
        details.put("totalQueues", stats.totalQueues());
        details.put("activeQueues", stats.activeQueues());
        details.put("pendingJobs", stats.pendingJobs());

        return new ManagedProcessInspection(
            id(),
            displayName(),
            description(),
            state(),
            details
        );
    }

    @Override
    public void start() {
        LOG.infof("Start requested for process %s", id());
        eventClient.startIfNeeded();
    }

    @Override
    public ResourceRequirements getResourceRequirements() {
        // Declare LLM resources for extraction and verification
        // Both use the same configuration for now (can be overridden in application.properties)
        return ResourceRequirements.builder()
            .llm("extractor", createDefaultLlmConfig())
            .llm("verifier", createDefaultLlmConfig())
            .build();
    }

    private LlmResourceConfiguration createDefaultLlmConfig() {
        // Default configuration - will be overridden by resolver from application.properties
        return new LlmResourceConfiguration() {
            @Override
            public String getProvider() {
                return "ollama";
            }

            @Override
            public String getModel() {
                return "llama3.2";
            }

            @Override
            public Double getTemperature() {
                return 0.1;
            }

            @Override
            public Integer getMaxTokens() {
                return 4096;
            }

            @Override
            public Optional<String> getApiKey() {
                return Optional.empty();
            }

            @Override
            public Duration getTimeout() {
                return Duration.ofSeconds(120);
            }

            @Override
            public Map<String, String> getCustomProperties() {
                return Map.of();
            }
        };
    }
}
