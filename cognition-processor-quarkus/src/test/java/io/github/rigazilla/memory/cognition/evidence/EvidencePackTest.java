package io.github.rigazilla.memory.cognition.evidence;

import com.google.protobuf.ByteString;
import io.github.chirino.memory.grpc.v1.Entry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for EvidencePack.
 * Tests evidence pack creation, formatting, and metadata methods.
 */
class EvidencePackTest {

    @Test
    void testCreation_withEmptyList() {
        // Arrange
        List<Entry> emptyEntries = new ArrayList<>();

        // Act
        EvidencePack pack = new EvidencePack(emptyEntries);

        // Assert
        assertThat(pack.getTranscriptEntries()).isEmpty();
        assertThat(pack.size()).isZero();
    }

    @Test
    void testCreation_withMultipleEntries() {
        // Arrange
        List<Entry> entries = List.of(
            Entry.newBuilder().setId(ByteString.copyFromUtf8("entry-1")).build(),
            Entry.newBuilder().setId(ByteString.copyFromUtf8("entry-2")).build(),
            Entry.newBuilder().setId(ByteString.copyFromUtf8("entry-3")).build()
        );

        // Act
        EvidencePack pack = new EvidencePack(entries);

        // Assert
        assertThat(pack.getTranscriptEntries()).hasSize(3);
        assertThat(pack.size()).isEqualTo(3);
    }

    @Test
    void testSize_returnsCorrectCount() {
        // Arrange
        List<Entry> entries = List.of(
            Entry.newBuilder().setId(ByteString.copyFromUtf8("entry-1")).build(),
            Entry.newBuilder().setId(ByteString.copyFromUtf8("entry-2")).build()
        );
        EvidencePack pack = new EvidencePack(entries);

        // Act
        int size = pack.size();

        // Assert
        assertThat(size).isEqualTo(2);
    }

    @Test
    void testComputeHash_returnsNull() {
        // Arrange - Phase 3A: Not yet implemented
        List<Entry> entries = List.of(Entry.newBuilder().setId(ByteString.copyFromUtf8("entry-1")).build());
        EvidencePack pack = new EvidencePack(entries);

        // Act
        String hash = pack.computeHash();

        // Assert - Should return null until implemented
        assertThat(hash).isNull();
    }

    @Test
    void testGetEvidenceBaseId_returnsNull() {
        // Arrange - Phase 3A: No compaction yet
        List<Entry> entries = List.of(Entry.newBuilder().setId(ByteString.copyFromUtf8("entry-1")).build());
        EvidencePack pack = new EvidencePack(entries);

        // Act
        String baseId = pack.getEvidenceBaseId();

        // Assert - Should return null until compaction is implemented
        assertThat(baseId).isNull();
    }

    @Test
    void testGetEvidenceBaseHash_returnsNull() {
        // Arrange - Phase 3A: No compaction yet
        List<Entry> entries = List.of(Entry.newBuilder().setId(ByteString.copyFromUtf8("entry-1")).build());
        EvidencePack pack = new EvidencePack(entries);

        // Act
        String baseHash = pack.getEvidenceBaseHash();

        // Assert - Should return null until compaction is implemented
        assertThat(baseHash).isNull();
    }

    @Test
    void testFormatAsText_withEmptyEntries() {
        // Arrange
        EvidencePack pack = new EvidencePack(new ArrayList<>());

        // Act
        String formatted = pack.formatAsText();

        // Assert
        assertThat(formatted).contains("=== CONVERSATION TRANSCRIPT ===");
        assertThat(formatted).doesNotContain("[user]");
        assertThat(formatted).doesNotContain("[assistant]");
    }

    @Test
    void testFormatAsText_withNonHistoryEntries() {
        // Arrange - Entries without history content type
        List<Entry> entries = List.of(
            Entry.newBuilder()
                .setId(ByteString.copyFromUtf8("entry-1"))
                .setContentType("text/plain")
                .build()
        );
        EvidencePack pack = new EvidencePack(entries);

        // Act
        String formatted = pack.formatAsText();

        // Assert - Should only show header, no conversation content
        assertThat(formatted).contains("=== CONVERSATION TRANSCRIPT ===");
        assertThat(formatted.split("\n")).hasSizeLessThan(5);
    }

    @Test
    void testFormatAsText_withHistoryEntries() {
        // Arrange - Create entry with history content type and proper structure
        var textValue = com.google.protobuf.Value.newBuilder()
            .setStringValue("Hello, how can I help?")
            .build();
        
        var roleValue = com.google.protobuf.Value.newBuilder()
            .setStringValue("assistant")
            .build();

        var struct = com.google.protobuf.Struct.newBuilder()
            .putFields("text", textValue)
            .putFields("role", roleValue)
            .build();

        var structValue = com.google.protobuf.Value.newBuilder()
            .setStructValue(struct)
            .build();

        Entry entry = Entry.newBuilder()
            .setId(ByteString.copyFromUtf8("entry-1"))
            .setContentType("history")
            .setCreatedAt("2026-01-01T10:00:00Z")
            .addContent(structValue)
            .build();

        EvidencePack pack = new EvidencePack(List.of(entry));

        // Act
        String formatted = pack.formatAsText();

        // Assert
        assertThat(formatted).contains("=== CONVERSATION TRANSCRIPT ===");
        assertThat(formatted).contains("[assistant]");
        assertThat(formatted).contains("Hello, how can I help?");
        assertThat(formatted).contains("2026-01-01T10:00:00Z");
    }

    @Test
    void testFormatAsText_withMultipleHistoryEntries() {
        // Arrange - Create multiple conversation entries
        var userStruct = com.google.protobuf.Struct.newBuilder()
            .putFields("text", com.google.protobuf.Value.newBuilder()
                .setStringValue("What is the weather?").build())
            .putFields("role", com.google.protobuf.Value.newBuilder()
                .setStringValue("user").build())
            .build();

        var assistantStruct = com.google.protobuf.Struct.newBuilder()
            .putFields("text", com.google.protobuf.Value.newBuilder()
                .setStringValue("It's sunny today.").build())
            .putFields("role", com.google.protobuf.Value.newBuilder()
                .setStringValue("assistant").build())
            .build();

        Entry userEntry = Entry.newBuilder()
            .setId(ByteString.copyFromUtf8("entry-1"))
            .setContentType("history")
            .setCreatedAt("2026-01-01T10:00:00Z")
            .addContent(com.google.protobuf.Value.newBuilder().setStructValue(userStruct).build())
            .build();

        Entry assistantEntry = Entry.newBuilder()
            .setId(ByteString.copyFromUtf8("entry-2"))
            .setContentType("history")
            .setCreatedAt("2026-01-01T10:00:05Z")
            .addContent(com.google.protobuf.Value.newBuilder().setStructValue(assistantStruct).build())
            .build();

        EvidencePack pack = new EvidencePack(List.of(userEntry, assistantEntry));

        // Act
        String formatted = pack.formatAsText();

        // Assert
        assertThat(formatted).contains("[user] What is the weather?");
        assertThat(formatted).contains("[assistant] It's sunny today.");
    }

    @Test
    void testFormatAsText_withMissingTimestamp() {
        // Arrange - Entry without createdAt timestamp
        var struct = com.google.protobuf.Struct.newBuilder()
            .putFields("text", com.google.protobuf.Value.newBuilder()
                .setStringValue("Test message").build())
            .putFields("role", com.google.protobuf.Value.newBuilder()
                .setStringValue("user").build())
            .build();

        Entry entry = Entry.newBuilder()
            .setId(ByteString.copyFromUtf8("entry-1"))
            .setContentType("history")
            .addContent(com.google.protobuf.Value.newBuilder().setStructValue(struct).build())
            .build();

        EvidencePack pack = new EvidencePack(List.of(entry));

        // Act
        String formatted = pack.formatAsText();

        // Assert - Should format without timestamp
        assertThat(formatted).contains("[user] Test message");
        assertThat(formatted).doesNotContain("[2026");
    }

    @Test
    void testToString_containsSize() {
        // Arrange
        List<Entry> entries = List.of(
            Entry.newBuilder().setId(ByteString.copyFromUtf8("entry-1")).build(),
            Entry.newBuilder().setId(ByteString.copyFromUtf8("entry-2")).build(),
            Entry.newBuilder().setId(ByteString.copyFromUtf8("entry-3")).build()
        );
        EvidencePack pack = new EvidencePack(entries);

        // Act
        String result = pack.toString();

        // Assert
        assertThat(result).contains("EvidencePack");
        assertThat(result).contains("entries=3");
    }

    @Test
    void testGetTranscriptEntries_returnsOriginalList() {
        // Arrange
        List<Entry> entries = List.of(
            Entry.newBuilder().setId(ByteString.copyFromUtf8("entry-1")).build(),
            Entry.newBuilder().setId(ByteString.copyFromUtf8("entry-2")).build()
        );
        EvidencePack pack = new EvidencePack(entries);

        // Act
        List<Entry> retrieved = pack.getTranscriptEntries();

        // Assert
        assertThat(retrieved).isEqualTo(entries);
        assertThat(retrieved).hasSize(2);
    }

    // -------------------------------------------------------------------------
    // earliestCreatedAt — temporal metadata sourcing
    // -------------------------------------------------------------------------

    @Test
    void earliestCreatedAtReturnsMinimumTimestamp() {
        // Arrange
        Entry e1 = Entry.newBuilder().setCreatedAt("2025-01-15T10:00:00Z").build();
        Entry e2 = Entry.newBuilder().setCreatedAt("2025-01-15T09:00:00Z").build();
        Entry e3 = Entry.newBuilder().setCreatedAt("2025-01-15T11:00:00Z").build();

        // Act
        Optional<String> result = new EvidencePack(List.of(e1, e2, e3)).earliestCreatedAt();

        // Assert
        assertThat(result).isPresent().hasValue("2025-01-15T09:00:00Z");
    }

    @Test
    void earliestCreatedAtIsEmptyWhenNoEntries() {
        // Arrange / Act / Assert
        assertThat(new EvidencePack(List.of()).earliestCreatedAt()).isEmpty();
    }

    @Test
    void earliestCreatedAtSkipsBlankTimestamps() {
        // Arrange
        Entry noTimestamp = Entry.newBuilder().build();
        Entry withTimestamp = Entry.newBuilder().setCreatedAt("2025-03-01T08:00:00Z").build();

        // Act
        Optional<String> result = new EvidencePack(List.of(noTimestamp, withTimestamp)).earliestCreatedAt();

        // Assert
        assertThat(result).isPresent().hasValue("2025-03-01T08:00:00Z");
    }

    @Test
    void earliestCreatedAtIsEmptyWhenAllTimestampsBlank() {
        // Arrange
        Entry e1 = Entry.newBuilder().build();
        Entry e2 = Entry.newBuilder().build();

        // Act / Assert
        assertThat(new EvidencePack(List.of(e1, e2)).earliestCreatedAt()).isEmpty();
    }

    @Test
    void earliestCreatedAtSkipsWhitespaceOnlyTimestamp() {
        // Arrange — EP-N1: whitespace-only createdAt must be excluded by the isBlank() filter
        Entry blank = Entry.newBuilder().setCreatedAt("   ").build();
        Entry valid = Entry.newBuilder().setCreatedAt("2025-05-20T07:00:00Z").build();

        // Act
        Optional<String> result = new EvidencePack(List.of(blank, valid)).earliestCreatedAt();

        // Assert
        assertThat(result)
            .as("whitespace-only createdAt must not be treated as a valid timestamp")
            .isPresent()
            .hasValue("2025-05-20T07:00:00Z");
    }

    @Test
    void earliestCreatedAtWithAllIdenticalTimestampsReturnsTheTimestamp() {
        // Arrange — EP-N2: when all entries share the same timestamp min() must still return it
        String ts = "2025-04-10T12:00:00Z";
        Entry e1 = Entry.newBuilder().setCreatedAt(ts).build();
        Entry e2 = Entry.newBuilder().setCreatedAt(ts).build();
        Entry e3 = Entry.newBuilder().setCreatedAt(ts).build();

        // Act
        Optional<String> result = new EvidencePack(List.of(e1, e2, e3)).earliestCreatedAt();

        // Assert
        assertThat(result)
            .as("must return a value even when all timestamps are equal")
            .isPresent()
            .hasValue(ts);
    }
}
