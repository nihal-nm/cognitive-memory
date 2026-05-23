package io.github.rigazilla.memory.cognition.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rigazilla.memory.cognition.event.ScopeJob;
import io.github.rigazilla.memory.cognition.evidence.EvidencePack;
import io.github.rigazilla.memory.cognition.evidence.TranscriptLoader;
import io.github.rigazilla.memory.cognition.extraction.DurableExtractionResponse;
import io.github.rigazilla.memory.cognition.extraction.DurableMemoryExtractor;
import io.github.rigazilla.memory.cognition.extraction.MemoryCandidate;
import io.github.rigazilla.memory.cognition.verification.DurableMemoryVerifier;
import io.github.rigazilla.memory.cognition.verification.DurableVerificationResponse;
import io.github.rigazilla.memory.cognition.writer.MemoryWriter;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * Processes jobs from conversation queues on virtual threads.
 * Ensures singleton processing per conversation: only one job processes at a time.
 * Multiple conversations can process in parallel via separate virtual threads.
 * 
 * Pipeline stages:
 * 1. Load evidence (transcript entries)
 * 2. Extract memory candidates (all 5 types in one LLM call)
 * 3. Verify candidates (check citations)
 * 4. Write verified memories to memory-service
 */
@ApplicationScoped
public class JobProcessor {
    
    private static final Logger LOG = Logger.getLogger(JobProcessor.class);
    
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
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
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
            // Stage 1: Load Evidence
            LOG.infof("  [1/4] Loading transcript for conversation: %s", job.conversationId());
            EvidencePack evidence = transcriptLoader.loadTranscript(job.conversationId());
            LOG.infof("  ✓ Loaded %d transcript entries", evidence.size());
            
            // Stage 2: Extract Memories
            LOG.infof("  [2/4] Extracting memories from evidence");
            String evidenceText = evidence.formatAsText();
            DurableExtractionResponse extraction = extractor.extract(evidenceText);
            
            int rawTotal = extraction.getTotalCount();
            List<MemoryCandidate> validCandidates = extraction.getAllCandidates();
            int filteredCount = rawTotal - validCandidates.size();
            
            if (filteredCount > 0) {
                LOG.warnf("  ⚠ Filtered %d invalid candidates (empty content, zero confidence, or no citations)", filteredCount);
            }
            
            LOG.infof("  ✓ Extracted %d valid memory candidates (raw=%d, filtered=%d): facts=%d, preferences=%d, procedures=%d, problemSolutions=%d, decisions=%d",
                validCandidates.size(),
                rawTotal,
                filteredCount,
                extraction.facts().size(),
                extraction.preferences().size(),
                extraction.procedures().size(),
                extraction.problemSolutions().size(),
                extraction.decisions().size());
            
            // Stage 3: Verify Memories
            LOG.infof("  [3/4] Verifying memory candidates");
            List<MemoryCandidate> allCandidates = validCandidates;
            String candidatesJson = objectMapper.writeValueAsString(allCandidates);
            DurableVerificationResponse verification = verifier.verify(candidatesJson, evidenceText);
            LOG.infof("  ✓ Verification complete: verified=%d, rejected=%d",
                verification.verified().size(),
                verification.rejected().size());
            
            // Log rejected candidates
            if (!verification.rejected().isEmpty()) {
                LOG.warnf("  ⚠ Rejected candidates:");
                for (var rejected : verification.rejected()) {
                    LOG.warnf("    - %s: %s", rejected.reason(), rejected.candidate().content());
                }
            }
            
            // Stage 4: Write Memories
            if (!verification.verified().isEmpty()) {
                LOG.infof("  [4/4] Writing %d verified memories to memory-service", verification.verified().size());
                
                // Extract userId from job metadata (assuming it's in the job context)
                // For now, use a placeholder - this should come from the conversation metadata
                String userId = "user-placeholder"; // TODO: Extract from conversation metadata
                
                memoryWriter.writeMemories(userId, verification.verified());
                LOG.infof("  ✓ Successfully wrote %d memories", verification.verified().size());
            } else {
                LOG.infof("  [4/4] No verified memories to write");
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
     * Exception thrown when job processing fails.
     */
    public static class JobProcessingException extends RuntimeException {
        public JobProcessingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
