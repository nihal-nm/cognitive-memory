package io.github.rigazilla.memory.cognition.extraction;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;

/**
 * LangChain4j AI Service for extracting durable memories from conversation evidence.
 * Extracts all 5 memory types in a single batched LLM call:
 * - Facts: Objective, verifiable information
 * - Preferences: User likes, dislikes, choices
 * - Procedures: Step-by-step processes or workflows
 * - Problem Solutions: Issues encountered and their resolutions
 * - Decisions: Choices made and their rationale
 * 
 * Uses the "memory" named model configured in application.properties.
 * Returns structured output via DurableExtractionResponse record.
 */
@RegisterAiService(chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class, modelName = "memory")
public interface DurableMemoryExtractor {
    
    /**
     * Extract durable memories from conversation evidence.
     * 
     * @param evidence The formatted conversation transcript
     * @return Structured extraction response with all memory types
     */
    @SystemMessage(fromResource = "prompts/durable-extractor-system.md")
    @UserMessage("""
        Extract durable memories from the following conversation transcript.

        For each memory type, identify:
        1. The memory content (clear, concise statement)
        2. Confidence level (0.0-1.0)
        3. Citations (specific quotes or references from the transcript)

        Memory Types:
        - **Facts**: Objective, verifiable information (e.g., "Caroline went hiking at Mount Rainier on May 15th, 2024")
        - **Preferences**: User likes, dislikes, and reasons (e.g., "User prefers dark mode because bright screens cause eye strain")
        - **Procedures**: Step-by-step processes (e.g., "User's deployment workflow: 1. Run tests, 2. Build, 3. Deploy")
        - **Problem Solutions**: Issues with causes and resolutions (e.g., "Build failed because JAVA_HOME pointed to JDK 11; fixed by updating to JDK 17")
        - **Decisions**: Choices with rationale and alternatives (e.g., "Chose PostgreSQL over MongoDB because the app requires ACID transactions")

        IMPORTANT:
        - Always include dates, times, and temporal markers when mentioned in the transcript.
        - Always include the cause/reason when a causal relationship is stated.
        - Always use full names for people, places, and organizations.
        - Extract ALL specific details: book titles, song names, pet names, place names, items bought, counts (number of children, number of visits), relationship status, country of origin. Do not skip seemingly minor details.

        Return a structured JSON response with arrays for each memory type.
        Each memory should have: type, content, confidence, citations.

        Transcript:
        {{evidence}}
        """)
    DurableExtractionResponse extract(@V("evidence") String evidence);
}