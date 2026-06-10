package io.github.rigazilla.memory.cognition.resource;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Base interface for all resource configurations.
 * Resources are external dependencies that cognitive processes use (LLMs, APIs, databases, caches).
 */
public interface ResourceConfiguration {
    
    /**
     * Get the type of this resource.
     * 
     * @return The resource type
     */
    ResourceType getType();
    
    /**
     * Get the timeout for operations on this resource.
     * 
     * @return The timeout duration
     */
    Duration getTimeout();
    
    /**
     * Get custom properties for this resource.
     * Used for resource-type-specific configuration that doesn't fit standard fields.
     * 
     * @return Map of custom property names to values
     */
    Map<String, String> getCustomProperties();
    
    /**
     * Get a custom property value.
     * 
     * @param key The property key
     * @return The property value, or empty if not set
     */
    default Optional<String> getCustomProperty(String key) {
        return Optional.ofNullable(getCustomProperties().get(key));
    }
}
