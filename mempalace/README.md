# MemPalace Architecture Documentation Index

## Overview

This index provides quick reference to MemPalace's architecture documentation files.

---

## Files

### [ARCHITECTURE_MEMORY_FLOW.md](ARCHITECTURE_MEMORY_FLOW.md)
Complete flow from user conversation with Claude Code through normalization, chunking, and storage. Covers hook triggers, conversation mining, exchange-based chunking, and ChromaDB storage with verbatim preservation guarantees.

### [ARCHITECTURE_MCP_INTERACTION.md](ARCHITECTURE_MCP_INTERACTION.md)
AI Agent interaction with MemPalace via MCP server. Documents all 30+ JSON-RPC tools, read/write workflows, security mechanisms (sanitization, WAL, parameter whitelisting), caching strategies, and multi-tool orchestration patterns.

### [ARCHITECTURE_LLM_USAGE.md](ARCHITECTURE_LLM_USAGE.md)
Optional LLM (gemma4:e4b) usage for corpus origin detection, entity refinement, and closet generation. Includes provider configuration, performance benchmarks, privacy warnings, and local-first philosophy with external API opt-in.

### [ARCHITECTURE_EMBEDDING_VS_LLM.md](ARCHITECTURE_EMBEDDING_VS_LLM.md)
Critical distinction between embedding model (all-MiniLM-L6-v2, required, 90MB, ONNX Runtime) and LLM (gemma4:e4b, optional, 2.8GB, Ollama). Clarifies their different purposes: text-to-vector conversion versus classification/reasoning.

### [ARCHITECTURE_IDENTITY_AUTH.md](ARCHITECTURE_IDENTITY_AUTH.md)
Identity and authorization propagation from user through Claude Code to storage. Documents single-user trust model, provenance tracking (added_by, filed_at), WAL audit trail, filesystem permissions, attack surfaces, and privacy implications.

---

## Reading Order

**For new contributors:**
1. Start with [ARCHITECTURE_MEMORY_FLOW.md](ARCHITECTURE_MEMORY_FLOW.md) - understand the complete data pipeline
2. Read [ARCHITECTURE_MCP_INTERACTION.md](ARCHITECTURE_MCP_INTERACTION.md) - learn how AI agents interact with storage
3. Review [ARCHITECTURE_EMBEDDING_VS_LLM.md](ARCHITECTURE_EMBEDDING_VS_LLM.md) - clarify the two-model architecture
4. Read [ARCHITECTURE_LLM_USAGE.md](ARCHITECTURE_LLM_USAGE.md) - understand optional LLM features
5. Study [ARCHITECTURE_IDENTITY_AUTH.md](ARCHITECTURE_IDENTITY_AUTH.md) - grasp security and provenance model

**For MCP tool developers:**
1. [ARCHITECTURE_MCP_INTERACTION.md](ARCHITECTURE_MCP_INTERACTION.md) (all tools documented)
2. [ARCHITECTURE_IDENTITY_AUTH.md](ARCHITECTURE_IDENTITY_AUTH.md) (security constraints)
3. [ARCHITECTURE_MEMORY_FLOW.md](ARCHITECTURE_MEMORY_FLOW.md) (storage format)

**For search/retrieval optimization:**
1. [ARCHITECTURE_EMBEDDING_VS_LLM.md](ARCHITECTURE_EMBEDDING_VS_LLM.md) (embedding model details)
2. [ARCHITECTURE_MCP_INTERACTION.md](ARCHITECTURE_MCP_INTERACTION.md) (search tool parameters)
3. [ARCHITECTURE_MEMORY_FLOW.md](ARCHITECTURE_MEMORY_FLOW.md) (hybrid BM25 + HNSW strategy)

**For privacy/security audit:**
1. [ARCHITECTURE_IDENTITY_AUTH.md](ARCHITECTURE_IDENTITY_AUTH.md) (complete security model)
2. [ARCHITECTURE_LLM_USAGE.md](ARCHITECTURE_LLM_USAGE.md) (external API warnings)
3. [ARCHITECTURE_MCP_INTERACTION.md](ARCHITECTURE_MCP_INTERACTION.md) (input sanitization)
