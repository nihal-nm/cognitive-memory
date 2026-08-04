package io.github.rigazilla.memory.cognition.extraction;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for DurableExtractionResponse record.
 * Tests candidate filtering, validation, and aggregation logic.
 */
class DurableExtractionResponseTest {

    @Test
    void testCreation_withAllTypes() {
        // Arrange
        List<MemoryCandidate> facts = List.of(
            new MemoryCandidate("fact", "User is 30", 0.9, List.of("entry-1"))
        );
        List<MemoryCandidate> preferences = List.of(
            new MemoryCandidate("preference", "Likes coffee", 0.85, List.of("entry-2"))
        );
        List<MemoryCandidate> procedures = List.of(
            new MemoryCandidate("procedure", "Check logs first", 0.88, List.of("entry-3"))
        );
        List<MemoryCandidate> solutions = List.of(
            new MemoryCandidate("problem_solution", "Restart service", 0.92, List.of("entry-4"))
        );
        List<MemoryCandidate> decisions = List.of(
            new MemoryCandidate("decision", "Use React", 0.87, List.of("entry-5"))
        );

        // Act
        DurableExtractionResponse response = new DurableExtractionResponse(
            facts, preferences, procedures, solutions, decisions
        );

        // Assert
        assertThat(response.facts()).hasSize(1);
        assertThat(response.preferences()).hasSize(1);
        assertThat(response.procedures()).hasSize(1);
        assertThat(response.problemSolutions()).hasSize(1);
        assertThat(response.decisions()).hasSize(1);
        assertThat(response.getTotalCount()).isEqualTo(5);
    }

    @Test
    void testCreation_withNullLists() {
        // Act - Null lists should be normalized to empty
        DurableExtractionResponse response = new DurableExtractionResponse(
            null, null, null, null, null
        );

        // Assert
        assertThat(response.facts()).isEmpty();
        assertThat(response.preferences()).isEmpty();
        assertThat(response.procedures()).isEmpty();
        assertThat(response.problemSolutions()).isEmpty();
        assertThat(response.decisions()).isEmpty();
        assertThat(response.getTotalCount()).isZero();
    }

    @Test
    void testCreation_withEmptyLists() {
        // Act
        DurableExtractionResponse response = new DurableExtractionResponse(
            List.of(), List.of(), List.of(), List.of(), List.of()
        );

        // Assert
        assertThat(response.facts()).isEmpty();
        assertThat(response.preferences()).isEmpty();
        assertThat(response.procedures()).isEmpty();
        assertThat(response.problemSolutions()).isEmpty();
        assertThat(response.decisions()).isEmpty();
        assertThat(response.getTotalCount()).isZero();
    }

    @Test
    void testGetAllCandidates_withValidCandidates() {
        // Arrange
        List<MemoryCandidate> facts = List.of(
            new MemoryCandidate("fact", "User is 30", 0.9, List.of("entry-1")),
            new MemoryCandidate("fact", "Lives in NYC", 0.85, List.of("entry-2"))
        );
        List<MemoryCandidate> preferences = List.of(
            new MemoryCandidate("preference", "Likes coffee", 0.88, List.of("entry-3"))
        );

        DurableExtractionResponse response = new DurableExtractionResponse(
            facts, preferences, List.of(), List.of(), List.of()
        );

        // Act
        List<MemoryCandidate> allCandidates = response.getAllCandidates();

        // Assert
        assertThat(allCandidates).hasSize(3);
        assertThat(allCandidates).containsExactlyInAnyOrderElementsOf(
            List.of(facts.get(0), facts.get(1), preferences.get(0))
        );
    }

    @Test
    void testGetAllCandidates_filtersInvalidCandidates() {
        // Arrange - Mix of valid and invalid candidates
        List<MemoryCandidate> facts = List.of(
            new MemoryCandidate("fact", "Valid fact", 0.9, List.of("entry-1")),
            new MemoryCandidate("fact", "", 0.8, List.of("entry-2")),  // Invalid: empty content
            new MemoryCandidate("fact", "Another fact", 0.0, List.of("entry-3")),  // Invalid: zero confidence
            new MemoryCandidate("fact", "No citations", 0.85, List.of())  // Invalid: no citations
        );

        DurableExtractionResponse response = new DurableExtractionResponse(
            facts, List.of(), List.of(), List.of(), List.of()
        );

        // Act
        List<MemoryCandidate> validCandidates = response.getAllCandidates();

        // Assert - Only the first candidate is valid
        assertThat(validCandidates).hasSize(1);
        assertThat(validCandidates.get(0).content()).isEqualTo("Valid fact");
    }

    @Test
    void testGetAllCandidates_filtersBlankContent() {
        // Arrange
        List<MemoryCandidate> facts = List.of(
            new MemoryCandidate("fact", "   ", 0.9, List.of("entry-1")),  // Blank
            new MemoryCandidate("fact", "Valid", 0.9, List.of("entry-2"))
        );

        DurableExtractionResponse response = new DurableExtractionResponse(
            facts, List.of(), List.of(), List.of(), List.of()
        );

        // Act
        List<MemoryCandidate> validCandidates = response.getAllCandidates();

        // Assert
        assertThat(validCandidates).hasSize(1);
        assertThat(validCandidates.get(0).content()).isEqualTo("Valid");
    }

    @Test
    void testGetInvalidCandidates_returnsFilteredOnes() {
        // Arrange
        List<MemoryCandidate> facts = List.of(
            new MemoryCandidate("fact", "Valid", 0.9, List.of("entry-1")),
            new MemoryCandidate("fact", "", 0.8, List.of("entry-2")),  // Invalid
            new MemoryCandidate("fact", "Another", 0.0, List.of("entry-3"))  // Invalid
        );

        DurableExtractionResponse response = new DurableExtractionResponse(
            facts, List.of(), List.of(), List.of(), List.of()
        );

        // Act
        List<MemoryCandidate> invalidCandidates = response.getInvalidCandidates();

        // Assert
        assertThat(invalidCandidates).hasSize(2);
    }

    @Test
    void testGetInvalidReason_emptyContent() {
        // Arrange
        DurableExtractionResponse response = new DurableExtractionResponse(
            List.of(), List.of(), List.of(), List.of(), List.of()
        );
        MemoryCandidate candidate = new MemoryCandidate("fact", "", 0.9, List.of("entry-1"));

        // Act
        String reason = response.getInvalidReason(candidate);

        // Assert
        assertThat(reason).isEqualTo("empty or blank content");
    }

    @Test
    void testGetInvalidReason_zeroConfidence() {
        // Arrange
        DurableExtractionResponse response = new DurableExtractionResponse(
            List.of(), List.of(), List.of(), List.of(), List.of()
        );
        MemoryCandidate candidate = new MemoryCandidate("fact", "content", 0.0, List.of("entry-1"));

        // Act
        String reason = response.getInvalidReason(candidate);

        // Assert
        assertThat(reason).contains("zero/negative confidence");
        assertThat(reason).contains("0.00");
    }

    @Test
    void testGetInvalidReason_negativeConfidence() {
        // Arrange
        DurableExtractionResponse response = new DurableExtractionResponse(
            List.of(), List.of(), List.of(), List.of(), List.of()
        );
        MemoryCandidate candidate = new MemoryCandidate("fact", "content", -0.5, List.of("entry-1"));

        // Act
        String reason = response.getInvalidReason(candidate);

        // Assert
        assertThat(reason).contains("zero/negative confidence");
        assertThat(reason).contains("-0.50");
    }

    @Test
    void testGetInvalidReason_noCitations() {
        // Arrange
        DurableExtractionResponse response = new DurableExtractionResponse(
            List.of(), List.of(), List.of(), List.of(), List.of()
        );
        MemoryCandidate candidate = new MemoryCandidate("fact", "content", 0.9, List.of());

        // Act
        String reason = response.getInvalidReason(candidate);

        // Assert
        assertThat(reason).isEqualTo("no citations");
    }

    @Test
    void testGetInvalidReason_nullCitations() {
        // Arrange
        DurableExtractionResponse response = new DurableExtractionResponse(
            List.of(), List.of(), List.of(), List.of(), List.of()
        );
        MemoryCandidate candidate = new MemoryCandidate("fact", "content", 0.9, null);

        // Act
        String reason = response.getInvalidReason(candidate);

        // Assert
        assertThat(reason).isEqualTo("no citations");
    }

    @Test
    void testGetTotalCount_withMixedTypes() {
        // Arrange
        DurableExtractionResponse response = new DurableExtractionResponse(
            List.of(
                new MemoryCandidate("fact", "f1", 0.9, List.of("e1")),
                new MemoryCandidate("fact", "f2", 0.9, List.of("e2"))
            ),
            List.of(
                new MemoryCandidate("preference", "p1", 0.9, List.of("e3"))
            ),
            List.of(),
            List.of(
                new MemoryCandidate("problem_solution", "s1", 0.9, List.of("e4")),
                new MemoryCandidate("problem_solution", "s2", 0.9, List.of("e5")),
                new MemoryCandidate("problem_solution", "s3", 0.9, List.of("e6"))
            ),
            List.of()
        );

        // Act
        int total = response.getTotalCount();

        // Assert
        assertThat(total).isEqualTo(6);
    }

    @Test
    void testToString_containsAllCounts() {
        // Arrange
        DurableExtractionResponse response = new DurableExtractionResponse(
            List.of(new MemoryCandidate("fact", "f1", 0.9, List.of("e1"))),
            List.of(new MemoryCandidate("preference", "p1", 0.9, List.of("e2"))),
            List.of(new MemoryCandidate("procedure", "pr1", 0.9, List.of("e3"))),
            List.of(new MemoryCandidate("problem_solution", "s1", 0.9, List.of("e4"))),
            List.of(new MemoryCandidate("decision", "d1", 0.9, List.of("e5")))
        );

        // Act
        String result = response.toString();

        // Assert
        assertThat(result).contains("DurableExtractionResponse");
        assertThat(result).contains("facts=1");
        assertThat(result).contains("preferences=1");
        assertThat(result).contains("procedures=1");
        assertThat(result).contains("problemSolutions=1");
        assertThat(result).contains("decisions=1");
        assertThat(result).contains("total=5");
    }

    @Test
    void testGetAllCandidates_preservesOrder() {
        // Arrange - Order should be: facts, preferences, procedures, solutions, decisions
        DurableExtractionResponse response = new DurableExtractionResponse(
            List.of(new MemoryCandidate("fact", "f1", 0.9, List.of("e1"))),
            List.of(new MemoryCandidate("preference", "p1", 0.9, List.of("e2"))),
            List.of(new MemoryCandidate("procedure", "pr1", 0.9, List.of("e3"))),
            List.of(new MemoryCandidate("problem_solution", "s1", 0.9, List.of("e4"))),
            List.of(new MemoryCandidate("decision", "d1", 0.9, List.of("e5")))
        );

        // Act
        List<MemoryCandidate> allCandidates = response.getAllCandidates();

        // Assert - Order is preserved
        assertThat(allCandidates).hasSize(5);
        assertThat(allCandidates.get(0).type()).isEqualTo("fact");
        assertThat(allCandidates.get(1).type()).isEqualTo("preference");
        assertThat(allCandidates.get(2).type()).isEqualTo("procedure");
        assertThat(allCandidates.get(3).type()).isEqualTo("problem_solution");
        assertThat(allCandidates.get(4).type()).isEqualTo("decision");
    }
}
