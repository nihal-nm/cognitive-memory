# AGENTS.md - AI Assistant Guide

## What is this project

This project implements the **cognition layer** of a two-layer memory architecture. The substrate layer (memory-service) handles durable storage, access control, and governance of raw conversation entries. This cognition layer interprets and organizes those entries into useful memory products like topics, summaries, and extracted facts. It runs as an event-driven system that subscribes to memory-service events, processes conversations asynchronously, and creates derived memories while preserving provenance and access control from the source data.

## Relation with memory-service

**Memory-service** is the substrate - the source of truth for raw conversation data, entries, and access control. This project has two components:

1. **Listener** (`io.github.rigazilla.memory.listener`) - **NOT the focus of this project.** This is a thin adaptation layer that bridges the reference memory-service implementation with the cognitive layer. It subscribes to memory-service event streams (SSE), receives conversation/entry events, and forwards them to the cognitive layer via REST API calls. It exists only to adapt events into HTTP requests.

2. **Cognitive** (`io.github.rigazilla.memory.cognitive`) - **The core focus of this project.** Business logic that processes events to extract topics, detect patterns, and create derived memories. It calls back to memory-service APIs to read full conversation context and can be deployed as a standalone service. This is where the actual cognition work happens.

## Goals

From the [Memory Cognition Architecture](https://github.com/chirino/memory-service/blob/main/docs/memory-cognition.md#goals):

1. **Preserve the substrate** - Keep current memory-service as the durable source of truth for raw entries, memories, access control, and lifecycle management

2. **Enable pluggability** - Allow multiple cognition implementations to run side-by-side for benchmarking and comparison

3. **Maintain asynchronous processing** - Keep cognition event-driven so extraction and consolidation don't block the agent's critical path

4. **Enforce governance** - Preserve provenance, scope, and access control for all derived memories

5. **Support flexible deployment** - Allow both external-process cognition runtimes (default) and optional embedded runtimes for low-latency scenarios

6. **Enable reproducibility** - Make cognition outputs replayable and rebuildable from substrate data and event history

## Non-Goals

From the [Memory Cognition Architecture](https://github.com/chirino/memory-service/blob/main/docs/memory-cognition.md#non-goals):

1. **Replacing substrate APIs** - Not replacing conversation, `context`, or `/v1/memories` APIs

2. **Hard-coding strategies** - Not locking into one cognition strategy, LLM provider, or prompt format

3. **Centralizing reasoning** - Not moving all reasoning into the memory-service process

4. **Bypassing governance** - Not treating cognition outputs as ungoverned side data that bypasses access control

## Whole picture

For a general description of the whole project see https://github.com/chirino/memory-service/blob/main/docs/memory-cognition.md. It's a very high level doc.

## Implementation guidelines

There are guidelines for the implementation at https://github.com/chirino/memory-service/blob/main/docs/enhancements/099-quarkus-cognition-processor.md. It's strongly reccomended to follow them when writing new code, but it's not mandatory. You must inform the user if you plan to not follow the guidelines.

## Documentation

- **Core concepts**: https://chirino.github.io/memory-service/docs/concepts/ - Essential memory-service concepts (conversations, entries, memories, access control) that the cognition layer builds upon
- **Quarkus implementation**: https://chirino.github.io/memory-service/docs/quarkus/ - Quarkus-specific information and patterns for this implementation

## Progress tracking

When a new feature is implemented and complete a design description document must be created in the DONE folder. Files there must be prefixed with "NNN-" where NNN is an increasing number.

Future work items, research tasks, and ideas are tracked in the TODO folder as individual markdown files.