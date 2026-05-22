package io.github.rigazilla.memory.cognition.verification;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;

/**
 * LangChain4j AI Service for verifying memory candidates against evidence.
 * Checks that each candidate's citations actually exist in the conversation transcript.
 * Rejects candidates with invalid, fabricated, or unsupported citations.
 * 
 * Uses the "memory" named model configured in application.properties.
 * Returns structured output via DurableVerificationResponse record.
 */
@RegisterAiService(chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class, modelName = "memory")
public interface DurableMemoryVerifier {
    
    /**
     * Verify memory candidates against conversation evidence.
     * 
     * @param candidatesJson JSON array of memory candidates to verify
     * @param evidence The formatted conversation transcript
     * @return Structured verification response with verified and rejected candidates
     */
    @SystemMessage(fromResource = "prompts/durable-verifier-system.md")
    @UserMessage("""
        Verify the following memory candidates against the conversation transcript.
        
        For each candidate:
        1. Check if ALL citations exist in the transcript (exact or paraphrased)
        2. Verify the memory content is supported by the citations
        3. Mark as VERIFIED if citations are valid, REJECTED if not
        
        Rejection reasons:
        - "Citation not found in transcript"
        - "Citation misrepresents the conversation"
        - "Memory content not supported by citations"
        - "Fabricated or hallucinated information"
        
        Return a structured JSON response with two arrays:
        - verified: Candidates with valid citations
        - rejected: Candidates with invalid citations (include rejection reason)
        
        Candidates to verify:
        {{candidates}}
        
        Transcript:
        {{evidence}}
        """)
    DurableVerificationResponse verify(@V("candidates") String candidatesJson, @V("evidence") String evidence);
}
