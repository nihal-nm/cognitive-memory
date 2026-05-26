# Research memory-service Quarkus Extension

## Description

Investigate the official memory-service Quarkus extension as a potential alternative to the current direct gRPC integration approach.

## Context

The memory-service project provides a Quarkus extension that integrates with LangChain4j-based AI agents. Currently, this project uses direct gRPC calls to communicate with memory-service APIs.

## Potential Benefits

- Simplified integration with memory-service APIs
- Built-in Dev Services for automatic container provisioning during development
- Alignment with recommended memory-service integration patterns
- Possible reduction in boilerplate code

## Current Status

The current gRPC approach is working well. This is a research task to evaluate whether switching would provide meaningful benefits given the cognition processor's specific use case.

## References

- https://chirino.github.io/memory-service/docs/quarkus/
- memory-service source: https://github.com/chirino/memory-service
