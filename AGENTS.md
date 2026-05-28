# AGENTS.md - AI Assistant Guide

## Working Directory

The primary working directory for development is **`cognition-processor-quarkus/`**.

For detailed development guidelines, architecture, and implementation context, see:
→ [cognition-processor-quarkus/AGENTS.md](cognition-processor-quarkus/AGENTS.md)

## Quick Start Information

When helping users get started with the project, follow the interactive two-step setup process:

### Step 1: Memory Service Setup

Before starting, **ask the user**:
1. **Do you already have memory-service cloned locally?**
   - If yes: Ask for the path to their existing clone
   - If no: Ask where they want to clone it (suggest a location like `~/git/memory-service` or `~/tmp/memory-service`)

2. **How do you want to run Ollama?**
   - Option A: Native Ollama (installed on host)
   - Option B: Docker Ollama (in a container)

Then follow the setup instructions in:
→ [memory-service/README.md](memory-service/README.md)

This folder contains:
- `compose.override.yaml.example` - Docker Compose override for connecting to host Ollama
- `.env.example` - Environment variables for memory-service configuration
- Complete setup instructions for both Ollama options

### Step 2: Cognition Processor Setup

After memory-service is running, start the cognition processor.

Setup instructions:
→ [cognition-processor-quarkus/README.md](cognition-processor-quarkus/README.md)

The cognition processor connects to memory-service via gRPC (port 8082) and runs on port 8090.

### Accessing the Memory Service Chat UI

Once memory-service is running, users can access the chat interface at http://localhost:8080

**Available test users:**
- `alice` / `alice`
- `bob` / `bob`
- `charlie` / `charlie`

### Testing the Complete Flow

Explain to the user what the system does:

1. **Have a conversation** in the chat UI at http://localhost:8080
2. **Cognition processor automatically extracts** salient memories (facts, preferences, decisions, etc.)
3. **Memories are stored** back into memory-service with full provenance

**Check extracted memories:**
```bash
curl -X POST http://localhost:8082/v1/memories/search \
  -H "Authorization: Bearer cognition-processor-key-123" \
  -H "X-API-Key: cognition-processor-key-123" \
  -H "Content-Type: application/json" \
  -d '{"namespace_prefix": ["user"]}'
```

Initially returns `{"items":[]}`, but after conversations with salient content, extracted memories will appear.

### Viewing Logs After Setup

Once both services are running, show the user how to view logs:

**Memory Service logs:**
```bash
cd /path/to/memory-service
docker compose logs -f memory-service
```

**Cognition Processor logs:**
```bash
# View the persisted log file (recommended):
tail -f cognition-processor-quarkus/logs/quarkus.log

# Or from within the cognition-processor-quarkus directory:
cd cognition-processor-quarkus
tail -f logs/quarkus.log
```

Note: The cognition processor logs to `logs/quarkus.log` as configured in `application.properties`. This persisted log file is the best way to monitor the processor's activity.

## Repository Structure

- **`cognition-processor-quarkus/`** - Active Quarkus implementation (main development focus)
- **`memory-service/`** - Quick start configuration for memory-service
- **`quarkus-cognitive-memory/`** - Alternative/older implementation

## Documentation

- Root README: [README.md](README.md) - Project overview and quick start links
- Development docs: [cognition-processor-quarkus/AGENTS.md](cognition-processor-quarkus/AGENTS.md)
- Implementation spec: [Enhancement 099](https://github.com/chirino/memory-service/blob/main/docs/enhancements/099-quarkus-cognition-processor.md)
