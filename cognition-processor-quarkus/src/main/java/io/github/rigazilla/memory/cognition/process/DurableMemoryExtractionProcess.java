package io.github.rigazilla.memory.cognition.process;

import io.github.rigazilla.memory.cognition.event.GrpcAdminEventClient;
import io.github.rigazilla.memory.cognition.queue.JobQueueRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

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
}
