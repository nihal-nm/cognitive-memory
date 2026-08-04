package io.github.rigazilla.memory.cognition.event;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for DirtyWindow.
 * Tests debounce window creation, event accumulation, and promotion logic.
 */
class DirtyWindowTest {

    @Test
    void testCreation_fromFirstEvent() {
        // Verifies window initialization from first event with correct cursor, entry, timing, and metadata
        Instant now = Instant.now();
        Duration debounceDelay = Duration.ofSeconds(5);

        DirtyWindow window = new DirtyWindow(
            "conv-123", "cursor-1", "entry-1", now, debounceDelay, null
        );

        assertThat(window.getConversationId()).isEqualTo("conv-123");
        assertThat(window.getFirstEventCursor()).isEqualTo("cursor-1");
        assertThat(window.getLatestEventCursor()).isEqualTo("cursor-1");
        assertThat(window.getEntryIds()).containsExactly("entry-1");
        assertThat(window.getEntryCount()).isEqualTo(1);
        assertThat(window.getPreviousEntryId()).isNull();
        assertThat(window.getFirstObservedAt()).isEqualTo(now);
        assertThat(window.getLatestObservedAt()).isEqualTo(now);
        assertThat(window.getDueAt()).isEqualTo(now.plus(debounceDelay));
        assertThat(window.getEventCount()).isEqualTo(1);
    }

    @Test
    void testCreation_withPreviousEntryId() {
        // Validates window creation with previous entry ID for linking consecutive windows in conversation history
        Instant now = Instant.now();
        Duration debounceDelay = Duration.ofSeconds(5);

        DirtyWindow window = new DirtyWindow(
            "conv-123", "cursor-1", "entry-1", now, debounceDelay, "prev-entry"
        );

        assertThat(window.getPreviousEntryId()).isEqualTo("prev-entry");
    }

    @Test
    void testCreation_withNullEntryId() {
        // Tests window handles null entry ID gracefully, maintaining empty entry list for metadata-only events
        Instant now = Instant.now();
        Duration debounceDelay = Duration.ofSeconds(5);

        DirtyWindow window = new DirtyWindow(
            "conv-123", "cursor-1", null, now, debounceDelay, null
        );

        assertThat(window.getEntryIds()).isEmpty();
        assertThat(window.getEntryCount()).isZero();
        assertThat(window.getEventCount()).isEqualTo(1);
    }

    @Test
    void testCreation_fromCheckpoint() {
        // Verifies window restoration from checkpoint data preserves all state including entry order and timing
        Instant firstObserved = Instant.parse("2026-01-01T10:00:00Z");
        Instant latestObserved = Instant.parse("2026-01-01T10:00:05Z");
        Instant dueAt = Instant.parse("2026-01-01T10:00:10Z");
        List<String> entryIds = List.of("entry-1", "entry-2", "entry-3");

        DirtyWindow window = new DirtyWindow(
            "conv-123", "cursor-1", "cursor-3", entryIds, "prev-entry",
            firstObserved, latestObserved, dueAt, 5
        );

        assertThat(window.getConversationId()).isEqualTo("conv-123");
        assertThat(window.getFirstEventCursor()).isEqualTo("cursor-1");
        assertThat(window.getLatestEventCursor()).isEqualTo("cursor-3");
        assertThat(window.getEntryIds()).containsExactly("entry-1", "entry-2", "entry-3");
        assertThat(window.getEntryCount()).isEqualTo(3);
        assertThat(window.getPreviousEntryId()).isEqualTo("prev-entry");
        assertThat(window.getFirstObservedAt()).isEqualTo(firstObserved);
        assertThat(window.getLatestObservedAt()).isEqualTo(latestObserved);
        assertThat(window.getDueAt()).isEqualTo(dueAt);
        assertThat(window.getEventCount()).isEqualTo(5);
    }

    @Test
    void testExtend_updatesLatestCursorAndEntry() {
        // Confirms extend operation updates latest cursor, adds entry, increments counters while preserving first event data
        Instant now = Instant.now();
        DirtyWindow window = new DirtyWindow(
            "conv-123", "cursor-1", "entry-1", now, Duration.ofSeconds(5), null
        );

        Instant later = now.plusSeconds(2);
        window.extend("cursor-2", "entry-2", later);

        assertThat(window.getFirstEventCursor()).isEqualTo("cursor-1");
        assertThat(window.getLatestEventCursor()).isEqualTo("cursor-2");
        assertThat(window.getEntryIds()).containsExactly("entry-1", "entry-2");
        assertThat(window.getEntryCount()).isEqualTo(2);
        assertThat(window.getFirstObservedAt()).isEqualTo(now);
        assertThat(window.getLatestObservedAt()).isEqualTo(later);
        assertThat(window.getEventCount()).isEqualTo(2);
    }

    @Test
    void testExtend_doesNotUpdateDueAt() {
        // Validates extend preserves original debounce deadline, preventing infinite window extension from continuous events
        Instant now = Instant.now();
        Duration debounceDelay = Duration.ofSeconds(5);
        DirtyWindow window = new DirtyWindow(
            "conv-123", "cursor-1", "entry-1", now, debounceDelay, null
        );
        Instant originalDueAt = window.getDueAt();

        Instant later = now.plusSeconds(2);
        window.extend("cursor-2", "entry-2", later);

        assertThat(window.getDueAt()).isEqualTo(originalDueAt);
    }

    @Test
    void testExtend_withNullEntryId() {
        // Tests extend handles null entry ID correctly, incrementing event count without adding to entry list
        Instant now = Instant.now();
        DirtyWindow window = new DirtyWindow(
            "conv-123", "cursor-1", "entry-1", now, Duration.ofSeconds(5), null
        );

        window.extend("cursor-2", null, now.plusSeconds(1));

        assertThat(window.getEntryIds()).containsExactly("entry-1");
        assertThat(window.getEntryCount()).isEqualTo(1);
        assertThat(window.getEventCount()).isEqualTo(2);
    }

    @Test
    void testExtend_multipleEvents() {
        // Verifies window correctly accumulates multiple events maintaining entry order and updating all counters
        Instant now = Instant.now();
        DirtyWindow window = new DirtyWindow(
            "conv-123", "cursor-1", "entry-1", now, Duration.ofSeconds(5), null
        );

        window.extend("cursor-2", "entry-2", now.plusSeconds(1));
        window.extend("cursor-3", "entry-3", now.plusSeconds(2));
        window.extend("cursor-4", "entry-4", now.plusSeconds(3));

        assertThat(window.getLatestEventCursor()).isEqualTo("cursor-4");
        assertThat(window.getEntryIds()).containsExactly("entry-1", "entry-2", "entry-3", "entry-4");
        assertThat(window.getEntryCount()).isEqualTo(4);
        assertThat(window.getEventCount()).isEqualTo(4);
    }

    @Test
    void testExtend_deduplicatesEntryIds() {
        // Confirms window deduplicates entry IDs using LinkedHashSet, preserving first occurrence order for idempotency
        Instant now = Instant.now();
        DirtyWindow window = new DirtyWindow(
            "conv-123", "cursor-1", "entry-1", now, Duration.ofSeconds(5), null
        );

        window.extend("cursor-2", "entry-2", now.plusSeconds(1));
        window.extend("cursor-3", "entry-1", now.plusSeconds(2));  // Duplicate
        window.extend("cursor-4", "entry-3", now.plusSeconds(3));

        assertThat(window.getEntryIds()).containsExactly("entry-1", "entry-2", "entry-3");
        assertThat(window.getEntryCount()).isEqualTo(3);
        assertThat(window.getEventCount()).isEqualTo(4);
    }

    @Test
    void testIsDue_beforeDeadline() {
        // Validates isDue returns false when current time is before debounce deadline
        Instant now = Instant.now();
        Duration debounceDelay = Duration.ofSeconds(5);
        DirtyWindow window = new DirtyWindow(
            "conv-123", "cursor-1", "entry-1", now, debounceDelay, null
        );

        Instant beforeDue = now.plusSeconds(3);
        assertThat(window.isDue(beforeDue)).isFalse();
    }

    @Test
    void testIsDue_atDeadline() {
        // Confirms isDue returns true when current time exactly matches debounce deadline
        Instant now = Instant.now();
        Duration debounceDelay = Duration.ofSeconds(5);
        DirtyWindow window = new DirtyWindow(
            "conv-123", "cursor-1", "entry-1", now, debounceDelay, null
        );

        Instant atDue = window.getDueAt();
        assertThat(window.isDue(atDue)).isTrue();
    }

    @Test
    void testIsDue_afterDeadline() {
        // Validates isDue returns true when current time exceeds debounce deadline
        Instant now = Instant.now();
        Duration debounceDelay = Duration.ofSeconds(5);
        DirtyWindow window = new DirtyWindow(
            "conv-123", "cursor-1", "entry-1", now, debounceDelay, null
        );

        Instant afterDue = now.plusSeconds(10);
        assertThat(window.isDue(afterDue)).isTrue();
    }

    @Test
    void testIsMaxAgeReached_belowThreshold() {
        // Tests isMaxAgeReached returns false when window age is below maximum batch age threshold
        Instant now = Instant.now();
        DirtyWindow window = new DirtyWindow(
            "conv-123", "cursor-1", "entry-1", now, Duration.ofSeconds(5), null
        );

        Duration maxBatchAge = Duration.ofSeconds(30);
        Instant checkTime = now.plusSeconds(20);
        assertThat(window.isMaxAgeReached(checkTime, maxBatchAge)).isFalse();
    }

    @Test
    void testIsMaxAgeReached_atThreshold() {
        // Confirms isMaxAgeReached returns true when window age exactly equals maximum batch age
        Instant now = Instant.now();
        DirtyWindow window = new DirtyWindow(
            "conv-123", "cursor-1", "entry-1", now, Duration.ofSeconds(5), null
        );

        Duration maxBatchAge = Duration.ofSeconds(30);
        Instant checkTime = now.plusSeconds(30);
        assertThat(window.isMaxAgeReached(checkTime, maxBatchAge)).isTrue();
    }

    @Test
    void testIsMaxAgeReached_aboveThreshold() {
        // Validates isMaxAgeReached returns true when window age exceeds maximum batch age threshold
        Instant now = Instant.now();
        DirtyWindow window = new DirtyWindow(
            "conv-123", "cursor-1", "entry-1", now, Duration.ofSeconds(5), null
        );

        Duration maxBatchAge = Duration.ofSeconds(30);
        Instant checkTime = now.plusSeconds(40);
        assertThat(window.isMaxAgeReached(checkTime, maxBatchAge)).isTrue();
    }

    @Test
    void testIsMaxEntriesReached_belowThreshold() {
        // Tests isMaxEntriesReached returns false when entry count is below maximum batch entries limit
        Instant now = Instant.now();
        DirtyWindow window = new DirtyWindow(
            "conv-123", "cursor-1", "entry-1", now, Duration.ofSeconds(5), null
        );
        window.extend("cursor-2", "entry-2", now.plusSeconds(1));

        assertThat(window.isMaxEntriesReached(10)).isFalse();
    }

    @Test
    void testIsMaxEntriesReached_atThreshold() {
        // Confirms isMaxEntriesReached returns true when entry count exactly equals maximum batch entries
        Instant now = Instant.now();
        DirtyWindow window = new DirtyWindow(
            "conv-123", "cursor-1", "entry-1", now, Duration.ofSeconds(5), null
        );
        window.extend("cursor-2", "entry-2", now.plusSeconds(1));
        window.extend("cursor-3", "entry-3", now.plusSeconds(2));

        assertThat(window.isMaxEntriesReached(3)).isTrue();
    }

    @Test
    void testIsMaxEntriesReached_aboveThreshold() {
        // Validates isMaxEntriesReached returns true when entry count exceeds maximum batch entries limit
        Instant now = Instant.now();
        DirtyWindow window = new DirtyWindow(
            "conv-123", "cursor-1", "entry-1", now, Duration.ofSeconds(5), null
        );
        window.extend("cursor-2", "entry-2", now.plusSeconds(1));
        window.extend("cursor-3", "entry-3", now.plusSeconds(2));
        window.extend("cursor-4", "entry-4", now.plusSeconds(3));

        assertThat(window.isMaxEntriesReached(3)).isTrue();
    }

    @Test
    void testGetAge_calculatesCorrectDuration() {
        // Verifies getAge correctly calculates duration between first observation and current time
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        DirtyWindow window = new DirtyWindow(
            "conv-123", "cursor-1", "entry-1", now, Duration.ofSeconds(5), null
        );

        Instant checkTime = Instant.parse("2026-01-01T10:00:15Z");
        Duration age = window.getAge(checkTime);

        assertThat(age).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void testGetEntryIds_returnsImmutableCopy() {
        // Tests getEntryIds returns defensive copy preventing external modification of internal entry collection
        Instant now = Instant.now();
        DirtyWindow window = new DirtyWindow(
            "conv-123", "cursor-1", "entry-1", now, Duration.ofSeconds(5), null
        );

        List<String> entryIds = window.getEntryIds();
        assertThat(entryIds).containsExactly("entry-1");

        // Extend window
        window.extend("cursor-2", "entry-2", now.plusSeconds(1));

        // Original list should not be affected
        assertThat(entryIds).containsExactly("entry-1");
        assertThat(window.getEntryIds()).containsExactly("entry-1", "entry-2");
    }

    @Test
    void testToString_containsKeyFields() {
        // Validates toString output includes conversation ID, event count, entry count, and timing information
        Instant now = Instant.now();
        DirtyWindow window = new DirtyWindow(
            "conv-123", "cursor-1", "entry-1", now, Duration.ofSeconds(5), null
        );
        window.extend("cursor-2", "entry-2", now.plusSeconds(1));

        String result = window.toString();

        assertThat(result).contains("DirtyWindow");
        assertThat(result).contains("conv=conv-123");
        assertThat(result).contains("events=2");
        assertThat(result).contains("entries=2");
    }
}
