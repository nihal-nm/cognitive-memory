package io.github.rigazilla.memory.cognition.resource;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Configuration for external HTTP API resources.
 * Supports REST APIs, gRPC services, and other HTTP-based external services.
 */
public interface ApiResourceConfiguration extends ResourceConfiguration {
    
    @Override
    default ResourceType getType() {
        return ResourceType.API;
    }
    
    /**
     * Get the API endpoint URL.
     * Examples: "http://spacy-service:8000/ner", "https://api.example.com/v1"
     * 
     * @return The endpoint URL
     */
    String getEndpoint();
    
    /**
     * Get custom HTTP headers to include in requests.
     * 
     * @return Map of header names to values
     */
    Map<String, String> getHeaders();
    
    /**
     * Get the API key for authentication (if required).
     * Resolved from credential reference (e.g., SPACY_API_KEY environment variable).
     * 
     * @return The API key, or empty if not required/configured
     */
    Optional<String> getApiKey();
    
    /**
     * Get the bearer token for authentication (if required).
     * Resolved from credential reference (e.g., SERVICE_BEARER_TOKEN environment variable).
     * 
     * @return The bearer token, or empty if not required/configured
     */
    Optional<String> getBearerToken();
    
    /**
     * Get the number of retry attempts for failed requests.
     * 
     * @return The retry attempts count
     */
    Integer getRetryAttempts();
    
    /**
     * Get the delay between retry attempts.
     * 
     * @return The retry delay duration
     */
    Duration getRetryDelay();
    
    /**
     * Get the connection timeout for establishing connections.
     * 
     * @return The connection timeout, or empty if using default
     */
    default Optional<Duration> getConnectionTimeout() {
        return getCustomProperty("connection-timeout")
            .map(s -> Duration.parse(s));
    }
    
    /**
     * Get the read timeout for reading responses.
     * 
     * @return The read timeout, or empty if using default
     */
    default Optional<Duration> getReadTimeout() {
        return getCustomProperty("read-timeout")
            .map(s -> Duration.parse(s));
    }
    
    /**
     * Check if SSL certificate validation should be disabled.
     * WARNING: Only use for development/testing with self-signed certificates.
     * 
     * @return true if SSL validation should be disabled, false otherwise
     */
    default boolean isInsecureSkipVerify() {
        return getCustomProperty("insecure-skip-verify")
            .map(Boolean::parseBoolean)
            .orElse(false);
    }
}
