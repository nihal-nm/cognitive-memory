package io.github.rigazilla.memory.cognition.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chirino.memory.grpc.v1.AdminConversation;
import io.github.chirino.memory.grpc.v1.AdminConversationsServiceGrpc;
import io.github.chirino.memory.grpc.v1.AdminGetConversationRequest;
import io.github.rigazilla.memory.cognition.event.ScopeJob;
import io.github.rigazilla.memory.cognition.evidence.EvidencePack;
import io.github.rigazilla.memory.cognition.evidence.TranscriptLoader;
import io.github.rigazilla.memory.cognition.extraction.DurableExtractionResponse;
import io.github.rigazilla.memory.cognition.extraction.DurableMemoryExtractor;
import io.github.rigazilla.memory.cognition.extraction.MemoryCandidate;
import io.github.rigazilla.memory.cognition.model.Provenance;
import io.github.rigazilla.memory.cognition.verification.DurableMemoryVerifier;
import io.github.rigazilla.memory.cognition.verification.DurableVerificationResponse;
import io.github.rigazilla.memory.cognition.writer.MemoryWriter;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Processes jobs from conversation queues on virtual threads.
 * Ensures singleton processing per conversation: only one job processes at a time.
 * Multiple conversations can process in parallel via separate virtual threads.
 *
 * Pipeline stages:
 * 0. Load conversation metadata (to get owner user ID)
 * 1. Load evidence (transcript entries)
 * 2. Extract memory candidates (all 5 types in one LLM call)
 * 3. Verify candidates (check citations)
 * 4. Write verified memories to memory-service
 *
 * Note: Memories are written to namespace ["user", <conversation_owner>, "cognition.v1", <memory_type>]
 *       where conversation_owner is the owner_user_id from the Conversation metadata.
 */
@ApplicationScoped
public class JobProcessor {

    private static final Logger LOG = Logger.getLogger(JobProcessor.class);

    @ConfigProperty(name = "memory-service.grpc.host")
    String grpcHost;

    @ConfigProperty(name = "memory-service.grpc.port")
    int grpcPort;

    @ConfigProperty(name = "memory-service.api-key")
    String apiKey;

    @ConfigProperty(name = "memory-service.client-id")
    String clientId;

    @ConfigProperty(name = "cognition.runtime.id")
    String runtimeId;

    @ConfigProperty(name = "cognition.runtime.version", defaultValue = "1.0.0-SNAPSHOT")
    String runtimeVersion;

    @Inject
    JobQueueRegistry registry;

    @Inject
    TranscriptLoader transcriptLoader;

    @Inject
    DurableMemoryExtractor extractor;

    @Inject
    DurableMemoryVerifier verifier;

    @Inject
    MemoryWriter memoryWriter;

    ManagedChannel channel;
    AdminConversationsServiceGrpc.AdminConversationsServiceBlockingStub conversationsStub;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Counts batches where no entry {@code created_at} was available and ingestion
     * time was substituted as {@code observed_at}. Surfaced via the temporal
     * enrichment process inspect endpoint so operators can assess data quality.
     */
    private final AtomicLong approximateObservedAtCount = new AtomicLong(0);

    /** Returns the number of batches written with an approximate {@code observed_at}. */
    public long getApproximateObservedAtCount() {
        return approximateObservedAtCount.get();
    }

    @PostConstruct
    void init() {
        LOG.infof("Initializing JobProcessor gRPC clients: %s:%d", grpcHost, grpcPort);

        // Create gRPC channel with authentication interceptor
        channel = ManagedChannelBuilder
            .forAddress(grpcHost, grpcPort)
            .usePlaintext()
            .intercept(new AuthInterceptor(apiKey, clientId))
            .build();

        conversationsStub = AdminConversationsServiceGrpc.newBlockingStub(channel);

        LOG.info("JobProcessor gRPC clients initialized successfully");
    }

    /**
     * Interceptor that adds authentication headers to all gRPC calls.
     */
    private static class AuthInterceptor implements ClientInterceptor {
        private final String apiKey;
        private final String clientId;

        AuthInterceptor(String apiKey, String clientId) {
            this.apiKey = apiKey;
            this.clientId = clientId;
        }

        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                MethodDescriptor<ReqT, RespT> method,
                CallOptions callOptions,
                Channel next) {
            return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                    next.newCall(method, callOptions)) {
                @Override
                public void start(Listener<RespT> responseListener, Metadata headers) {
                    // Add authentication headers: X-API-Key and X-Client-ID
                    headers.put(Metadata.Key.of("x-api-key", Metadata.ASCII_STRING_MARSHALLER), apiKey);
                    headers.put(Metadata.Key.of("x-client-id", Metadata.ASCII_STRING_MARSHALLER), clientId);
                    super.start(responseListener, headers);
                }
            };
        }
    }

    @PreDestroy
    void cleanup() {
        if (channel != null && !channel.isShutdown()) {
            LOG.info("Shutting down JobProcessor gRPC channel");
            channel.shutdown();
        }
    }

    /**
     * Start processing jobs for a conversation.
     * Runs on a virtual thread to avoid blocking platform threads.
     * Continues processing until the queue is empty.
     *
     * @param conversationId Conversation ID
     */
    public void startProcessing(String conversationId) {
        ConversationJobQueue queue = registry.getOrCreateQueue(conversationId);

        // Try to acquire processing lock
        if (!queue.startProcessing()) {
            LOG.debugf("Conversation %s already processing, skipping", conversationId);
            return;
        }

        try {
            LOG.infof("Started processing jobs for conversation: %s", conversationId);

            // Process jobs until queue is empty
            while (!queue.isEmpty()) {
                ScopeJob job = queue.poll();
                if (job == null) {
                    break; // Interrupted
                }

                try {
                    processJob(job);
                } catch (Exception e) {
                    LOG.errorf(e, "Failed to process job for conversation %s: %s",
                        conversationId, job);
                    // Continue processing next job despite error
                }
            }

            LOG.infof("Finished processing jobs for conversation: %s", conversationId);

        } finally {
            queue.stopProcessing();

            // Clean up empty queue
            if (queue.isEmpty()) {
                registry.removeQueue(conversationId);
            }
        }
    }

    /**
     * Start processing asynchronously.
     * Returns immediately, processing happens on virtual thread.
     *
     * @param conversationId Conversation ID
     * @return CompletableFuture that completes when processing finishes
     */
    public CompletableFuture<Void> startProcessingAsync(String conversationId) {
        // Use virtual thread executor for non-blocking parallel processing
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        return CompletableFuture.runAsync(() -> startProcessing(conversationId), executor);
    }

    /**
     * Process a single job through the full pipeline.
     *
     * @param job The job to process
     */
    private void processJob(ScopeJob job) {
        // Manually activate request context for virtual thread
        ManagedContext requestContext = Arc.container().requestContext();
        if (!requestContext.isActive()) {
            requestContext.activate();
        }

        try {
            LOG.infof("▶ Processing job: %s", job);
            long startTime = System.currentTimeMillis();

            processJobInternal(job, startTime);

        } finally {
            if (requestContext.isActive()) {
                requestContext.terminate();
            }
        }
    }

    private void processJobInternal(ScopeJob job, long startTime) {
        try {
            // Stage 0: Load Conversation Metadata
            LOG.infof("  [0/5] Loading conversation metadata: %s", job.conversationId());
            String userId = getConversationOwner(job.conversationId());
            LOG.infof("  ✓ Conversation owner: %s", userId);

            // Stage 1: Load Evidence
            LOG.infof("  [1/5] Loading transcript for conversation: %s", job.conversationId());
            EvidencePack evidence = transcriptLoader.loadTranscript(
                job.conversationId(),
                job.entryIds(),
                job.previousEntryId(),
                userId  // Pass conversation owner for on-behalf-of authorization
            );
            LOG.infof("  ✓ Loaded %d transcript entries", evidence.size());

            // Build provenance from ScopeJob for this batch
            Provenance provenance = Provenance.fromScopeJobMinimal(job, runtimeId, runtimeVersion);
            LOG.debugf("  ✓ Built provenance: batch=%d entries, trigger=%s",
                provenance.entryIds().size(), provenance.batchTrigger());

            // Stage 2: Extract Memories
            LOG.infof("  [2/5] Extracting memories from evidence");
            String evidenceText = evidence.formatAsText();

            // Debug log: show formatted evidence sent to LLM
            if (LOG.isDebugEnabled()) {
                LOG.debugf("  Evidence text sent to extractor (%d chars):", evidenceText.length());
                String preview = evidenceText.length() > 500
                    ? evidenceText.substring(0, 497) + "..."
                    : evidenceText;
                LOG.debugf("  %s", preview);
                if (evidenceText.length() > 500) {
                    LOG.debugf("  ... (truncated, full length: %d chars)", evidenceText.length());
                }
            }

            DurableExtractionResponse extraction = extractor.extract(evidenceText);

            int rawTotal = extraction.getTotalCount();
            List<MemoryCandidate> validCandidates = extraction.getAllCandidates();
            List<MemoryCandidate> invalidCandidates = extraction.getInvalidCandidates();
            int filteredCount = rawTotal - validCandidates.size();

            if (filteredCount > 0) {
                LOG.debugf("  ⚠ Filtered %d invalid candidates:", filteredCount);
                for (MemoryCandidate invalid : invalidCandidates) {
                    String reason = extraction.getInvalidReason(invalid);
                    String preview = invalid.content() != null && invalid.content().length() > 50
                        ? invalid.content().substring(0, 47) + "..."
                        : (invalid.content() != null ? invalid.content() : "(null)");
                    LOG.debugf("    - [%s] %s - reason: %s, confidence: %.2f, citations: %d",
                        invalid.type(),
                        preview,
                        reason,
                        invalid.confidence(),
                        invalid.citations() != null ? invalid.citations().size() : 0);
                }
            }

            LOG.infof("  ✓ Extracted %d valid memory candidates "
                    + "(raw=%d, filtered=%d): facts=%d, preferences=%d, "
                    + "procedures=%d, problemSolutions=%d, decisions=%d",
                validCandidates.size(),
                rawTotal,
                filteredCount,
                extraction.facts().size(),
                extraction.preferences().size(),
                extraction.procedures().size(),
                extraction.problemSolutions().size(),
                extraction.decisions().size());

            // Stage 3: Verify Memories
            LOG.infof("  [3/5] Verifying memory candidates");
            List<MemoryCandidate> allCandidates = validCandidates;
            String candidatesJson = objectMapper.writeValueAsString(allCandidates);
            DurableVerificationResponse verification = verifier.verify(candidatesJson, evidenceText);
            LOG.infof("  ✓ Verification complete: verified=%d, rejected=%d",
                verification.verified().size(),
                verification.rejected().size());

            // Log rejected candidates with details
            if (!verification.rejected().isEmpty()) {
                LOG.debugf("  ⚠ Rejected %d candidates during verification:", verification.rejected().size());
                for (var rejected : verification.rejected()) {
                    String preview = rejected.candidate().content().length() > 50
                        ? rejected.candidate().content().substring(0, 47) + "..."
                        : rejected.candidate().content();
                    LOG.debugf("    - [%s] %s - reason: %s, confidence: %.2f",
                        rejected.candidate().type(),
                        preview,
                        rejected.reason(),
                        rejected.candidate().confidence());
                }
            }

            // Stage 4: Write Memories
            if (!verification.verified().isEmpty()) {
                LOG.infof("  [4/5] Writing %d verified memories to memory-service for user: %s",
                    verification.verified().size(), userId);

                // observed_at = earliest entry createdAt in this batch (when the facts were stated).
                // Falls back to provenance.processedAt() when no entry timestamps are available.
                // Log a warning: this fallback uses ingestion time, NOT the time facts were stated,
                // which violates the spec. Any memories written with this timestamp should be
                // treated as having an approximate observed_at.
                String observedAt = evidence.earliestCreatedAt().orElseGet(() -> {
                    String fallback = provenance.processedAt().toString();
                    LOG.warnf("No entry createdAt found in evidence for conversation %s; "
                        + "falling back to ingestion time %s — observed_at will be approximate",
                        provenance.conversationId(), fallback);
                    approximateObservedAtCount.incrementAndGet();
                    return fallback;
                });

                memoryWriter.writeMemories(userId, verification.verified(), provenance, observedAt);
                LOG.infof("  ✓ Successfully wrote %d memories to namespace: [\"user\", \"%s\", \"cognition.v1\", *]"
                        + " (observedAt=%s)",
                    verification.verified().size(), userId, observedAt);
                LOG.debugf("  ✓ Provenance recorded: conversation=%s, entries=%d, trigger=%s",
                    provenance.conversationId(), provenance.entryIds().size(), provenance.batchTrigger());
            } else {
                LOG.infof("  [4/5] No verified memories to write");
            }

            long duration = System.currentTimeMillis() - startTime;
            LOG.infof("✓ Job completed successfully in %dms: %s", duration, job);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            LOG.errorf(e, "✗ Job failed after %dms: %s", duration, job);
            throw new JobProcessingException("Failed to process job for conversation " + job.conversationId(), e);
        }
    }

    /**
     * Get the owner user ID for a conversation by loading conversation metadata via gRPC.
     * Uses AdminConversationsService which provides admin access without requiring membership.
     *
     * @param conversationId Conversation UUID string
     * @return Owner user ID
     * @throws JobProcessingException if conversation metadata cannot be loaded
     */
    private String getConversationOwner(String conversationId) {
        try {
            AdminGetConversationRequest request = AdminGetConversationRequest.newBuilder()
                .setConversationId(conversationId)
                .build();

            AdminConversation conversation = conversationsStub.getConversation(request);

            String ownerId = conversation.getOwnerUserId();
            LOG.debugf("Loaded conversation %s owner: %s", conversationId, ownerId);

            return ownerId;

        } catch (StatusRuntimeException e) {
            Status status = e.getStatus();
            LOG.errorf(e, "Failed to load conversation metadata for %s: %s", conversationId, status);
            throw new JobProcessingException("Failed to load conversation metadata for " + conversationId, e);
        } catch (Exception e) {
            LOG.errorf(e, "Unexpected error loading conversation metadata for %s", conversationId);
            throw new JobProcessingException("Failed to load conversation metadata for " + conversationId, e);
        }
    }

    /**
     * Exception thrown when job processing fails.
     */
    public static class JobProcessingException extends RuntimeException {
        public JobProcessingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
