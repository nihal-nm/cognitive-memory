package io.github.rigazilla.memory.cognition.queue;

import io.github.chirino.memory.grpc.v1.AdminConversation;
import io.github.chirino.memory.grpc.v1.AdminConversationsServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for JobProcessor.
 * 
 * Focuses on:
 * - Queue management and locking
 * - Async processing
 * - Error handling
 * - Resource cleanup
 * 
 * Note: Full pipeline integration tests are in separate integration test suite.
 */
class JobProcessorTest {

    private JobProcessor processor;
    private JobQueueRegistry registry;
    private AdminConversationsServiceGrpc.AdminConversationsServiceBlockingStub conversationsStub;
    private ManagedChannel mockChannel;

    @BeforeEach
    void setUp() {
        processor = new JobProcessor();
        registry = mock(JobQueueRegistry.class);
        conversationsStub = mock(AdminConversationsServiceGrpc.AdminConversationsServiceBlockingStub.class);
        mockChannel = mock(ManagedChannel.class);

        // Inject mocks
        processor.registry = registry;
        processor.conversationsStub = conversationsStub;
        processor.channel = mockChannel;

        // Set config
        processor.grpcHost = "localhost";
        processor.grpcPort = 8082;
        processor.apiKey = "test-key";
        processor.clientId = "test-client";
        processor.runtimeId = "test-runtime";
        processor.runtimeVersion = "1.0.0";
    }

    @Test
    void testStartProcessing_EmptyQueue_ReleasesLock() {
        // Given: Empty queue
        ConversationJobQueue queue = mock(ConversationJobQueue.class);
        when(registry.getOrCreateQueue("conv-1")).thenReturn(queue);
        when(queue.startProcessing()).thenReturn(true);
        when(queue.isEmpty()).thenReturn(true);

        // When: Start processing
        processor.startProcessing("conv-1");

        // Then: Should acquire lock, check empty, release lock, remove queue
        verify(queue).startProcessing();
        verify(queue, atLeastOnce()).isEmpty();
        verify(queue).stopProcessing();
        verify(registry).removeQueue("conv-1");
    }

    @Test
    void testStartProcessing_AlreadyProcessing_Skips() {
        // Given: Queue already processing
        ConversationJobQueue queue = mock(ConversationJobQueue.class);
        when(registry.getOrCreateQueue("conv-1")).thenReturn(queue);
        when(queue.startProcessing()).thenReturn(false);

        // When: Start processing
        processor.startProcessing("conv-1");

        // Then: Should skip without polling
        verify(queue).startProcessing();
        verify(queue, never()).poll();
        verify(queue, never()).stopProcessing();
    }

    @Test
    void testStartProcessing_NonEmptyQueue_PollsJobs() {
        // Given: Queue with jobs that returns null on poll (simulating empty after check)
        ConversationJobQueue queue = mock(ConversationJobQueue.class);
        when(registry.getOrCreateQueue("conv-1")).thenReturn(queue);
        when(queue.startProcessing()).thenReturn(true);
        when(queue.isEmpty()).thenReturn(false, true);
        when(queue.poll()).thenReturn(null); // Simulate interrupted/empty

        // When: Start processing
        processor.startProcessing("conv-1");

        // Then: Should attempt to poll
        verify(queue).poll();
        verify(queue).stopProcessing();
    }

    @Test
    void testStartProcessing_AlwaysReleasesLock() {
        // Given: Queue that throws exception during isEmpty check
        ConversationJobQueue queue = mock(ConversationJobQueue.class);
        when(registry.getOrCreateQueue("conv-1")).thenReturn(queue);
        when(queue.startProcessing()).thenReturn(true);
        when(queue.isEmpty()).thenThrow(new RuntimeException("Test exception"));

        // When: Start processing (should handle exception)
        assertThrows(RuntimeException.class, () -> processor.startProcessing("conv-1"));

        // Then: Should still release lock
        verify(queue).stopProcessing();
    }

    @Test
    void testStartProcessing_NonEmptyAfterProcessing_DoesNotRemoveQueue() {
        // Given: Queue still has items after processing
        ConversationJobQueue queue = mock(ConversationJobQueue.class);
        when(registry.getOrCreateQueue("conv-1")).thenReturn(queue);
        when(queue.startProcessing()).thenReturn(true);
        when(queue.isEmpty()).thenReturn(false, false); // Still not empty after poll
        when(queue.poll()).thenReturn(null); // Break loop

        // When: Start processing
        processor.startProcessing("conv-1");

        // Then: Should not remove queue
        verify(registry, never()).removeQueue("conv-1");
    }

    @Test
    void testStartProcessingAsync_ReturnsCompletableFuture() {
        // Given: Queue
        ConversationJobQueue queue = mock(ConversationJobQueue.class);
        when(registry.getOrCreateQueue("conv-1")).thenReturn(queue);
        when(queue.startProcessing()).thenReturn(true);
        when(queue.isEmpty()).thenReturn(true);

        // When: Start async
        CompletableFuture<Void> future = processor.startProcessingAsync("conv-1");

        // Then: Should return future that completes
        assertNotNull(future);
        assertDoesNotThrow(() -> future.get());
    }

    @Test
    void testStartProcessingAsync_HandlesException() {
        // Given: Queue that throws exception
        ConversationJobQueue queue = mock(ConversationJobQueue.class);
        when(registry.getOrCreateQueue("conv-1")).thenReturn(queue);
        when(queue.startProcessing()).thenThrow(new RuntimeException("Test error"));

        // When: Start async
        CompletableFuture<Void> future = processor.startProcessingAsync("conv-1");

        // Then: Future should complete exceptionally
        assertThrows(Exception.class, () -> future.get());
    }

    @Test
    void testCleanup_ShutsDownChannel() {
        // Given: Channel not shutdown
        when(mockChannel.isShutdown()).thenReturn(false);
        when(mockChannel.shutdown()).thenReturn(mockChannel);

        // When: Cleanup
        processor.cleanup();

        // Then: Should shutdown channel
        verify(mockChannel).shutdown();
    }

    @Test
    void testCleanup_AlreadyShutdown_DoesNothing() {
        // Given: Channel already shutdown
        when(mockChannel.isShutdown()).thenReturn(true);

        // When: Cleanup
        processor.cleanup();

        // Then: Should not call shutdown again
        verify(mockChannel, never()).shutdown();
    }

    @Test
    void testCleanup_NullChannel_DoesNotThrow() {
        // Given: Null channel
        processor.channel = null;

        // When/Then: Should not throw
        assertDoesNotThrow(() -> processor.cleanup());
    }

    @Test
    void testInit_CreatesGrpcChannel() {
        // Given: Fresh processor
        JobProcessor newProcessor = new JobProcessor();
        newProcessor.grpcHost = "test-host";
        newProcessor.grpcPort = 9999;
        newProcessor.apiKey = "key";
        newProcessor.clientId = "client";

        // When: Init
        newProcessor.init();

        // Then: Should create channel and stub
        assertNotNull(newProcessor.channel);
        assertNotNull(newProcessor.conversationsStub);

        // Cleanup
        newProcessor.cleanup();
    }

    @Test
    void testJobProcessingException_PreservesMessage() {
        // Given: Exception with message and cause
        RuntimeException cause = new RuntimeException("Root cause");
        
        // When: Create JobProcessingException
        JobProcessor.JobProcessingException exception =
            new JobProcessor.JobProcessingException("Processing failed", cause);

        // Then: Should preserve message and cause
        assertEquals("Processing failed", exception.getMessage());
        assertEquals(cause, exception.getCause());
    }

    // -------------------------------------------------------------------------
    // approximateObservedAtCount counter test (JP-N1)
    // -------------------------------------------------------------------------

    @Test
    void approximateObservedAtCountStartsAtZero() {
        // JP-N1: baseline — freshly constructed processor, no jobs run yet.
        // Verifies the public accessor is wired correctly and the counter
        // initialises to zero before any job has been processed.
        // The increment path (fallback when no entry createdAt is present) is
        // covered by the end-to-end integration test suite where Arc CDI is live.
        assertEquals(0L, processor.getApproximateObservedAtCount(),
            "counter must start at zero before any job is processed");
    }
}