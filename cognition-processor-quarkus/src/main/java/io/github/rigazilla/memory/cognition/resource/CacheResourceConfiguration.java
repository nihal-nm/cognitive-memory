package io.github.rigazilla.memory.cognition.resource;

import java.time.Duration;
import java.util.Optional;

/**
 * Configuration for cache resources.
 * Supports Redis, in-memory caches, and other caching systems.
 */
public interface CacheResourceConfiguration extends ResourceConfiguration {
    
    @Override
    default ResourceType getType() {
        return ResourceType.CACHE;
    }
    
    /**
     * Get the cache provider type.
     * Examples: "redis", "memcached", "in-memory"
     * 
     * @return The cache provider
     */
    String getProvider();
    
    /**
     * Get the cache server host.
     * 
     * @return The host address
     */
    String getHost();
    
    /**
     * Get the cache server port.
     * 
     * @return The port number
     */
    Integer getPort();
    
    /**
     * Get the cache password (if required).
     * Resolved from credential reference (e.g., REDIS_PASSWORD environment variable).
     * 
     * @return The password, or empty if not required/configured
     */
    Optional<String> getPassword();
    
    /**
     * Get the database/index number to use (for Redis).
     * 
     * @return The database number, or empty if using default (0)
     */
    default Optional<Integer> getDatabase() {
        return getCustomProperty("database").map(Integer::parseInt);
    }
    
    /**
     * Get the default TTL (time-to-live) for cached entries.
     * 
     * @return The default TTL, or empty if no default
     */
    default Optional<Duration> getDefaultTtl() {
        return getCustomProperty("default-ttl")
            .map(s -> Duration.parse(s));
    }
    
    /**
     * Get the maximum number of connections in the pool.
     * 
     * @return The max connections, or empty if using default
     */
    default Optional<Integer> getMaxConnections() {
        return getCustomProperty("max-connections").map(Integer::parseInt);
    }
    
    /**
     * Get the minimum idle connections in the pool.
     * 
     * @return The min idle connections, or empty if using default
     */
    default Optional<Integer> getMinIdleConnections() {
        return getCustomProperty("min-idle-connections").map(Integer::parseInt);
    }
    
    /**
     * Check if SSL/TLS should be used for the connection.
     * 
     * @return true if SSL should be used, false otherwise
     */
    default boolean isUseSsl() {
        return getCustomProperty("use-ssl")
            .map(Boolean::parseBoolean)
            .orElse(false);
    }
    
    /**
     * Get the key prefix to use for all cache entries.
     * Useful for namespacing in shared cache instances.
     * 
     * @return The key prefix, or empty if no prefix
     */
    default Optional<String> getKeyPrefix() {
        return getCustomProperty("key-prefix");
    }
}
