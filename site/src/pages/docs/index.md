---
layout: ../../layouts/DocsLayout.astro
title: Overview
---

The Cognitive Memory processor is an intelligent cognition layer that sits between AI agents and [Memory Service](https://github.com/chirino/memory-service). It automatically extracts salient memories from conversations and stores them back with full provenance.

## Architecture

```
Agent  -->  Memory Service  -->  Events  -->  Cognition Processor
                ^                                    |
                |                                    |
                +-------- Extracted Memories ---------+
```

1. Agents interact with users through Memory Service conversations
2. Memory Service emits `entry.created` events via gRPC stream
3. The cognition processor receives events, buffers them in debounce windows, and extracts structured memories using an LLM
4. Extracted memories are written back to Memory Service with citations and provenance

## Memory Types

The processor extracts five types of cognitive memories:

| Type | Description | Example |
|------|-------------|---------|
| **Fact** | Objective information about the user | "Works at Acme Corp as a senior engineer" |
| **Preference** | User likes, dislikes, or choices | "Prefers Go over Rust" |
| **Decision** | Choices the user has made | "Decided to use PostgreSQL for the project" |
| **Procedure** | How-to knowledge or workflows | "Deploys via CI/CD pipeline with staging gate" |
| **Problem/Solution** | Issues encountered and their fixes | "Fixed OOM by increasing heap to 4GB" |

## API Surface

The cognition processor exposes REST APIs for management and inspection. These are **not** data ingestion endpoints — all conversation data flows through Memory Service.

| API | Base Path | Purpose |
|-----|-----------|---------|
| Process Management | `/api/processes` | Control and inspect cognitive processes |
| Event Status | `/api/events` | Monitor event stream and window status |
| Profile Consolidation | `/api/consolidate` | Trigger user profile consolidation |
| Memory Justification | `/api/memories` | Get memory with full provenance |
| Health & Metrics | `/q/health`, `/q/metrics` | Liveness, readiness, Prometheus metrics |

See the full [API Reference](/docs/api/) for details, request/response schemas, and example `curl` commands.

## Data Flow

Data enters the system exclusively through Memory Service. To inject information for processing:

1. Create a conversation via Memory Service API
2. Add entries to the conversation
3. Memory Service emits events automatically
4. The cognition processor picks them up and extracts memories

There is no way to bypass this flow — the processor is a passive listener by design.
