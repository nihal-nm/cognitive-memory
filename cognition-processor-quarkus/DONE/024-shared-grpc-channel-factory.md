# Shared gRPC channel factory

## Summary

Memory-service clients now create authenticated gRPC channels through a single
`GrpcChannelFactory`. The factory owns plaintext channel construction and the canonical lowercase
`x-api-key` and optional `x-client-id` metadata headers.

## Design

The factory provides overloads for clients that require only an API key and clients that also send a
client ID. Authentication is implemented as a channel interceptor, so generated blocking stubs do
not need service-specific credentials or duplicated interceptor classes.

The writer, transcript, profile, job processing, checkpoint, justification, and temporal enrichment
clients retain ownership of their channels and their existing shutdown lifecycle. Only channel
construction and authentication moved to the shared utility.

## Verification

`GrpcChannelFactoryTest` invokes the interceptor through a capturing channel and verifies both the
two-header and API-key-only cases. The repository Checkstyle and SpotBugs gates also cover the new
implementation.
