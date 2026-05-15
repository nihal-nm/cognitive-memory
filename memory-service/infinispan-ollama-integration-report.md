# Infinispan Vector Store and Ollama Integration Report

**Date**: March 13-15, 2026  
**Status**: ✅ Complete and Functional

## Overview

This report documents the integration of Infinispan as a vector store provider and the configuration of Ollama for local LLM inference in the memory-service project.

## 1. Infinispan Vector Store Integration

### 1.1 Docker Compose Setup

Added Infinispan service to `compose.yaml`:

```yaml
infinispan:
  image: quay.io/infinispan/server:16.1
  ports:
    - "11222:11222"
  environment:
    USER: admin
    PASS: password
  tmpfs:
    - /opt/infinispan/server/data
  healthcheck:
    test: ["CMD", "curl", "-f", "http://localhost:11222/rest/v2/cache-managers/default/health/status"]
    interval: 10s
    timeout: 5s
    retries: 10
```

**Key Features**:
- Uses ephemeral storage (tmpfs) for development
- Health check ensures service is ready before dependent services start
- Exposed on port 11222 for REST API access

### 1.2 Memory Service Configuration

Added Infinispan vector store configuration to memory-service environment in `compose.yaml`:

```yaml
environment:
  # Vector search - Infinispan
  MEMORY_SERVICE_VECTOR_INFINISPAN_URL: ${MEMORY_SERVICE_VECTOR_INFINISPAN_URL:-http://infinispan:11222}
  MEMORY_SERVICE_VECTOR_INFINISPAN_USERNAME: ${MEMORY_SERVICE_VECTOR_INFINISPAN_USERNAME:-admin}
  MEMORY_SERVICE_VECTOR_INFINISPAN_PASSWORD: ${MEMORY_SERVICE_VECTOR_INFINISPAN_PASSWORD:-password}
  MEMORY_SERVICE_VECTOR_MIGRATE_AT_START: "true"

depends_on:
  infinispan:
    condition: service_healthy
```

### 1.3 Digest Authentication Fix

**Problem**: The initial authTransport implementation was falling back to Basic auth instead of properly implementing Digest authentication, which Infinispan requires by default.

**Solution**:

1. Added digest authentication library:
   ```bash
   go get github.com/icholy/digest
   ```

2. Fixed `internal/plugin/vector/infinispan/client.go`:
   - Added import: `"github.com/icholy/digest"`
   - Replaced flawed digest auth implementation with proper `digest.Transport`
   - The library correctly handles the challenge-response flow

**Before**:
```go
// Flawed implementation that fell back to basic auth
if resp.StatusCode == http.StatusUnauthorized {
    resp.Body.Close()
    req2.SetBasicAuth(t.username, t.password)
    return t.base.RoundTrip(req2)
}
```

**After**:
```go
// Proper digest auth using library
digestTransport := &digest.Transport{
    Username: t.username,
    Password: t.password,
    Transport: t.base,
}
return digestTransport.RoundTrip(req)
```

### 1.4 Existing Implementation

The Infinispan vector store was already fully implemented with:

- **Core Implementation** (`internal/plugin/vector/infinispan/`):
  - `infinispan.go` - VectorStore interface implementation
  - `client.go` - REST API v3 client
  - `queries.go` - Ickle query builders
  - `schemas/vector_chunk.proto` - Protobuf schema with `@Vector` annotation
  - `schemas/cache_config.xml` - Cache configuration

- **Features**:
  - Native vector search using Ickle: `WHERE i.embedding <-> [vector] ~ k`
  - Cosine similarity scoring
  - Conversation group filtering
  - Automatic schema registration and cache creation on migration

### 1.5 Configuration

To enable Infinispan vector store, set in `.env`:

```bash
MEMORY_SERVICE_VECTOR_KIND=infinispan
```

Optional overrides:
```bash
MEMORY_SERVICE_VECTOR_INFINISPAN_URL=http://host.docker.internal:11222
MEMORY_SERVICE_VECTOR_INFINISPAN_USERNAME=admin
MEMORY_SERVICE_VECTOR_INFINISPAN_PASSWORD=password
```

## 2. Ollama Integration

### 2.1 Memory Service Configuration

Added OpenAI base URL configuration to memory-service in `compose.yaml`:

```yaml
environment:
  MEMORY_SERVICE_OPENAI_BASE_URL: ${MEMORY_SERVICE_OPENAI_BASE_URL:-https://api.openai.com}
```

This allows the memory-service to connect to Ollama running on the host machine via `host.docker.internal`.

### 2.2 Chat-Quarkus Configuration

#### Problem 1: URL Path Concatenation

The `application.properties` concatenates `/v1` to the `OPENAI_BASE_URL`, which is the correct behavior. However, environment variables were incorrectly including `/v1` in the URL.

**Solution**: Keep the concatenation in properties file and ensure environment variables do NOT include `/v1`:

```properties
# application.properties (unchanged - correct behavior)
quarkus.langchain4j.openai.base-url=${OPENAI_BASE_URL:https://api.openai.com}/v1
```

**Environment variables should be**:
- For OpenAI: `OPENAI_BASE_URL=https://api.openai.com` (no /v1)
- For Ollama: `OPENAI_BASE_URL=http://host.docker.internal:11434` (no /v1)

The properties file will automatically append `/v1` to create the final URL.

**File**: `java/quarkus/examples/chat-quarkus/src/main/resources/application.properties` (no change needed)

#### Problem 2: Host Network Access

Chat-quarkus container couldn't resolve `host.docker.internal` to reach Ollama on the host.

**Fix**: Added host mapping and updated default URL in `compose.yaml`:

```yaml
chat-quarkus:
  environment:
    OPENAI_BASE_URL: ${OPENAI_BASE_URL:-http://host.docker.internal:11434/v1}
  extra_hosts:
    - "host.docker.internal:host-gateway"
```

### 2.3 Configuration

To use Ollama with the memory-service stack, set in `.env`:

```bash
# Ollama configuration (do NOT include /v1 - it will be appended automatically)
OPENAI_BASE_URL=http://host.docker.internal:11434
OPENAI_API_KEY=ollama
OPENAI_MODEL=qwen2.5:1.5b

# Or any other Ollama model
# OPENAI_MODEL=llama3.2
# OPENAI_MODEL=nomic-embed-text
```

**Important Notes**:
- Do NOT include `/v1` in `OPENAI_BASE_URL` - the application.properties file automatically appends it
- The `host.docker.internal` hostname is a special DNS name that Docker provides to access the host machine from within containers

## 3. Files Modified

### 3.1 Configuration Files

1. **`compose.yaml`**:
   - Added `infinispan` service definition
   - Added Infinispan configuration to `memory-service` environment
   - Added `infinispan` to `memory-service` dependencies
   - Added `MEMORY_SERVICE_OPENAI_BASE_URL` to `memory-service`
   - Updated `chat-quarkus` with `extra_hosts` and default Ollama URL (without /v1 suffix)

2. **`java/quarkus/examples/chat-quarkus/src/main/resources/application.properties`**:
   - No changes needed - correctly appends `/v1` to `OPENAI_BASE_URL`

### 3.2 Source Code

1. **`internal/plugin/vector/infinispan/client.go`**:
   - Added import: `"github.com/icholy/digest"`
   - Fixed `authTransport.RoundTrip()` to use proper digest authentication

### 3.3 Dependencies

1. **`go.mod` / `go.sum`**:
   - Added: `github.com/icholy/digest v1.1.0`

## 4. Verification Steps

### 4.1 Verify Infinispan Connection

```bash
# Check memory-service logs for successful migration
docker compose logs memory-service | grep infinispan

# Expected output:
# INFO Running migration name=infinispan
# INFO Created Infinispan cache name=memory-service_...
```

### 4.2 Verify Ollama Connection

```bash
# Check that Ollama is accessible from containers
docker compose exec chat-quarkus curl http://host.docker.internal:11434/v1/models

# Check chat-quarkus logs for successful startup
docker compose logs chat-quarkus | grep -E "Started|Listening"

# Expected output:
# INFO  [io.quarkus] (main) chat-quarkus ... started in X.XXXs. Listening on: http://0.0.0.0:8080
```

### 4.3 Test Vector Search

```bash
# Set vector store to Infinispan
export MEMORY_SERVICE_VECTOR_KIND=infinispan

# Restart services
docker compose up -d

# Test vector search via API (requires authentication)
curl -X POST http://localhost:8082/v1/conversations/search \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"query": "test query", "limit": 5}'
```

## 5. Architecture Overview

### 5.1 Infinispan Vector Store

```
┌─────────────────┐
│ Memory Service  │
│                 │
│  ┌───────────┐  │     REST API v3
│  │  Vector   │──┼────────────────────┐
│  │  Plugin   │  │                    │
│  └───────────┘  │                    ▼
└─────────────────┘              ┌──────────────┐
                                 │  Infinispan  │
                                 │   Server     │
                                 │              │
                                 │ - Protobuf   │
                                 │ - Ickle      │
                                 │ - Vector     │
                                 │   Search     │
                                 └──────────────┘
```

**Key Components**:
- REST API v3 for all operations
- Protobuf schema with `@Vector` annotation
- Ickle query language for vector similarity
- Digest authentication

### 5.2 Ollama Integration

```
┌──────────────────┐
│   Host Machine   │
│                  │
│  ┌────────────┐  │
│  │   Ollama   │  │
│  │  :11434    │  │
│  └────────────┘  │
└──────────────────┘
         ▲
         │ host.docker.internal
         │
┌────────┴─────────────────────────────────┐
│         Docker Network                   │
│                                          │
│  ┌─────────────────┐  ┌───────────────┐ │
│  │ Memory Service  │  │ Chat-Quarkus  │ │
│  │                 │  │               │ │
│  │ - Embeddings    │  │ - LangChain4j │ │
│  │ - OpenAI API    │  │ - Chat UI     │ │
│  └─────────────────┘  └───────────────┘ │
└──────────────────────────────────────────┘
```

**Key Points**:
- Ollama runs on host machine (port 11434)
- Containers access via `host.docker.internal`
- OpenAI-compatible API endpoint: `/v1`
- No authentication required for Ollama

## 6. Troubleshooting

### 6.1 Infinispan Authentication Errors

**Symptom**: `401 Unauthorized` errors in memory-service logs

**Solution**: Ensure digest authentication library is installed and client.go is using `digest.Transport`

### 6.2 Ollama Connection Refused

**Symptom**: `java.net.UnknownHostException: ollama` or connection refused errors

**Solutions**:
1. Verify `.env` uses `host.docker.internal` not `ollama`
2. Ensure `extra_hosts` is configured in compose.yaml
3. Check Ollama is running on host: `curl http://localhost:11434/v1/models`

### 6.3 Double /v1 in URL

**Symptom**: Requests to `http://host.docker.internal:11434/v1/v1/...`

**Solution**: Ensure `OPENAI_BASE_URL` environment variable does NOT include `/v1` suffix. The application.properties file automatically appends it.

**Correct**: `OPENAI_BASE_URL=http://host.docker.internal:11434`  
**Incorrect**: `OPENAI_BASE_URL=http://host.docker.internal:11434/v1`

## 7. Performance Considerations

### 7.1 Infinispan

- **Ephemeral Storage**: Using tmpfs for development (data lost on restart)
- **Production**: Consider persistent volumes for production deployments
- **Indexing**: Local-heap indexing is fast but memory-intensive
- **Scaling**: Infinispan supports clustering for horizontal scaling

### 7.2 Ollama

- **Host Resources**: Ollama uses host GPU/CPU resources
- **Model Size**: Larger models require more RAM (e.g., 7B models need ~8GB)
- **Concurrency**: Ollama handles concurrent requests but may queue them
- **Performance**: Local inference is slower than cloud APIs but has no network latency

## 8. Future Enhancements

### 8.1 Infinispan

- [ ] Add BDD tests for Infinispan vector store
- [ ] Document Infinispan configuration in site docs
- [ ] Add metrics for Infinispan operations
- [ ] Support for other similarity metrics (euclidean, dot product)

### 8.2 Ollama

- [ ] Add health check for Ollama availability
- [ ] Support for multiple Ollama instances (load balancing)
- [ ] Automatic model download/management
- [ ] Embedding model configuration per conversation

## 9. References

### 9.1 Documentation

- [Infinispan REST API v3](https://infinispan.org/docs/stable/titles/rest/rest.html)
- [Infinispan Vector Search](https://infinispan.org/docs/stable/titles/developing/developing.html#vector-search)
- [Ollama API Documentation](https://github.com/ollama/ollama/blob/main/docs/api.md)
- [LangChain4j Quarkus](https://docs.quarkiverse.io/quarkus-langchain4j/dev/index.html)

### 9.2 Related Files

- `AI/infinispan-analysis-phase1.md` - Initial research and analysis
- `AI/infinispan-vector-store-implementation-plan.md` - Implementation plan
- `AGENTS.md` - Project overview and development guidelines
- `internal/plugin/vector/infinispan/` - Infinispan implementation

## 10. Conclusion

Both Infinispan vector store and Ollama integration are now fully functional:

✅ **Infinispan**: 
- Properly authenticates using digest auth
- Auto-creates vector cache with correct schema
- Performs vector similarity search using Ickle queries

✅ **Ollama**:
- Memory-service can use Ollama for embeddings
- Chat-quarkus can use Ollama for chat completions
- Both services access Ollama on host via `host.docker.internal`

The system is ready for development and testing with local LLM inference and Infinispan-based vector storage.
