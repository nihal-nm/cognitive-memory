# Cognitive Memory

A Quarkus-based cognition layer that processes conversation events from [memory-service](https://github.com/chirino/memory-service) to extract and organize structured memories (topics, facts, preferences, etc.).

## Architecture

- **Memory Service** (substrate layer) - Stores raw conversation data, manages access control
- **Cognition Processor** (this project) - Processes events to extract derived memories with LLMs

See [cognition-processor-quarkus/AGENTS.md](cognition-processor-quarkus/AGENTS.md) for project overview and [Enhancement 099](https://github.com/chirino/memory-service/blob/main/docs/enhancements/099-quarkus-cognition-processor.md) for implementation specification.

## Quick Start

### Option 1: AI-Assisted Setup (Recommended)

Start your AI assistant (Claude Code, Cursor, etc.) in this repository and type:

```
Please read the AGENTS.md and quickstart the application
```

The AI will guide you through the interactive setup process.

### Option 2: Manual Setup

#### 1. Memory Service

Set up and run memory-service locally:

→ [memory-service/README.md](memory-service/README.md)

#### 2. Cognition Processor

Run the Quarkus cognition processor:

→ [cognition-processor-quarkus/README.md](cognition-processor-quarkus/README.md)

## Learn More

- [cognition-processor-quarkus/AGENTS.md](cognition-processor-quarkus/AGENTS.md) - Project context and guidelines
- [cognition-processor-quarkus/TODO/](cognition-processor-quarkus/TODO/) - Future work items
- [cognition-processor-quarkus/DONE/](cognition-processor-quarkus/DONE/) - Completed features
- [Memory Service Docs](https://chirino.github.io/memory-service/)

## License

Apache License 2.0
