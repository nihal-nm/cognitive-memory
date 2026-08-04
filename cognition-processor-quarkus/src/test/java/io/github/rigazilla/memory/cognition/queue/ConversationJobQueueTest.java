package io.github.rigazilla.memory.cognition.queue;

import io.github.rigazilla.memory.cognition.event.ScopeJob;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ConversationJobQueue.
 * Tests queue operations, processing state management, and thread safety.
 */
class ConversationJobQueueTest {

    @Test
    void testCreation() {
        // Act
        ConversationJobQueue queue = new ConversationJobQueue("conv-123");

        // Assert
        assertThat(queue.getConversationId()).isEqualTo("conv-123");
        assertThat(queue.isEmpty()).isTrue();
        assertThat(queue.size()).isZero();
        assertThat(queue.isProcessing()).isFalse();
    }

    @Test
    void testEnqueue_singleJob() {
        // Arrange
        ConversationJobQueue queue = new ConversationJobQueue("conv-123");
        ScopeJob job = createTestJob("conv-123");

        // Act
        boolean result = queue.enqueue(job);

        // Assert
        assertThat(result).isTrue();
        assertThat(queue.size()).isEqualTo(1);
        assertThat(queue.isEmpty()).isFalse();
    }

    @Test
    void testEnqueue_multipleJobs() {
        // Arrange
        ConversationJobQueue queue = new ConversationJobQueue("conv-123");
        ScopeJob job1 = createTestJob("conv-123");
        ScopeJob job2 = createTestJob("conv-123");
        ScopeJob job3 = createTestJob("conv-123");

        // Act
        queue.enqueue(job1);
        queue.enqueue(job2);
        queue.enqueue(job3);

        // Assert
        assertThat(queue.size()).isEqualTo(3);
    }

    @Test
    void testPoll_retrievesJob() {
        // Arrange
        ConversationJobQueue queue = new ConversationJobQueue("conv-123");
        ScopeJob job = createTestJob("conv-123");
        queue.enqueue(job);

        // Act
        ScopeJob polled = queue.poll();

        // Assert
        assertThat(polled).isEqualTo(job);
        assertThat(queue.isEmpty()).isTrue();
        assertThat(queue.size()).isZero();
    }

    @Test
    void testPoll_fifoOrder() {
        // Arrange
        ConversationJobQueue queue = new ConversationJobQueue("conv-123");
        ScopeJob job1 = new ScopeJob("conv-123", "c1", "c1", List.of("e1"), null, 
            Instant.now(), Instant.now(), "trigger1");
        ScopeJob job2 = new ScopeJob("conv-123", "c2", "c2", List.of("e2"), null, 
            Instant.now(), Instant.now(), "trigger2");
        ScopeJob job3 = new ScopeJob("conv-123", "c3", "c3", List.of("e3"), null, 
            Instant.now(), Instant.now(), "trigger3");

        queue.enqueue(job1);
        queue.enqueue(job2);
        queue.enqueue(job3);

        // Act & Assert - FIFO order
        assertThat(queue.poll()).isEqualTo(job1);
        assertThat(queue.poll()).isEqualTo(job2);
        assertThat(queue.poll()).isEqualTo(job3);
        assertThat(queue.isEmpty()).isTrue();
    }

    @Test
    void testStartProcessing_initiallyFalse() {
        // Arrange
        ConversationJobQueue queue = new ConversationJobQueue("conv-123");

        // Act
        boolean started = queue.startProcessing();

        // Assert
        assertThat(started).isTrue();
        assertThat(queue.isProcessing()).isTrue();
    }

    @Test
    void testStartProcessing_alreadyProcessing() {
        // Arrange
        ConversationJobQueue queue = new ConversationJobQueue("conv-123");
        queue.startProcessing();

        // Act - Try to start again
        boolean started = queue.startProcessing();

        // Assert - Should fail
        assertThat(started).isFalse();
        assertThat(queue.isProcessing()).isTrue();
    }

    @Test
    void testStopProcessing() {
        // Arrange
        ConversationJobQueue queue = new ConversationJobQueue("conv-123");
        queue.startProcessing();

        // Act
        queue.stopProcessing();

        // Assert
        assertThat(queue.isProcessing()).isFalse();
    }

    @Test
    void testProcessingCycle() {
        // Arrange
        ConversationJobQueue queue = new ConversationJobQueue("conv-123");

        // Act & Assert - Full cycle
        assertThat(queue.isProcessing()).isFalse();
        
        assertThat(queue.startProcessing()).isTrue();
        assertThat(queue.isProcessing()).isTrue();
        
        queue.stopProcessing();
        assertThat(queue.isProcessing()).isFalse();
        
        // Can start again after stopping
        assertThat(queue.startProcessing()).isTrue();
        assertThat(queue.isProcessing()).isTrue();
    }

    @Test
    void testIsEmpty_afterEnqueueAndPoll() {
        // Arrange
        ConversationJobQueue queue = new ConversationJobQueue("conv-123");
        ScopeJob job = createTestJob("conv-123");

        // Initially empty
        assertThat(queue.isEmpty()).isTrue();

        // After enqueue
        queue.enqueue(job);
        assertThat(queue.isEmpty()).isFalse();

        // After poll
        queue.poll();
        assertThat(queue.isEmpty()).isTrue();
    }

    @Test
    void testSize_tracksQueueSize() {
        // Arrange
        ConversationJobQueue queue = new ConversationJobQueue("conv-123");

        // Initially 0
        assertThat(queue.size()).isZero();

        // Add jobs
        queue.enqueue(createTestJob("conv-123"));
        assertThat(queue.size()).isEqualTo(1);

        queue.enqueue(createTestJob("conv-123"));
        assertThat(queue.size()).isEqualTo(2);

        // Remove job
        queue.poll();
        assertThat(queue.size()).isEqualTo(1);

        queue.poll();
        assertThat(queue.size()).isZero();
    }

    @Test
    void testToString_containsKeyFields() {
        // Arrange
        ConversationJobQueue queue = new ConversationJobQueue("conv-123");
        queue.enqueue(createTestJob("conv-123"));
        queue.enqueue(createTestJob("conv-123"));
        queue.startProcessing();

        // Act
        String result = queue.toString();

        // Assert
        assertThat(result).contains("ConversationJobQueue");
        assertThat(result).contains("conversationId=conv-123");
        assertThat(result).contains("size=2");
        assertThat(result).contains("processing=true");
    }

    @Test
    void testConcurrentEnqueue() throws InterruptedException {
        // Arrange
        ConversationJobQueue queue = new ConversationJobQueue("conv-123");
        int threadCount = 10;
        int jobsPerThread = 10;
        CountDownLatch latch = new CountDownLatch(threadCount);

        // Act - Multiple threads enqueuing
        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                for (int j = 0; j < jobsPerThread; j++) {
                    queue.enqueue(createTestJob("conv-123"));
                }
                latch.countDown();
            }).start();
        }

        // Wait for all threads
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        // Assert - All jobs enqueued
        assertThat(queue.size()).isEqualTo(threadCount * jobsPerThread);
    }

    @Test
    void testProcessingStateThreadSafety() throws InterruptedException {
        // Arrange
        ConversationJobQueue queue = new ConversationJobQueue("conv-123");
        int threadCount = 10;
        CountDownLatch latch = new CountDownLatch(threadCount);
        int[] successCount = {0};

        // Act - Multiple threads trying to start processing
        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                if (queue.startProcessing()) {
                    synchronized (successCount) {
                        successCount[0]++;
                    }
                }
                latch.countDown();
            }).start();
        }

        // Wait for all threads
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        // Assert - Only one thread should succeed
        assertThat(successCount[0]).isEqualTo(1);
        assertThat(queue.isProcessing()).isTrue();
    }

    @Test
    void testGetConversationId() {
        // Arrange
        ConversationJobQueue queue = new ConversationJobQueue("test-conv-456");

        // Act
        String id = queue.getConversationId();

        // Assert
        assertThat(id).isEqualTo("test-conv-456");
    }

    private ScopeJob createTestJob(String conversationId) {
        return new ScopeJob(
            conversationId,
            "cursor-1",
            "cursor-2",
            List.of("entry-1"),
            null,
            Instant.now(),
            Instant.now(),
            "test-trigger"
        );
    }
}
