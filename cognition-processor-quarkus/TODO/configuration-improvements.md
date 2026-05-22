# TODO: Configuration Improvements

**Priority**: MEDIUM  
**Status**: Non-blocking (warnings only)

## Problem

Quarkus logs warnings about unrecognized LangChain4j configuration keys:

```
WARN  [io.quarkus.config] Unrecognized configuration key "quarkus.langchain4j.memory.chat-model.temperature"
WARN  [io.quarkus.config] Unrecognized configuration key "quarkus.langchain4j.memory.chat-model.model-id"
WARN  [io.quarkus.config] Unrecognized configuration key "quarkus.langchain4j.memory.chat-model.max-tokens"
WARN  [io.quarkus.config] Unrecognized configuration key "quarkus.langchain4j.topic-summary.chat-model.temperature"
WARN  [io.quarkus.config] Unrecognized configuration key "quarkus.langchain4j.topic-summary.chat-model.model-id"
WARN  [io.quarkus.config] Unrecognized configuration key "quarkus.langchain4j.topic-summary.chat-model.max-tokens"
WARN  [io.quarkus.config] Unrecognized configuration key "quarkus.langchain4j.devui.enabled"
```

## Current Configuration

```properties
# Named model: "memory" - for extraction and verification
quarkus.langchain4j.memory.chat-model.provider=ollama
quarkus.langchain4j.memory.chat-model.model-id=llama3.2
quarkus.langchain4j.memory.chat-model.temperature=0.1
quarkus.langchain4j.memory.chat-model.max-tokens=4096

# Named model: "topic-summary" - for topic summarization
quarkus.langchain4j.topic-summary.chat-model.provider=ollama
quarkus.langchain4j.topic-summary.chat-model.model-id=llama3.2
quarkus.langchain4j.topic-summary.chat-model.temperature=0.3
quarkus.langchain4j.topic-summary.chat-model.max-tokens=2048

# Disable LangChain4j DevUI features (OpenWebUI container)
quarkus.langchain4j.devui.enabled=false
```

## Impact

- LangChain4j services may fall back to default Ollama configuration
- Temperature, model ID, and token limits may not be applied as intended
- Extraction and verification may use different LLM settings than specified

## Investigation Needed

1. **Check Quarkus LangChain4j Documentation**:
   - https://docs.quarkiverse.io/quarkus-langchain4j/dev/
   - Look for named model configuration syntax
   - Verify if `chat-model` is the correct property path

2. **Possible Correct Syntax**:
```properties
# Option A: Direct Ollama model configuration
quarkus.langchain4j.ollama.memory.model-name=llama3.2
quarkus.langchain4j.ollama.memory.temperature=0.1
quarkus.langchain4j.ollama.memory.max-tokens=4096

# Option B: Chat model with provider reference
quarkus.langchain4j.memory.model-name=llama3.2
quarkus.langchain4j.memory.temperature=0.1
quarkus.langchain4j.memory.max-tokens=4096

# Option C: Programmatic configuration
# May need to configure via @ConfigMapping or CDI beans
```

3. **Check Extension Version**:
```bash
# Verify quarkus-langchain4j-ollama version in pom.xml
grep -A 5 "quarkus-langchain4j-ollama" pom.xml

# Check if version supports named models
```

## Solution Steps

1. **Review Quarkus LangChain4j Examples**:
```bash
# Clone Quarkus LangChain4j examples
git clone https://github.com/quarkiverse/quarkus-langchain4j
cd quarkus-langchain4j/samples

# Look for Ollama configuration examples
grep -r "ollama" --include="*.properties" .
```

2. **Test Different Configuration Patterns**:
```properties
# Try simplified configuration first
quarkus.langchain4j.ollama.chat-model.model-name=llama3.2
quarkus.langchain4j.ollama.chat-model.temperature=0.1
```

3. **Verify Configuration is Applied**:
```java
// Add debug logging to DurableMemoryExtractor
@Inject
ChatLanguageModel model;

@PostConstruct
void logConfig() {
    LOG.infof("LangChain4j model configuration: %s", model.getClass().getName());
    // Check if temperature/model settings are applied
}
```

4. **Update application.properties** with correct syntax

5. **Remove DevUI Warning**:
   - The `quarkus.langchain4j.devui.enabled=false` warning may be expected
   - Verify if this property exists in the extension
   - If not, rely on the pom.xml exclusion only

## Testing

After fixing configuration:

```bash
# Restart application
mvn quarkus:dev

# Verify no warnings in startup logs
grep "Unrecognized configuration" /tmp/quarkus-startup-final.log

# Test LLM calls work correctly
# Create test conversation and verify extraction/verification behavior
```

## References

- Quarkus LangChain4j Documentation: https://docs.quarkiverse.io/quarkus-langchain4j/dev/
- Quarkus LangChain4j GitHub: https://github.com/quarkiverse/quarkus-langchain4j
- Ollama Configuration: https://docs.quarkiverse.io/quarkus-langchain4j/dev/ollama.html
