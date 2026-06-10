package io.github.rigazilla.memory.cognition.profile;

import io.github.rigazilla.memory.cognition.process.CognitiveProcess;
import io.github.rigazilla.memory.cognition.process.ManagedProcessInspection;
import io.github.rigazilla.memory.cognition.process.ManagedProcessState;
import io.github.rigazilla.memory.cognition.resource.LlmResourceConfiguration;
import io.github.rigazilla.memory.cognition.resource.ResourceRequirements;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Managed process for profile context consolidation.
 * Phase 0: Manual trigger only, no automatic scheduling.
 */
@ApplicationScoped
public class ProfileContextConsolidationProcess implements CognitiveProcess {
    
    private static final Logger LOG = Logger.getLogger(ProfileContextConsolidationProcess.class);
    public static final String PROCESS_ID = "profile-context-consolidation";
    
    @Inject
    ProfileContextService profileContextService;

    @ConfigProperty(name = "cognition.resources.default.llm.model")
    String defaultModel;

    @ConfigProperty(name = "cognition.resources.default.llm.base-url")
    String defaultBaseUrl;

    @ConfigProperty(name = "cognition.resources.default.llm.provider")
    String defaultProvider;
    
    private final AtomicReference<Instant> lastRunTime = new AtomicReference<>();
    private final AtomicReference<String> lastRunStatus = new AtomicReference<>("never_run");
    private final AtomicReference<String> lastRunUserId = new AtomicReference<>();
    
    @Override
    public String id() {
        return PROCESS_ID;
    }
    
    @Override
    public String displayName() {
        return "Profile Context Consolidation";
    }
    
    @Override
    public String description() {
        return "Consolidates user memories into profile snapshots (manual trigger only in Phase 0)";
    }
    
    @Override
    public boolean supportsStart() {
        return false;  // No automatic start in Phase 0
    }
    
    @Override
    public boolean supportsEnable() {
        return false;
    }
    
    @Override
    public boolean supportsDisable() {
        return false;
    }
    
    @Override
    public ManagedProcessState state() {
        return ManagedProcessState.ENABLED;
    }
    
    @Override
    public ManagedProcessInspection inspect() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("mode", "manual_trigger");
        details.put("lastRunTime", lastRunTime.get() != null ? lastRunTime.get().toString() : "never");
        details.put("lastRunStatus", lastRunStatus.get());
        details.put("lastRunUserId", lastRunUserId.get() != null ? lastRunUserId.get() : "none");
        
        // Add resource type information with prompts
        ResourceRequirements requirements = getResourceRequirements();
        if (requirements != null) {
            Map<String, Map<String, String>> resourceTypes = new LinkedHashMap<>();
            requirements.getAllResources().forEach((name, config) -> {
                Map<String, String> resourceInfo = new LinkedHashMap<>();
                resourceInfo.put("type", config.getType().name());
                
                // Add prompt content for LLM resources
                if (config.getType() == io.github.rigazilla.memory.cognition.resource.ResourceType.LLM) {
                    // Add model and endpoint information
                    resourceInfo.put("model", defaultModel);
                    resourceInfo.put("endpoint", defaultBaseUrl);
                    resourceInfo.put("provider", defaultProvider);
                    
                    String promptPath = switch (name) {
                        case "consolidator" -> "prompts/profile-consolidator-system.md";
                        default -> null;
                    };
                    if (promptPath != null) {
                        try {
                            String promptContent = new String(
                                getClass().getClassLoader()
                                    .getResourceAsStream(promptPath)
                                    .readAllBytes()
                            );
                            resourceInfo.put("prompt", promptContent);
                        } catch (Exception e) {
                            LOG.warnf("Failed to load prompt from %s: %s", promptPath, e.getMessage());
                            resourceInfo.put("prompt", "Error loading prompt: " + promptPath);
                        }
                    }
                }
                
                resourceTypes.put(name, resourceInfo);
            });
            details.put("resourceTypes", resourceTypes);
        }
        
        return new ManagedProcessInspection(
            id(),
            displayName(),
            description(),
            state(),
            details
        );
    }
    
    /**
     * Trigger consolidation for a specific user.
     * Called by ProfileContextResource.
     */
    public void triggerConsolidation(String userId) {
        LOG.infof("Triggering consolidation for user: %s", userId);
        
        try {
            profileContextService.consolidateProfile(userId);
            lastRunTime.set(Instant.now());
            lastRunStatus.set("success");
            lastRunUserId.set(userId);
            LOG.infof("Consolidation completed successfully for user: %s", userId);
            
        } catch (Exception e) {
            lastRunTime.set(Instant.now());
            lastRunStatus.set("error: " + e.getMessage());
            lastRunUserId.set(userId);
            LOG.errorf(e, "Consolidation failed for user: %s", userId);
            throw e;
        }
    }

    @Override
    public ResourceRequirements getResourceRequirements() {
        // Declare LLM resource for profile consolidation
        // Uses higher temperature (0.3) for more creative consolidation
        return ResourceRequirements.builder()
            .llm("consolidator", createDefaultLlmConfig())
            .build();
    }

    private LlmResourceConfiguration createDefaultLlmConfig() {
        // Default configuration - will be overridden by resolver from application.properties
        return new LlmResourceConfiguration() {
            @Override
            public String getProvider() {
                return "ollama";
            }

            @Override
            public String getModel() {
                return "llama3.2";
            }

            @Override
            public Double getTemperature() {
                return 0.3;  // Higher temperature for creative consolidation
            }

            @Override
            public Integer getMaxTokens() {
                return 4096;
            }

            @Override
            public Optional<String> getApiKey() {
                return Optional.empty();
            }

            @Override
            public Duration getTimeout() {
                return Duration.ofSeconds(120);
            }

            @Override
            public Map<String, String> getCustomProperties() {
                return Map.of();
            }
        };
    }
}
