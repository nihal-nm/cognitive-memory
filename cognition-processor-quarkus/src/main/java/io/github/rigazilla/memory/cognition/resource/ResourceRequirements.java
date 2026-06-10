package io.github.rigazilla.memory.cognition.resource;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Declares resource requirements for a cognitive process.
 * Processes use this to specify which external resources they need (LLMs, APIs, databases, caches).
 */
public class ResourceRequirements {
    
    private final Map<String, ResourceConfiguration> namedResources;
    
    private ResourceRequirements(Map<String, ResourceConfiguration> namedResources) {
        this.namedResources = Map.copyOf(namedResources);
    }
    
    /**
     * Get a named resource configuration.
     * 
     * @param resourceName The resource name
     * @return The resource configuration, or empty if not found
     */
    public Optional<ResourceConfiguration> getResource(String resourceName) {
        return Optional.ofNullable(namedResources.get(resourceName));
    }
    
    /**
     * Get all named resources.
     * 
     * @return Map of resource names to configurations
     */
    public Map<String, ResourceConfiguration> getAllResources() {
        return namedResources;
    }
    
    /**
     * Create a new builder for resource requirements.
     * 
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Builder for ResourceRequirements.
     */
    public static class Builder {
        private final Map<String, ResourceConfiguration> resources = new HashMap<>();
        
        /**
         * Add an LLM resource requirement.
         * 
         * @param name The resource name (e.g., "extractor", "verifier")
         * @param config Configuration for the LLM resource
         * @return This builder
         */
        public Builder llm(String name, LlmResourceConfiguration config) {
            resources.put(name, config);
            return this;
        }
        
        /**
         * Add an API resource requirement.
         * 
         * @param name The resource name (e.g., "ner-service", "knowledge-graph")
         * @param config Configuration for the API resource
         * @return This builder
         */
        public Builder api(String name, ApiResourceConfiguration config) {
            resources.put(name, config);
            return this;
        }
        
        /**
         * Add a database resource requirement.
         * 
         * @param name The resource name (e.g., "vector-store", "graph-db")
         * @param config Configuration for the database resource
         * @return This builder
         */
        public Builder database(String name, DatabaseResourceConfiguration config) {
            resources.put(name, config);
            return this;
        }
        
        /**
         * Add a cache resource requirement.
         * 
         * @param name The resource name (e.g., "redis-cache", "memory-cache")
         * @param config Configuration for the cache resource
         * @return This builder
         */
        public Builder cache(String name, CacheResourceConfiguration config) {
            resources.put(name, config);
            return this;
        }
        
        /**
         * Build the ResourceRequirements instance.
         * 
         * @return The built ResourceRequirements
         */
        public ResourceRequirements build() {
            return new ResourceRequirements(resources);
        }
    }
}
