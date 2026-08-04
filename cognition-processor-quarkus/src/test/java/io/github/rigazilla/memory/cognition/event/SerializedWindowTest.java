package io.github.rigazilla.memory.cognition.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for SerializedWindow record.
 * Tests checkpoint serialization format, field accessors, and equality semantics.
 */
class SerializedWindowTest {

    @Test
    void testRecordCreation_withAllFields() {
        // Verifies record creation with complete checkpoint data including timing and provenance information
        String conversationId = "conv-123";
        String firstCursor = "cursor-1";
        String latestCursor = "cursor-5";
        List<String> entryIds = List.of("entry-1", "entry-2", "entry-3");
        String previousEntryId = "entry-0";
        Instant firstObserved = Instant.parse("2026-01-01T10:00:00Z");
        Instant latestObserved = Instant.parse("2026-01-01T10:00:10Z");
        Instant dueAt = Instant.parse("2026-01-01T10:00:15Z");
        int eventCount = 5;

        SerializedWindow window = new SerializedWindow(
            conversationId,
            firstCursor,
            latestCursor,
            entryIds,
            previousEntryId,
            firstObserved,
            latestObserved,
            dueAt,
            eventCount
        );

        assertThat(window.conversationId()).isEqualTo(conversationId);
        assertThat(window.firstEventCursor()).isEqualTo(firstCursor);
        assertThat(window.latestEventCursor()).isEqualTo(latestCursor);
        assertThat(window.entryIds()).isEqualTo(entryIds);
        assertThat(window.previousEntryId()).isEqualTo(previousEntryId);
        assertThat(window.firstObservedAt()).isEqualTo(firstObserved);
        assertThat(window.latestObservedAt()).isEqualTo(latestObserved);
        assertThat(window.dueAt()).isEqualTo(dueAt);
        assertThat(window.eventCount()).isEqualTo(eventCount);
    }

    @Test
    void testRecordCreation_withNullPreviousEntryId() {
        // Tests checkpoint serialization handles null previous entry for first window in conversation
        SerializedWindow window = new SerializedWindow(
            "conv-123",
            "cursor-1",
            "cursor-2",
            List.of("entry-1"),
            null,  // First window has no previous entry
            Instant.now(),
            Instant.now(),
            Instant.now(),
            1
        );

        assertThat(window.previousEntryId()).isNull();
        assertThat(window.conversationId()).isEqualTo("conv-123");
    }

    @Test
    void testRecordCreation_withEmptyEntryList() {
        // Validates serialization supports empty entry list for metadata-only event windows
        SerializedWindow window = new SerializedWindow(
            "conv-123",
            "cursor-1",
            "cursor-2",
            List.of(),  // No entries
            null,
            Instant.now(),
            Instant.now(),
            Instant.now(),
            2
        );

        assertThat(window.entryIds()).isEmpty();
        assertThat(window.eventCount()).isEqualTo(2);
    }

    @Test
    void testRecordCreation_withMultipleEntries() {
        // Confirms serialization preserves entry order and handles large batches correctly
        List<String> manyEntries = List.of(
            "entry-1", "entry-2", "entry-3", "entry-4", "entry-5",
            "entry-6", "entry-7", "entry-8", "entry-9", "entry-10"
        );

        SerializedWindow window = new SerializedWindow(
            "conv-123",
            "cursor-1",
            "cursor-10",
            manyEntries,
            null,
            Instant.now(),
            Instant.now(),
            Instant.now(),
            10
        );

        assertThat(window.entryIds()).hasSize(10);
        assertThat(window.entryIds()).containsExactlyElementsOf(manyEntries);
    }

    @Test
    void testEquality_sameValues() {
        // Tests record equality semantics with identical field values for checkpoint deduplication
        Instant firstObserved = Instant.parse("2026-01-01T10:00:00Z");
        Instant latestObserved = Instant.parse("2026-01-01T10:00:10Z");
        Instant dueAt = Instant.parse("2026-01-01T10:00:15Z");
        List<String> entryIds = List.of("entry-1", "entry-2");

        SerializedWindow window1 = new SerializedWindow(
            "conv-123",
            "cursor-1",
            "cursor-2",
            entryIds,
            null,
            firstObserved,
            latestObserved,
            dueAt,
            2
        );

        SerializedWindow window2 = new SerializedWindow(
            "conv-123",
            "cursor-1",
            "cursor-2",
            entryIds,
            null,
            firstObserved,
            latestObserved,
            dueAt,
            2
        );

        assertThat(window1).isEqualTo(window2);
        assertThat(window1.hashCode()).isEqualTo(window2.hashCode());
    }

    @Test
    void testEquality_differentConversationIds() {
        // Validates records with different conversation IDs are not equal for checkpoint isolation
        Instant now = Instant.now();
        SerializedWindow window1 = new SerializedWindow(
            "conv-123",
            "cursor-1",
            "cursor-2",
            List.of("entry-1"),
            null,
            now,
            now,
            now,
            1
        );

        SerializedWindow window2 = new SerializedWindow(
            "conv-456",  // Different conversation
            "cursor-1",
            "cursor-2",
            List.of("entry-1"),
            null,
            now,
            now,
            now,
            1
        );

        assertThat(window1).isNotEqualTo(window2);
    }

    @Test
    void testEquality_differentCursors() {
        // Tests records with different event cursors are not equal for checkpoint versioning
        Instant now = Instant.now();
        SerializedWindow window1 = new SerializedWindow(
            "conv-123",
            "cursor-1",
            "cursor-2",
            List.of("entry-1"),
            null,
            now,
            now,
            now,
            1
        );

        SerializedWindow window2 = new SerializedWindow(
            "conv-123",
            "cursor-1",
            "cursor-3",  // Different latest cursor
            List.of("entry-1"),
            null,
            now,
            now,
            now,
            1
        );

        assertThat(window1).isNotEqualTo(window2);
    }

    @Test
    void testEquality_differentEntryIds() {
        // Confirms records with different entry lists are not equal for checkpoint integrity
        Instant now = Instant.now();
        SerializedWindow window1 = new SerializedWindow(
            "conv-123",
            "cursor-1",
            "cursor-2",
            List.of("entry-1", "entry-2"),
            null,
            now,
            now,
            now,
            2
        );

        SerializedWindow window2 = new SerializedWindow(
            "conv-123",
            "cursor-1",
            "cursor-2",
            List.of("entry-1", "entry-3"),  // Different entries
            null,
            now,
            now,
            now,
            2
        );

        assertThat(window1).isNotEqualTo(window2);
    }

    @Test
    void testEquality_differentTimestamps() {
        // Validates records with different timestamps are not equal for temporal checkpoint accuracy
        Instant time1 = Instant.parse("2026-01-01T10:00:00Z");
        Instant time2 = Instant.parse("2026-01-01T10:00:01Z");

        SerializedWindow window1 = new SerializedWindow(
            "conv-123",
            "cursor-1",
            "cursor-2",
            List.of("entry-1"),
            null,
            time1,
            time1,
            time1,
            1
        );

        SerializedWindow window2 = new SerializedWindow(
            "conv-123",
            "cursor-1",
            "cursor-2",
            List.of("entry-1"),
            null,
            time2,  // Different timestamp
            time2,
            time2,
            1
        );

        assertThat(window1).isNotEqualTo(window2);
    }

    @Test
    void testEquality_differentEventCounts() {
        // Tests records with different event counts are not equal for checkpoint consistency
        Instant now = Instant.now();
        SerializedWindow window1 = new SerializedWindow(
            "conv-123",
            "cursor-1",
            "cursor-2",
            List.of("entry-1"),
            null,
            now,
            now,
            now,
            1
        );

        SerializedWindow window2 = new SerializedWindow(
            "conv-123",
            "cursor-1",
            "cursor-2",
            List.of("entry-1"),
            null,
            now,
            now,
            now,
            2  // Different event count
        );

        assertThat(window1).isNotEqualTo(window2);
    }

    @Test
    void testToString_containsKeyFields() {
        // Confirms toString includes conversation ID and cursors for checkpoint debugging and logging
        SerializedWindow window = new SerializedWindow(
            "conv-123",
            "cursor-1",
            "cursor-5",
            List.of("entry-1", "entry-2"),
            null,
            Instant.now(),
            Instant.now(),
            Instant.now(),
            5
        );

        String result = window.toString();

        assertThat(result).contains("conv-123");
        assertThat(result).contains("cursor-1");
        assertThat(result).contains("cursor-5");
    }

    @Test
    void testEntryIds_preservesOrder() {
        // Validates entry list maintains insertion order for correct checkpoint restoration sequence
        List<String> orderedEntries = List.of("entry-3", "entry-1", "entry-2");

        SerializedWindow window = new SerializedWindow(
            "conv-123",
            "cursor-1",
            "cursor-3",
            orderedEntries,
            null,
            Instant.now(),
            Instant.now(),
            Instant.now(),
            3
        );

        assertThat(window.entryIds()).containsExactly("entry-3", "entry-1", "entry-2");
    }

    @Test
    void testTimestamps_chronologicalOrder() {
        // Tests checkpoint captures correct temporal sequence with first observed before latest observed
        Instant firstObserved = Instant.parse("2026-01-01T10:00:00Z");
        Instant latestObserved = Instant.parse("2026-01-01T10:00:10Z");
        Instant dueAt = Instant.parse("2026-01-01T10:00:15Z");

        SerializedWindow window = new SerializedWindow(
            "conv-123",
            "cursor-1",
            "cursor-2",
            List.of("entry-1"),
            null,
            firstObserved,
            latestObserved,
            dueAt,
            1
        );

        assertThat(window.firstObservedAt()).isBefore(window.latestObservedAt());
        assertThat(window.latestObservedAt()).isBefore(window.dueAt());
    }

    @Test
    void testEventCount_matchesEntryCount() {
        // Confirms event count can differ from entry count for windows with metadata-only events
        List<String> entries = List.of("entry-1", "entry-2");

        SerializedWindow window = new SerializedWindow(
            "conv-123",
            "cursor-1",
            "cursor-5",
            entries,
            null,
            Instant.now(),
            Instant.now(),
            Instant.now(),
            5  // More events than entries (some events had no entry)
        );

        assertThat(window.entryIds()).hasSize(2);
        assertThat(window.eventCount()).isEqualTo(5);
    }
}
