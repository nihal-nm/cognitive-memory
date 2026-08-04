package io.github.rigazilla.memory.cognition.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ScopeJob record.
 * Tests record creation, field accessors, and equality semantics.
 */
class ScopeJobTest {

    @Test
    void testRecordCreation_withAllFields() {
        // Arrange
        String conversationId = "conv-123";
        String firstCursor = "cursor-1";
        String latestCursor = "cursor-2";
        List<String> entryIds = List.of("entry-1", "entry-2", "entry-3");
        String previousEntryId = "entry-0";
        Instant observedAt = Instant.parse("2026-01-01T10:00:00Z");
        Instant processAfter = Instant.parse("2026-01-01T10:00:05Z");
        String trigger = "debounce_delay";

        // Act
        ScopeJob job = new ScopeJob(
            conversationId,
            firstCursor,
            latestCursor,
            entryIds,
            previousEntryId,
            observedAt,
            processAfter,
            trigger
        );

        // Assert
        assertThat(job.conversationId()).isEqualTo(conversationId);
        assertThat(job.firstEventCursor()).isEqualTo(firstCursor);
        assertThat(job.latestEventCursor()).isEqualTo(latestCursor);
        assertThat(job.entryIds()).isEqualTo(entryIds);
        assertThat(job.previousEntryId()).isEqualTo(previousEntryId);
        assertThat(job.observedAt()).isEqualTo(observedAt);
        assertThat(job.processAfter()).isEqualTo(processAfter);
        assertThat(job.trigger()).isEqualTo(trigger);
    }

    @Test
    void testRecordCreation_withNullPreviousEntryId() {
        // Arrange & Act - First window has no previous entry
        ScopeJob job = new ScopeJob(
            "conv-123",
            "cursor-1",
            "cursor-2",
            List.of("entry-1"),
            null,  // First window
            Instant.now(),
            Instant.now(),
            "debounce_delay"
        );

        // Assert
        assertThat(job.previousEntryId()).isNull();
        assertThat(job.conversationId()).isEqualTo("conv-123");
    }

    @Test
    void testRecordCreation_withDifferentTriggers() {
        // Test max_batch_age trigger
        ScopeJob maxAgeJob = new ScopeJob(
            "conv-123",
            "cursor-1",
            "cursor-2",
            List.of("entry-1"),
            null,
            Instant.now(),
            Instant.now(),
            "max_batch_age"
        );
        assertThat(maxAgeJob.trigger()).isEqualTo("max_batch_age");

        // Test max_batch_entries trigger
        ScopeJob maxEntriesJob = new ScopeJob(
            "conv-123",
            "cursor-1",
            "cursor-2",
            List.of("entry-1", "entry-2", "entry-3"),
            null,
            Instant.now(),
            Instant.now(),
            "max_batch_entries"
        );
        assertThat(maxEntriesJob.trigger()).isEqualTo("max_batch_entries");

        // Test checkpoint_bounded trigger
        ScopeJob checkpointJob = new ScopeJob(
            "conv-123",
            "cursor-1",
            "cursor-2",
            List.of("entry-1"),
            null,
            Instant.now(),
            Instant.now(),
            "checkpoint_bounded"
        );
        assertThat(checkpointJob.trigger()).isEqualTo("checkpoint_bounded");
    }

    @Test
    void testRecordEquality_sameValues() {
        // Arrange
        Instant observedAt = Instant.parse("2026-01-01T10:00:00Z");
        Instant processAfter = Instant.parse("2026-01-01T10:00:05Z");
        
        ScopeJob job1 = new ScopeJob(
            "conv-123",
            "cursor-1",
            "cursor-2",
            List.of("entry-1"),
            null,
            observedAt,
            processAfter,
            "debounce_delay"
        );
        
        ScopeJob job2 = new ScopeJob(
            "conv-123",
            "cursor-1",
            "cursor-2",
            List.of("entry-1"),
            null,
            observedAt,
            processAfter,
            "debounce_delay"
        );

        // Assert
        assertThat(job1).isEqualTo(job2);
        assertThat(job1.hashCode()).isEqualTo(job2.hashCode());
    }

    @Test
    void testRecordEquality_differentConversationIds() {
        // Arrange
        Instant now = Instant.now();
        ScopeJob job1 = new ScopeJob(
            "conv-123",
            "cursor-1",
            "cursor-2",
            List.of("entry-1"),
            null,
            now,
            now,
            "debounce_delay"
        );
        
        ScopeJob job2 = new ScopeJob(
            "conv-456",  // Different conversation
            "cursor-1",
            "cursor-2",
            List.of("entry-1"),
            null,
            now,
            now,
            "debounce_delay"
        );

        // Assert
        assertThat(job1).isNotEqualTo(job2);
    }

    @Test
    void testRecordEquality_differentEntryIds() {
        // Arrange
        Instant now = Instant.now();
        ScopeJob job1 = new ScopeJob(
            "conv-123",
            "cursor-1",
            "cursor-2",
            List.of("entry-1", "entry-2"),
            null,
            now,
            now,
            "debounce_delay"
        );
        
        ScopeJob job2 = new ScopeJob(
            "conv-123",
            "cursor-1",
            "cursor-2",
            List.of("entry-1", "entry-3"),  // Different entries
            null,
            now,
            now,
            "debounce_delay"
        );

        // Assert
        assertThat(job1).isNotEqualTo(job2);
    }

    @Test
    void testEntryIds_preservesOrder() {
        // Arrange
        List<String> orderedEntries = List.of("entry-1", "entry-2", "entry-3");
        
        // Act
        ScopeJob job = new ScopeJob(
            "conv-123",
            "cursor-1",
            "cursor-3",
            orderedEntries,
            null,
            Instant.now(),
            Instant.now(),
            "debounce_delay"
        );

        // Assert - Order is preserved
        assertThat(job.entryIds()).containsExactly("entry-1", "entry-2", "entry-3");
    }

    @Test
    void testProcessAfter_isAfterObservedAt() {
        // Arrange
        Instant observedAt = Instant.parse("2026-01-01T10:00:00Z");
        Instant processAfter = Instant.parse("2026-01-01T10:00:05Z");

        // Act
        ScopeJob job = new ScopeJob(
            "conv-123",
            "cursor-1",
            "cursor-2",
            List.of("entry-1"),
            null,
            observedAt,
            processAfter,
            "debounce_delay"
        );

        // Assert
        assertThat(job.processAfter()).isAfter(job.observedAt());
    }

    @Test
    void testToString_containsKeyFields() {
        // Arrange
        ScopeJob job = new ScopeJob(
            "conv-123",
            "cursor-1",
            "cursor-2",
            List.of("entry-1", "entry-2"),
            null,
            Instant.now(),
            Instant.now(),
            "debounce_delay"
        );

        // Act
        String result = job.toString();

        // Assert - toString should contain key identifying information
        assertThat(result).contains("conv-123");
        assertThat(result).contains("debounce_delay");
    }

    @Test
    void testMultipleEntries_handlesLargeBatch() {
        // Arrange - Simulate a large batch
        List<String> manyEntries = List.of(
            "entry-1", "entry-2", "entry-3", "entry-4", "entry-5",
            "entry-6", "entry-7", "entry-8", "entry-9", "entry-10"
        );

        // Act
        ScopeJob job = new ScopeJob(
            "conv-123",
            "cursor-1",
            "cursor-10",
            manyEntries,
            null,
            Instant.now(),
            Instant.now(),
            "max_batch_entries"
        );

        // Assert
        assertThat(job.entryIds()).hasSize(10);
        assertThat(job.entryIds()).containsExactlyElementsOf(manyEntries);
    }
}
