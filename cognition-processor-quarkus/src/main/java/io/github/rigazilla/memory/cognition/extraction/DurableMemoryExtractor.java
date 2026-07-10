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
        Extract ALL information from the following conversation transcript. Do not skip anything — every event, detail, feeling, plan, item, name, date, and fact matters.

        For each piece of information, create a separate memory with:
        1. The memory content (clear, concise, standalone statement with all specific details preserved)
        2. Confidence level (0.0-1.0)
        3. Citations (quotes from the transcript)

        Resolve relative dates using the entry timestamps: "last week" in a [2023-07-06] entry → "around late June 2023".

        Return a structured JSON with arrays: facts, preferences, procedures, problemSolutions, decisions.
        Most extractions should be facts. Each memory needs: type, content, confidence, citations.

        Transcript:
        {{evidence}}
        """)
    DurableExtractionResponse extract(@V("evidence") String evidence);
}