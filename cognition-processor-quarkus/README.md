# Cognition Processor - Quarkus Implementation

Reference implementation of the Memory Service Cognition Processor as described in [Enhancement 099](https://github.com/chirino/memory-service/blob/main/docs/enhancements/099-quarkus-cognition-processor.md).

## Overview

This is a standalone Quarkus application that implements the cognition layer for Memory Service:
- Consumes substrate events via gRPC
- Extracts durable and cache-only memories using LangChain4j
- Implements multi-stage pipeline: Extract → Verify → Consolidate
- Writes derived memories back to Memory Service substrate

## Architecture

```
Memory Service Events → Debounce → Evidence Packs → 
Extraction (LangChain4j) → Verification → Consolidation → 
Memory Storage (gRPC)
```

## Memory Types

### Durable Memories
- **fact** - Stable user/project facts
- **preference** - Repeated user preferences
- **procedure** - Reusable workflows
- **problem_solution** - Issue-resolution patterns
- **decision** - Decision rules/criteria

### Cache-Only Memories
- **bridge** - Current focus/goals (retrieval aid)
- **topic** - Recent themes (retrieval aid)
- **summary** - Conversation summaries (rolling cache)

## Technology Stack

- **Quarkus 3.17.5** - Application framework
- **LangChain4j** - LLM abstraction and structured extraction
- **OpenAI** - Default LLM provider (configurable)
- **Micrometer + Prometheus** - Metrics and observability
- **YAML Config** - Configuration management

## Project Structure

```
cognition-processor-quarkus/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── io/github/rigazilla/memory/
│   │   │       └── cognition/
│   │   │           ├── pipeline/      # Event processing pipeline
│   │   │           ├── evidence/      # Evidence pack builder
│   │   │           ├── extraction/    # LangChain4j extractors
│   │   │           ├── verification/  # Citation verification
│   │   │           ├── consolidation/ # Memory consolidation
│   │   │           └── storage/       # gRPC memory storage
│   │   └── resources/
│   │       ├── application.yml        # Configuration
│   │       └── prompts/               # LLM prompts
│   └── test/
│       └── java/
└── pom.xml
```

## Configuration

Key configuration properties (see `application.yml`):

```yaml
# Identity
cognition:
  worker-id: worker-1
  runtime-id: cognition-processor-v1

# Evidence bounds
evidence:
  base:
    max-tokens: 6000
  delta:
    max-entries: 12

# Debouncing
scheduler:
  debounce-delay: PT1M
  max-batch-entries: 24

# LangChain4j / OpenAI
quarkus:
  langchain4j:
    openai:
      api-key: ${OPENAI_API_KEY}
      memory:
        chat-model:
          model-name: gpt-4o-mini
```

## Development

### Prerequisites
- Java 21+
- Maven 3.9+
- Memory Service running (for integration)
- OpenAI API key (or alternative LLM provider)

### Run in Dev Mode
```bash
./mvnw quarkus:dev
```

### Build
```bash
./mvnw clean package
```

### Run Tests
```bash
./mvnw test
```

## Dependencies

### Hard Prerequisites
- Enhancement 101: gRPC API parity (event stream, checkpoints, on-behalf-of policy)
- Enhancement 100: Enhanced memory search (retrieval contract)

### Soft Integration
- Enhancement 090: Adaptive knowledge clustering (optional evidence source)
- Enhancement 091: Skill extraction (downstream consumer of procedures)

## Implementation Status

- [ ] Event processing pipeline
- [ ] Debouncing scheduler
- [ ] Evidence pack builder
- [ ] LangChain4j extractors (DurableMemoryExtractor, TopicSummaryExtractor)
- [ ] Citation verifier
- [ ] Memory consolidator
- [ ] gRPC storage client
- [ ] Checkpoint persistence
- [ ] Metrics and observability
- [ ] Integration tests

## References

- [Enhancement 099: Quarkus + LangChain4j Cognition Processor](https://github.com/chirino/memory-service/blob/main/docs/enhancements/099-quarkus-cognition-processor.md)
- [Memory Cognition Architecture](https://github.com/chirino/memory-service/blob/main/docs/memory-cognition.md)
- [Memory Service Documentation](https://chirino.github.io/memory-service/)
- [LangChain4j Documentation](https://docs.langchain4j.dev/)

## License

Apache License 2.0