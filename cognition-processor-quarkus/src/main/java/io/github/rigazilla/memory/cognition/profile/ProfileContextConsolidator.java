package io.github.rigazilla.memory.cognition.profile;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;

/**
 * LangChain4j AI Service for consolidating user memories into a profile snapshot.
 * Uses the "memory" named model configured in application.properties.
 * Returns structured output via ProfileConsolidationResponse record.
 */
@RegisterAiService(chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class, modelName = "memory")
public interface ProfileContextConsolidator {
    
    /**
     * Consolidate user memories into a profile snapshot with 3 core sections.
     * 
     * @param memoriesJson JSON array of memory items with type, content, confidence, citations
     * @return Structured consolidation response with profile, goals, and preferences sections
     */
    @SystemMessage(fromResource = "prompts/profile-consolidator-system.md")
    @UserMessage("""
        Consolidate the following user memories into a profile snapshot.
        
        Create 3 sections:
        
        1. **Profile Snapshot** - Identity, role, location, background, education, employment
        2. **Active Goals** - Current work, projects, objectives, tasks in progress
        3. **Preferences** - Working style, tools, communication preferences, coding preferences
        
        For each section provide:
        - content: Coherent markdown text (2-4 paragraphs)
        - confidence: Overall confidence level (0.0-1.0)
        - sourceMemoryKeys: List of memory keys used as sources
        
        Guidelines:
        - Only include information supported by the provided memories
        - Be concise and actionable
        - Prioritize recent and high-confidence memories
        - Group related facts into coherent narratives
        - Omit low-confidence or contradictory information
        
        Return a structured JSON response with profileSnapshot, activeGoals, and preferences sections.
        
        Memories:
        {{memoriesJson}}
        """)
    ProfileConsolidationResponse consolidate(@V("memoriesJson") String memoriesJson);
}
