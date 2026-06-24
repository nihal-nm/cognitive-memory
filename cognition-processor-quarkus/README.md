# Cognition Processor - Quarkus Implementation

A Quarkus-based implementation of the Memory Service cognition layer that processes conversation events to extract and organize memories.

## What is this?

This project implements the **cognition layer** of a two-layer memory architecture:
- **Substrate layer** ([memory-service](https://github.com/chirino/memory-service)) - stores raw conversation data and manages access control
- **Cognition layer** (this project) - processes events to extract topics, facts, preferences, and other derived memories

Think of it as the "intelligence" that turns raw conversation transcripts into structured, searchable memories.

## Quick Start

### Prerequisites
- Java 21+
- Maven 3.9+
- Memory Service running - see [../memory-service](../memory-service) for local setup instructions

### Run It

1. **Start the cognition processor**:
   ```bash
   ./mvnw quarkus:dev
   ```

2. **Verify it's running**:
   - Check logs for "Started AdminEventClient - connected to memory-service"
   - Health checks:
     - Liveness: http://localhost:8090/q/health/live
     - Readiness: http://localhost:8090/q/health/ready (includes gRPC connection status)
     - Full health: http://localhost:8090/q/health

The processor will automatically:
- Subscribe to memory-service events
- Process new conversations
- Extract and verify memories
- Write them back to memory-service

## How It Works

```
Memory Service Events → Debounce → Evidence Packs → 
Extract (LLM) → Verify → Write Memories
```

See [AGENTS.md](./AGENTS.md) for architecture details and [Enhancement 099](https://github.com/chirino/memory-service/blob/main/docs/enhancements/099-quarkus-cognition-processor.md) for full specification.

## Configuration

Default configuration works with local development setup. Key settings in `src/main/resources/application.properties`:
- Memory Service connection: `memory-service.grpc.host` / `memory-service.grpc.port`
- LLM provider: `quarkus.langchain4j.*.chat-model.provider` (default: Ollama)
- Models: `memory` model for extraction/verification, `topic-summary` for summaries

Environment variable examples:
- `.env.example` for local host-based runs
- `.env.docker.example` for Docker-based dev/test runs

## Docker Dev/Test

A JVM-based Docker setup is included for local integration testing.

### Prerequisites
- Docker
- Memory Service running on the host at `http://localhost:8082`
- Optional Ollama running on the host at `http://localhost:11434` if you use Ollama-backed models

### Build the application
```bash
./mvnw package
```

### Configure Docker runtime
```bash
cp .env.docker.example .env.docker
```

Then edit `.env.docker` as needed:
- set `OPENAI_API_KEY` if using the default OpenAI-compatible memory model configuration
- or switch the memory model provider to Ollama using the commented examples in `.env.docker.example`

### Start the container
```bash
docker compose up --build
```

The containerized cognition processor will be available on:
- `http://localhost:8090`

### Logs
Container logs:
```bash
docker compose logs -f cognition-processor
```

Persisted Quarkus log file:
```bash
tail -f logs/quarkus.log
```

### Notes
- The compose file maps `host.docker.internal` so the container can reach host services on Linux
- The container expects Memory Service on `host.docker.internal:8082`
- The compose file mounts `./logs` to `/deployments/logs` so file logging remains available

## Project Status

**Currently Implemented** (see [DONE/](./DONE/) folder for details):
- ✅ gRPC event stream client
- ✅ Debounce windows and batching
- ✅ Job processing pipeline
- ✅ Memory extraction (5 types: fact, preference, procedure, problem_solution, decision)
- ✅ Citation verification
- ✅ Provenance tracking

**Not Yet Implemented** (see [TODO/](./TODO/) folder):
- ❌ Memory consolidation (deduplication, merging)
- ❌ Topic summary extraction
- ❌ Cache-only notes (bridge, topic)
- ❌ Prompt caching optimization

## For New Contributors

- 📖 Start with [AGENTS.md](./AGENTS.md) for project context
- 📝 Browse [TODO/](./TODO/) folder for work items
- ✅ Check [DONE/](./DONE/) folder to understand what's completed

## Learn More

### About This Project
- [AGENTS.md](./AGENTS.md) - Project overview and guidelines for AI assistants
- [Enhancement 099](https://github.com/chirino/memory-service/blob/main/docs/enhancements/099-quarkus-cognition-processor.md) - Implementation specification
- [TODO/gap-analysis-model-backed-extraction.md](./TODO/gap-analysis-model-backed-extraction.md) - Current vs spec comparison

### About Memory Service
- [Memory Service Repository](https://github.com/chirino/memory-service)
- [Core Concepts](https://chirino.github.io/memory-service/docs/concepts/) - Conversations, entries, memories, access control
- [Quarkus Guide](https://chirino.github.io/memory-service/docs/quarkus/) - Quarkus integration patterns
- [Memory Cognition Architecture](https://github.com/chirino/memory-service/blob/main/docs/memory-cognition.md) - Two-layer design

### Technologies
- [Quarkus](https://quarkus.io/) - Application framework
- [LangChain4j](https://docs.langchain4j.dev/) - LLM integration
- [Ollama](https://ollama.ai) - Local LLM runtime

## License

Apache License 2.0