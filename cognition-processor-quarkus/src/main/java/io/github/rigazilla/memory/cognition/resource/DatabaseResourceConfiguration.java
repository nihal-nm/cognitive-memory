package io.github.rigazilla.memory.cognition.resource;

import java.time.Duration;
import java.util.Optional;

/**
 * Configuration for database resources.
 * Supports relational databases, vector stores, graph databases, etc.
 */
public interface DatabaseResourceConfiguration extends ResourceConfiguration {
    
    @Override
    default ResourceType getType() {
        return ResourceType.DATABASE;
    }
    
    /**
     * Get the database connection string.
     * Examples: "postgresql://localhost:5432/vectors", "mongodb://localhost:27017/cognition"
     * 
     * @return The connection string
     */
    String getConnectionString();
    
    /**
     * Get the database username.
     * 
     * @return The username
     */
    String getUsername();
    
    /**
     * Get the database password (if required).
     * Resolved from credential reference (e.g., PGVECTOR_PASSWORD environment variable).
     * 
     * @return The password, or empty if not required/configured
     */
    Optional<String> getPassword();
    
    /**
     * Get the connection pool size.
     * 
     * @return The pool size
     */
    Integer getPoolSize();
    
    /**
     * Get the database name/schema to use.
     * 
     * @return The database name, or empty if specified in connection string
     */
    default Optional<String> getDatabaseName() {
        return getCustomProperty("database-name");
    }
    
    /**
     * Get the minimum idle connections in the pool.
     * 
     * @return The minimum idle connections, or empty if using default
     */
    default Optional<Integer> getMinIdleConnections() {
        return getCustomProperty("min-idle-connections").map(Integer::parseInt);
    }
    
    /**
     * Get the maximum lifetime for a connection.
     * 
     * @return The max lifetime, or empty if using default
     */
    default Optional<Duration> getMaxLifetime() {
        return getCustomProperty("max-lifetime")
            .map(s -> Duration.parse(s));
    }
    
    /**
     * Get the connection validation query.
     * 
     * @return The validation query, or empty if using default
     */
    default Optional<String> getValidationQuery() {
        return getCustomProperty("validation-query");
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
}
