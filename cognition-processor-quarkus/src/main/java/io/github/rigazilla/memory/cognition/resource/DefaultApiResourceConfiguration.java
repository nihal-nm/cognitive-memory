package io.github.rigazilla.memory.cognition.resource;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Default implementation of ApiResourceConfiguration.
 */
public class DefaultApiResourceConfiguration implements ApiResourceConfiguration {
    
    private final String endpoint;
    private final Map<String, String> headers;
    private final Optional<String> apiKey;
    private final Optional<String> bearerToken;
    private final Integer retryAttempts;
    private final Duration retryDelay;
    private final Duration timeout;
    private final Map<String, String> customProperties;
    
    public DefaultApiResourceConfiguration(
            String endpoint,
            Map<String, String> headers,
            Optional<String> apiKey,
            Optional<String> bearerToken,
            Integer retryAttempts,
            Duration retryDelay,
            Duration timeout,
            Map<String, String> customProperties) {
        this.endpoint = endpoint;
        this.headers = Map.copyOf(headers);
        this.apiKey = apiKey;
        this.bearerToken = bearerToken;
        this.retryAttempts = retryAttempts;
        this.retryDelay = retryDelay;
        this.timeout = timeout;
        this.customProperties = Map.copyOf(customProperties);
    }
    
    @Override
    public String getEndpoint() {
        return endpoint;
    }
    
    @Override
    public Map<String, String> getHeaders() {
        return headers;
    }
    
    @Override
    public Optional<String> getApiKey() {
        return apiKey;
    }
    
    @Override
    public Optional<String> getBearerToken() {
        return bearerToken;
    }
    
    @Override
    public Integer getRetryAttempts() {
        return retryAttempts;
    }
    
    @Override
    public Duration getRetryDelay() {
        return retryDelay;
    }
    
    @Override
    public Duration getTimeout() {
        return timeout;
    }
    
    @Override
    public Map<String, String> getCustomProperties() {
        return customProperties;
    }
    
    @Override
    public String toString() {
        return String.format("ApiResourceConfiguration{endpoint='%s', retryAttempts=%d, timeout=%s}",
            endpoint, retryAttempts, timeout);
    }
}
