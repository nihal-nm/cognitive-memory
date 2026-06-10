package io.github.rigazilla.memory.cognition.resource;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Configuration for Language Model resources.
 * Supports various LLM providers: Ollama, OpenAI, Anthropic, Vertex AI Gemini, etc.
 */
public interface LlmResourceConfiguration extends ResourceConfiguration {
    
    @Override
    default ResourceType getType() {
        return ResourceType.LLM;
    }
    
    /**
     * Get the LLM provider name.
     * Examples: "ollama", "openai", "anthropic", "vertex-ai-gemini"
     * 
     * @return The provider name
     */
    String getProvider();
    
    /**
     * Get the model identifier.
     * Examples: "llama3.2", "gpt-4", "claude-3-opus", "gemini-1.5-flash"
     * 
     * @return The model ID
     */
    String getModel();
    
    /**
     * Get the temperature parameter for generation.
     * Controls randomness: 0.0 = deterministic, 1.0 = very random
     * 
     * @return The temperature value (typically 0.0-1.0)
     */
    Double getTemperature();
    
    /**
     * Get the maximum number of tokens to generate.
     * 
     * @return The max tokens limit
     */
    Integer getMaxTokens();
    
    /**
     * Get the API key for this LLM provider (if required).
     * Resolved from credential reference (e.g., OPENAI_API_KEY environment variable).
     * 
     * @return The API key, or empty if not required/configured
     */
    Optional<String> getApiKey();
    
    /**
     * Get the base URL for the LLM provider (if applicable).
     * Used for self-hosted or custom endpoints.
     * 
     * @return The base URL, or empty if using default
     */
    default Optional<String> getBaseUrl() {
        return getCustomProperty("base-url");
    }
    
    /**
     * Get the top-p parameter for nucleus sampling (if applicable).
     * 
     * @return The top-p value, or empty if not configured
     */
    default Optional<Double> getTopP() {
        return getCustomProperty("top-p").map(Double::parseDouble);
    }
    
    /**
     * Get the frequency penalty parameter (if applicable).
     * 
     * @return The frequency penalty, or empty if not configured
     */
    default Optional<Double> getFrequencyPenalty() {
        return getCustomProperty("frequency-penalty").map(Double::parseDouble);
    }
    
    /**
     * Get the presence penalty parameter (if applicable).
     * 
     * @return The presence penalty, or empty if not configured
     */
    default Optional<Double> getPresencePenalty() {
        return getCustomProperty("presence-penalty").map(Double::parseDouble);
    }
}
