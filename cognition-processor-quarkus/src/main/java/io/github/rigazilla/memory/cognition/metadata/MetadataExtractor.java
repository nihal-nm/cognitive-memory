package io.github.rigazilla.memory.cognition.metadata;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;

/**
 * LangChain4j AI Service for extracting entity and topic metadata from a memory item.
 * Uses the "memory" named model configured in application.properties.
 * Returns structured output via MetadataExtractionResponse record.
 */
@RegisterAiService(
        chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class,
        modelName = "memory")
public interface MetadataExtractor {

    /**
     * Extract entities and classify topics for a single memory.
     *
     * @param memoryType The memory type (fact, preference, procedure, problem_solution, decision)
     * @param content    The memory content text
     * @return Structured extraction response with entities and topics
     */
    @SystemMessage(fromResource = "prompts/metadata-extractor-system.md")
    @UserMessage("Extract entities and classify topics for the following memory.\n\n"
            + "Memory Type: {{memoryType}}\n"
            + "Memory Content: {{content}}\n\n"
            + "Return JSON with:\n"
            + "- entities: array of {\"name\": \"...\", \"type\": \"...\"} objects\n"
            + "- topics: array of topic strings (use hierarchical format where appropriate,"
            + " e.g. \"programming/scripting\")\n\n"
            + "Valid entity types: technology, organization, person, location, product, concept\n"
            + "If no entities or topics are found, return empty arrays.")
    MetadataExtractionResponse extract(
            @V("memoryType") String memoryType,
            @V("content") String content
    );
}
