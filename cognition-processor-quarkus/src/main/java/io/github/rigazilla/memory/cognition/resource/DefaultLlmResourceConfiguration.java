package io.github.rigazilla.memory.cognition.resource;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Default implementation of LlmResourceConfiguration.
 */
public class DefaultLlmResourceConfiguration implements LlmResourceConfiguration {
    
    private final String provider;
    private final String model;
    private final Double temperature;
    private final Integer maxTokens;
    private final Duration timeout;
    private final Optional<String> apiKey;
    private final Map<String, String> customProperties;
    
    public DefaultLlmResourceConfiguration(
            String provider,
            String model,
            Double temperature,
            Integer maxTokens,
            Duration timeout,
            Optional<String> apiKey,
            Map<String, String> customProperties) {
        this.provider = provider;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.timeout = timeout;
        this.apiKey = apiKey;
        this.customProperties = Map.copyOf(customProperties);
    }
    
    @Override
    public String getProvider() {
        return provider;
    }
    
    @Override
    public String getModel() {
        return model;
    }
    
    @Override
    public Double getTemperature() {
        return temperature;
    }
    
    @Override
    public Integer getMaxTokens() {
        return maxTokens;
    }
    
    @Override
    public Optional<String> getApiKey() {
        return apiKey;
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
        return String.format("LlmResourceConfiguration{provider='%s', model='%s', "
                + "temperature=%.2f, maxTokens=%d, timeout=%s}",
                provider, model, temperature, maxTokens, timeout);
    }
}
