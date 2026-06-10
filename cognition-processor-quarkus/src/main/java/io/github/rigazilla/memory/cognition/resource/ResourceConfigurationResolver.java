package io.github.rigazilla.memory.cognition.resource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves resource configurations using a 3-level hierarchy:
 * 1. Named resource overrides (most specific)
 * 2. Process defaults
 * 3. Global defaults (fallback)
 * 
 * Configuration format:
 * - Global: cognition.resources.default.{type}.{property}
 * - Process: cognition.process.{processId}.{type}.{property}
 * - Named: cognition.process.{processId}.resources.{resourceName}.{type}.{property}
 */
@ApplicationScoped
public class ResourceConfigurationResolver {
    
    private static final Logger LOG = Logger.getLogger(ResourceConfigurationResolver.class);
    
    @Inject
    CredentialResolver credentialResolver;
    
    private final Config config;
    
    public ResourceConfigurationResolver() {
        this.config = ConfigProvider.getConfig();
    }
    
    /**
     * Resolve an LLM resource configuration.
     * 
     * @param processId The process ID
     * @param resourceName The resource name (can be null for process default)
     * @return The resolved LLM configuration
     */
    public LlmResourceConfiguration resolveLlm(String processId, String resourceName) {
        LOG.debugf("Resolving LLM resource: process=%s, resource=%s", processId, resourceName);
        
        String prefix = buildPrefix(processId, resourceName, "llm");
        String processPrefix = buildProcessPrefix(processId, "llm");
        String globalPrefix = "cognition.resources.default.llm";
        
        // Build configuration with 3-level hierarchy
        String provider = getConfigValue(prefix, processPrefix, globalPrefix, "provider")
            .orElseThrow(() -> new IllegalStateException("LLM provider not configured"));
        
        String model = getConfigValue(prefix, processPrefix, globalPrefix, "model")
            .orElseThrow(() -> new IllegalStateException("LLM model not configured"));
        
        Double temperature = getConfigValue(prefix, processPrefix, globalPrefix, "temperature")
            .map(Double::parseDouble)
            .orElse(0.1);
        
        Integer maxTokens = getConfigValue(prefix, processPrefix, globalPrefix, "max-tokens")
            .map(Integer::parseInt)
            .orElse(4096);
        
        Duration timeout = getConfigValue(prefix, processPrefix, globalPrefix, "timeout")
            .map(Duration::parse)
            .orElse(Duration.ofSeconds(120));
        
        // Resolve API key if configured
        Optional<String> apiKeyRef = getConfigValue(prefix, processPrefix, globalPrefix, "api-key-ref");
        Optional<String> apiKey = apiKeyRef.map(credentialResolver::resolve);
        
        // Get custom properties
        Map<String, String> customProps = new HashMap<>();
        getConfigValue(prefix, processPrefix, globalPrefix, "base-url")
            .ifPresent(v -> customProps.put("base-url", v));
        getConfigValue(prefix, processPrefix, globalPrefix, "top-p")
            .ifPresent(v -> customProps.put("top-p", v));
        getConfigValue(prefix, processPrefix, globalPrefix, "frequency-penalty")
            .ifPresent(v -> customProps.put("frequency-penalty", v));
        getConfigValue(prefix, processPrefix, globalPrefix, "presence-penalty")
            .ifPresent(v -> customProps.put("presence-penalty", v));
        
        return new DefaultLlmResourceConfiguration(
            provider, model, temperature, maxTokens, timeout, apiKey, customProps
        );
    }
    
    /**
     * Resolve an API resource configuration.
     * 
     * @param processId The process ID
     * @param resourceName The resource name (can be null for process default)
     * @return The resolved API configuration
     */
    public ApiResourceConfiguration resolveApi(String processId, String resourceName) {
        LOG.debugf("Resolving API resource: process=%s, resource=%s", processId, resourceName);
        
        String prefix = buildPrefix(processId, resourceName, "api");
        String processPrefix = buildProcessPrefix(processId, "api");
        String globalPrefix = "cognition.resources.default.api";
        
        String endpoint = getConfigValue(prefix, processPrefix, globalPrefix, "endpoint")
            .orElseThrow(() -> new IllegalStateException("API endpoint not configured"));
        
        Duration timeout = getConfigValue(prefix, processPrefix, globalPrefix, "timeout")
            .map(Duration::parse)
            .orElse(Duration.ofSeconds(30));
        
        Integer retryAttempts = getConfigValue(prefix, processPrefix, globalPrefix, "retry-attempts")
            .map(Integer::parseInt)
            .orElse(3);
        
        Duration retryDelay = getConfigValue(prefix, processPrefix, globalPrefix, "retry-delay")
            .map(Duration::parse)
            .orElse(Duration.ofSeconds(1));
        
        // Resolve credentials if configured
        Optional<String> apiKeyRef = getConfigValue(prefix, processPrefix, globalPrefix, "api-key-ref");
        Optional<String> apiKey = apiKeyRef.map(credentialResolver::resolve);
        
        Optional<String> bearerTokenRef = getConfigValue(prefix, processPrefix, globalPrefix, "bearer-token-ref");
        Optional<String> bearerToken = bearerTokenRef.map(credentialResolver::resolve);
        
        // Get headers
        Map<String, String> headers = new HashMap<>();
        // TODO: Parse headers from config
        
        // Get custom properties
        Map<String, String> customProps = new HashMap<>();
        getConfigValue(prefix, processPrefix, globalPrefix, "connection-timeout")
            .ifPresent(v -> customProps.put("connection-timeout", v));
        getConfigValue(prefix, processPrefix, globalPrefix, "read-timeout")
            .ifPresent(v -> customProps.put("read-timeout", v));
        getConfigValue(prefix, processPrefix, globalPrefix, "insecure-skip-verify")
            .ifPresent(v -> customProps.put("insecure-skip-verify", v));
        
        return new DefaultApiResourceConfiguration(
            endpoint, headers, apiKey, bearerToken, retryAttempts, retryDelay, timeout, customProps
        );
    }
    
    /**
     * Build configuration prefix for named resource.
     */
    private String buildPrefix(String processId, String resourceName, String type) {
        if (resourceName != null && !resourceName.isBlank()) {
            return String.format("cognition.process.%s.resources.%s.%s", processId, resourceName, type);
        }
        return buildProcessPrefix(processId, type);
    }
    
    /**
     * Build configuration prefix for process default.
     */
    private String buildProcessPrefix(String processId, String type) {
        return String.format("cognition.process.%s.%s", processId, type);
    }
    
    /**
     * Get configuration value with 3-level hierarchy.
     */
    private Optional<String> getConfigValue(String namedPrefix, String processPrefix, String globalPrefix, String property) {
        // 1. Check named resource override
        Optional<String> namedValue = config.getOptionalValue(namedPrefix + "." + property, String.class);
        if (namedValue.isPresent()) {
            LOG.tracef("Found named config: %s.%s = %s", namedPrefix, property, namedValue.get());
            return namedValue;
        }
        
        // 2. Check process default
        Optional<String> processValue = config.getOptionalValue(processPrefix + "." + property, String.class);
        if (processValue.isPresent()) {
            LOG.tracef("Found process config: %s.%s = %s", processPrefix, property, processValue.get());
            return processValue;
        }
        
        // 3. Check global default
        Optional<String> globalValue = config.getOptionalValue(globalPrefix + "." + property, String.class);
        if (globalValue.isPresent()) {
            LOG.tracef("Found global config: %s.%s = %s", globalPrefix, property, globalValue.get());
        }
        return globalValue;
    }
}
