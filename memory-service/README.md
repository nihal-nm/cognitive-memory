# Memory Service Documentation Index

Comprehensive guides explaining data flow, processing, access control, and vector store integration.

---

## Core Documentation

Architecture, data flow, and access control guides created May 2026.

### [chat-to-memory-flow.md](chat-to-memory-flow.md)
Overview of how user conversations flow from frontend applications through agent apps to memory service storage, including data transformation at each layer.

### [mcp-to-storage-flow.md](mcp-to-storage-flow.md)
Detailed explanation of MCP server data flow: who processes information, when LLMs are used, and how data flows bidirectionally between Claude Code and memory storage.

### [identity-and-access-control.md](identity-and-access-control.md)
Complete guide to authentication, user isolation, access levels, and privacy model. Explains how identity flows from AI assistants through memory service with conversation groups and sharing.

---

## Infinispan Vector Store Documentation

Implementation planning and integration reports for Infinispan vector search provider.

### [infinispan-vector-store-implementation-plan.md](infinispan-vector-store-implementation-plan.md)
Phase-by-phase implementation plan for Infinispan vector store provider. Covers research, design decisions, protobuf schemas, REST client, and query patterns with vector similarity operators.

### [infinispan-analysis-phase1.md](infinispan-analysis-phase1.md)
Analysis of llama-stack Infinispan implementation. Documents native vector search capabilities using protobuf annotations, Ickle query language with KNN search, and cosine similarity scoring.

### [infinispan-ollama-integration-report.md](infinispan-ollama-integration-report.md)
Complete integration report for Infinispan vector store and Ollama local LLM setup. Covers Docker Compose configuration, health checks, and memory service environment variables.

---

## Quick Reference

### For Understanding the System
- **Overall architecture**: Start with `chat-to-memory-flow.md`
- **MCP integration**: See `mcp-to-storage-flow.md`
- **Security & access**: Read `identity-and-access-control.md`

### For Infinispan Development
- **Implementation planning**: Start with `infinispan-vector-store-implementation-plan.md`
- **Vector search details**: See `infinispan-analysis-phase1.md`
- **Setup & configuration**: Check `infinispan-ollama-integration-report.md`

---

## Document Features

All core documentation includes:
- ASCII flow diagrams
- Source code references with file paths and line numbers
- Step-by-step explanations
- Examples and use cases

Infinispan documentation includes:
- Protobuf schema templates
- Ickle query examples
- Docker Compose configurations
- REST API patterns
