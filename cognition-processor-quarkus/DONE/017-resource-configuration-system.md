# 017 - Resource Configuration System

## Overview

Implemented a hierarchical resource configuration system that allows cognitive processes to declare and configure their external resource dependencies (LLMs, APIs, databases, caches) independently while avoiding configuration explosion.

## Problem Statement

- Each cognitive process needs independent resource configuration
- Current implementation: All processes share the same "memory" LangChain4j model
- Need to support multiple resource types: LLMs, external APIs, databases, caches
- Must avoid configuration file explosion
- Require secure credential management (API keys, passwords, tokens)

## Solution

### Three-Level Configuration Hierarchy

```
Global Defaults → Process Defaults → Named Resource Overrides
```

**Configuration precedence:**
1. Named resource override (most specific) - `cognition.process.{processId}.resources.{resourceName}.{type}.{property}`
2. Process default - `cognition.process.{processId}.{type}.{property}`
3. Global default (fallback) - `cognition.resources.default.{type}.{property}`

### Architecture Components

#### 1. Resource Types (First-Class Citizens)

**Enum: `ResourceType`**
- `LLM` - Language models (Ollama, OpenAI, Gemini)
- `API` - External HTTP services (spaCy, custom NLP, knowledge graphs)
- `DATABASE` - Databases (PostgreSQL, vector stores, graph databases)
- `CACHE` - Cache systems (Redis, in-memory caches)

#### 2. Resource Configuration Interfaces

**Base Interface: `ResourceConfiguration`**
- Common properties: type, timeout, custom properties
- Extensible via custom properties map

**Type-Specific Interfaces:**
- `LlmResourceConfiguration` - provider, model, temperature, max tokens, API key
- `ApiResourceConfiguration` - endpoint, headers, API key, bearer token, retry config
- `DatabaseResourceConfiguration` - connection string, username, password, pool size
- `CacheResourceConfiguration` - provider, host, port, password, TTL

#### 3. Credential Management

**Service: `CredentialResolver`**
- Resolves credential references to actual values
- Supports multiple providers: environment variables, Vault, AWS Secrets Manager
- Fail-fast on missing credentials
- Never logs actual credential values

**Security Features:**
- Credentials stored separately from configuration
- Configuration files contain references only (e.g., `api-key-ref=OPENAI_API_KEY`)
- Environment variables or secrets manager for actual values
- Audit logging of credential access (not values)

#### 4. Configuration Resolution

**Service: `ResourceConfigurationResolver`**
- Implements 3-level hierarchy resolution
- Parses configuration from `application.properties`
- Resolves credentials via `CredentialResolver`
- Returns strongly-typed configuration objects

**Resolution Algorithm:**
```java
1. Check named resource override (cognition.process.{id}.resources.{name}.{type}.{prop})
2. If not found, check process default (cognition.process.{id}.{type}.{prop})
3. If not found, use global default (cognition.resources.default.{type}.{prop})
4. Resolve any credential references
5. Return typed configuration object
```

#### 5. Process Integration

**Interface: `CognitiveProcess`**
- Added `getResourceRequirements()` method
- Returns `ResourceRequirements` or null for global defaults only

**Class: `ResourceRequirements`**
- Builder pattern for declaring resource needs
- Named resources: multiple resources of same type per process
- Example:
  ```java
  ResourceRequirements.builder()
      .llm("extractor", extractorConfig)
      .llm("verifier", verifierConfig)
      .api("ner-service", apiConfig)
      .build()
  ```

### Implementation Details

#### Process Migration

**DurableMemoryExtractionProcess:**
- Declares 2 LLM resources: "extractor" and "verifier"
- Both use default configuration (can be overridden)
- Temperature: 0.1 (low for consistency)

**ProfileContextConsolidationProcess:**
- Declares 1 LLM resource: "consolidator"
- Uses higher temperature: 0.3 (for creative consolidation)
- Overridden in `application.properties`

#### Configuration File Structure

```properties
# Global defaults (used by all processes unless overridden)
cognition.resources.default.llm.provider=ollama
cognition.resources.default.llm.model=llama3.2
cognition.resources.default.llm.temperature=0.1
cognition.resources.default.llm.max-tokens=4096
cognition.resources.default.llm.timeout=120s

# Process-specific overrides
cognition.process.profile-context-consolidation.llm.temperature=0.3

# Named resource overrides (examples)
# cognition.process.durable-memory-extraction.resources.verifier.llm.model=llama3.2:70b
# cognition.process.durable-memory-extraction.resources.verifier.llm.temperature=0.05
```

#### Credential Configuration

```properties
# In application.properties - reference only
cognition.process.entity-extraction.resources.openai-llm.llm.api-key-ref=OPENAI_API_KEY

# In .env or environment - actual value
OPENAI_API_KEY=sk-proj-...
```

## Benefits

1. **Multi-Resource Support** - LLMs, APIs, databases, caches from day one
2. **Secure Credentials** - Separate credential management from configuration
3. **Minimal Configuration** - Most processes use global defaults
4. **Independent Processes** - Each declares its own resource needs
5. **Named Resources** - Multiple resources of same type per process
6. **Type-Safe** - Strongly-typed configuration per resource type
7. **Clear Hierarchy** - Explicit precedence rules
8. **Extensible** - Easy to add new resource types and secrets providers

## Future Work

### Phase 1: ResourceFactory (Not Yet Implemented)
- Dynamic resource instantiation from configurations
- Factory pattern for creating LLM clients, API clients, etc.
- Integration with existing LangChain4j services

### Phase 2: LangChain4j Integration (Not Yet Implemented)
- Migrate `@RegisterAiService` to use resolved configurations
- Dynamic model selection based on process requirements
- Runtime configuration updates

### Phase 3: Additional Secrets Providers
- HashiCorp Vault integration
- AWS Secrets Manager integration
- Azure Key Vault integration

### Phase 4: Configuration Validation
- Startup validation of all process resource requirements
- Early detection of missing credentials
- Configuration health checks

### Phase 5: Resource Monitoring
- Track resource usage per process
- Cost tracking for paid LLM providers
- Performance metrics per resource

## Testing Strategy

### Unit Tests Needed
- `CredentialResolver` - environment variable resolution
- `ResourceConfigurationResolver` - 3-level hierarchy
- Configuration parsing and merging
- Credential reference resolution

### Integration Tests Needed
- End-to-end process resource resolution
- Multiple processes with different configurations
- Credential not found scenarios
- Configuration override precedence

## Migration Notes

### Backward Compatibility
- Existing LangChain4j `@RegisterAiService` annotations still work
- New system is additive, not breaking
- Processes can opt-in gradually

### Migration Path for New Processes
1. Implement `getResourceRequirements()` in process
2. Declare named resources with builder
3. Add configuration overrides if needed
4. Set credential references for external services

## Files Created

### Core Interfaces
- `io.github.rigazilla.memory.cognition.resource.ResourceType`
- `io.github.rigazilla.memory.cognition.resource.ResourceConfiguration`
- `io.github.rigazilla.memory.cognition.resource.LlmResourceConfiguration`
- `io.github.rigazilla.memory.cognition.resource.ApiResourceConfiguration`
- `io.github.rigazilla.memory.cognition.resource.DatabaseResourceConfiguration`
- `io.github.rigazilla.memory.cognition.resource.CacheResourceConfiguration`
- `io.github.rigazilla.memory.cognition.resource.ResourceRequirements`

### Services
- `io.github.rigazilla.memory.cognition.resource.CredentialResolver`
- `io.github.rigazilla.memory.cognition.resource.ResourceConfigurationResolver`

### Implementations
- `io.github.rigazilla.memory.cognition.resource.DefaultLlmResourceConfiguration`
- `io.github.rigazilla.memory.cognition.resource.DefaultApiResourceConfiguration`

### Exceptions
- `io.github.rigazilla.memory.cognition.resource.CredentialNotFoundException`

### Modified Files
- `io.github.rigazilla.memory.cognition.process.CognitiveProcess` - Added `getResourceRequirements()`
- `io.github.rigazilla.memory.cognition.process.DurableMemoryExtractionProcess` - Declared LLM resources
- `io.github.rigazilla.memory.cognition.profile.ProfileContextConsolidationProcess` - Declared LLM resource
- `src/main/resources/application.properties` - Added resource configuration section

## Configuration Examples

### Example 1: Process Using Multiple LLMs
```java
@Override
public ResourceRequirements getResourceRequirements() {
    return ResourceRequirements.builder()
        .llm("fast-classifier", fastLlmConfig)
        .llm("accurate-analyzer", accurateLlmConfig)
        .build();
}
```

### Example 2: Process Using External API
```properties
cognition.process.entity-extraction.resources.spacy-api.api.endpoint=http://spacy:8000/ner
cognition.process.entity-extraction.resources.spacy-api.api.timeout=60s
cognition.process.entity-extraction.resources.spacy-api.api.api-key-ref=SPACY_API_KEY
```

### Example 3: Process Using Vector Database
```properties
cognition.process.semantic-search.resources.pgvector.database.connection-string=postgresql://localhost:5432/vectors
cognition.process.semantic-search.resources.pgvector.database.username=cognition_user
cognition.process.semantic-search.resources.pgvector.database.password-ref=PGVECTOR_PASSWORD
cognition.process.semantic-search.resources.pgvector.database.pool-size=20
```

## Design Decisions

### Why Three Levels?
- **Global defaults** - Avoid repetition for common settings
- **Process defaults** - Allow process-wide overrides without naming every resource
- **Named resources** - Enable fine-grained control when needed

### Why Credential References?
- Security: Never commit secrets to version control
- Flexibility: Support multiple secrets providers
- Auditability: Track credential access without exposing values

### Why Named Resources?
- Clarity: Explicit naming makes configuration intent clear
- Flexibility: Multiple resources of same type per process
- Debugging: Easy to identify which resource is being used

### Why Builder Pattern?
- Type safety: Compile-time checking of resource types
- Fluent API: Readable resource declarations
- Extensibility: Easy to add new resource types

## Conclusion

The resource configuration system provides a flexible, secure, and scalable foundation for managing external dependencies in cognitive processes. It supports multiple resource types from day one, maintains minimal configuration through smart defaults, and ensures security through separate credential management.

The system is designed for gradual adoption - existing processes continue to work, and new processes can opt-in to the new system as needed. Future work will focus on dynamic resource instantiation, LangChain4j integration, and additional secrets providers.
