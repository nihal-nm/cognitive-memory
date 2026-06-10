package io.github.rigazilla.memory.cognition.resource;

/**
 * Types of external resources that cognitive processes can use.
 */
public enum ResourceType {
    /**
     * Language Model resources (Ollama, OpenAI, Gemini, etc.)
     */
    LLM,
    
    /**
     * External HTTP API resources (spaCy, custom NLP services, knowledge graphs, etc.)
     */
    API,
    
    /**
     * Database resources (PostgreSQL, vector stores, graph databases, etc.)
     */
    DATABASE,
    
    /**
     * Cache resources (Redis, in-memory caches, etc.)
     */
    CACHE
}
