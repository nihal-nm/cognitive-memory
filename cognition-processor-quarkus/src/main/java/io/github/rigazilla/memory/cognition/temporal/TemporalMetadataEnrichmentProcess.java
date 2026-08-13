package io.github.rigazilla.memory.cognition.temporal;

import io.github.rigazilla.memory.cognition.process.CognitiveProcess;
import io.github.rigazilla.memory.cognition.process.ManagedProcessInspection;
import io.github.rigazilla.memory.cognition.process.ManagedProcessState;
import io.github.rigazilla.memory.cognition.queue.JobProcessor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Managed process that backfills {@code observed_at} and {@code effective_at} into
 * cognition memories that were written before temporal metadata was introduced.
 *
 * <p>Trigger via {@code POST /api/processes/temporal-metadata-enrichment/start}.
 * The backfill runs asynchronously on a virtual thread so it does not block the
 * HTTP request or other cognitive processes.
 */
@ApplicationScoped
public class TemporalMetadataEnrichmentProcess implements CognitiveProcess {

    private static final Logger LOG = Logger.getLogger(TemporalMetadataEnrichmentProcess.class);
    public static final String PROCESS_ID = "temporal-metadata-enrichment";

    @Inject
    TemporalMetadataEnrichmentService enrichmentService;

    @Inject
    JobProcessor jobProcessor;

    private final AtomicReference<Instant> lastStartTime = new AtomicReference<>();
    private final AtomicReference<Instant> lastFinishTime = new AtomicReference<>();
    private final AtomicReference<String> lastStatus = new AtomicReference<>("never_run");

    @Override
    public String id() {
        return PROCESS_ID;
    }

    @Override
    public String displayName() {
        return "Temporal Metadata Enrichment";
    }

    @Override
    public String description() {
        return "Backfills observed_at and effective_at into cognition memories that lack temporal metadata";
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
        Map<String, Object> details = new LinkedHashMap<>();
        
        // Common fields expected by frontend
        details.put("mode", "manual_trigger");
        details.put("lastRunTime",
            lastStartTime.get() != null ? lastStartTime.get().toString() : "never");
        details.put("lastRunStatus", lastStatus.get());
        details.put("lastRunUserId", "system");
        
        // Event stream fields (N/A for this process)
        details.put("eventStreamConnected", false);
        details.put("eventsAccepted", 0);
        details.put("activeWindows", 0);
        details.put("totalQueues", 0);
        details.put("activeQueues", 0);
        details.put("pendingJobs", 0);
        
        // Process-specific metrics
        details.put("running", enrichmentService.isRunning());
        details.put("lastStartTime",
            lastStartTime.get() != null ? lastStartTime.get().toString() : "never");
        details.put("lastFinishTime",
            lastFinishTime.get() != null ? lastFinishTime.get().toString() : "never");
        details.put("scanned", enrichmentService.getScanned());
        details.put("enriched", enrichmentService.getEnriched());
        details.put("skipped", enrichmentService.getSkipped());
        details.put("conflicts", enrichmentService.getConflicts());
        details.put("errors", enrichmentService.getErrors());
        details.put("approximateObservedAtWrites", jobProcessor.getApproximateObservedAtCount());
        
        // Empty resourceTypes (this process doesn't use LLMs)
        details.put("resourceTypes", Map.of());
        
        return new ManagedProcessInspection(
            id(),
            displayName(),
            description(),
            state(),
            details
        );
    }

    /**
     * Starts the backfill asynchronously on a virtual thread.
     * Returns immediately; progress is visible via {@link #inspect()}.
     */
    @Override
    public void start() {
        if (enrichmentService.isRunning()) {
            LOG.infof("Temporal metadata backfill already running; ignoring duplicate start request");
            return;
        }
        LOG.infof("Start requested for process %s", id());
        lastStartTime.set(Instant.now());
        lastStatus.set("running");

        Executors.newVirtualThreadPerTaskExecutor().execute(() -> {
            try {
                enrichmentService.runBackfill();
                lastFinishTime.set(Instant.now());
                lastStatus.set("completed");
            } catch (Exception e) {
                lastFinishTime.set(Instant.now());
                lastStatus.set("error: " + e.getMessage());
                LOG.errorf(e, "Temporal metadata backfill failed");
            }
        });
    }
}
