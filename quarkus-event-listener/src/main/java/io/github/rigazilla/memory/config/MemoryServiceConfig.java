package io.github.rigazilla.memory.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * Configuration for memory-service event stream connection.
 */
@ConfigMapping(prefix = "memory-service")
public interface MemoryServiceConfig {

    /**
     * Base URL of the memory-service instance.
     */
    @WithName("url")
    @WithDefault("http://localhost:9090")
    String baseUrl();

    /**
     * Authentication bearer token.
     */
    @WithName("token")
    String token();

    /**
     * Event kinds to subscribe to (comma-separated).
     */
    @WithName("event-kinds")
    @WithDefault("conversation")
    String eventKinds();

    /**
     * Detail level: "summary" or "full".
     */
    @WithName("detail-level")
    @WithDefault("summary")
    String detailLevel();

    /**
     * Enable/disable the event listener.
     */
    @WithName("enabled")
    @WithDefault("true")
    boolean enabled();

    /**
     * API Key for authentication (X-API-Key header).
     */
    @WithName("api-key")
    @WithDefault("test-key")
    String apiKey();
}
