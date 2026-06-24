# 020 - Docker Compose Setup

**Status**: ✅ Complete  
**Date**: 2026-06-24

## Overview

Added complete Docker Compose configuration for local development and testing of the cognition processor. Includes JVM-based containerization, environment configuration templates, and integration with host services (memory-service and Ollama).

## Changes Made

### 1. Created Docker Compose Configuration

**File**: `docker-compose.yml`

Complete service definition for the cognition processor:

```yaml
services:
  cognition-processor:
    container_name: cognition-processor
    build:
      context: .
      dockerfile: src/main/docker/Dockerfile.jvm
    image: cognitive-memory/cognition-processor-quarkus:dev
    ports:
      - "8090:8090"
    env_file:
      - .env.docker
    environment:
      JAVA_OPTS_APPEND: >-
        -Dquarkus.http.host=0.0.0.0
        -Djava.util.logging.manager=org.jboss.logmanager.LogManager
      QUARKUS_HTTP_PORT: "8090"
      MEMORY_SERVICE_GRPC_HOST: host.docker.internal
      MEMORY_SERVICE_GRPC_PORT: "8082"
      QUARKUS_LANGCHAIN4J_OLLAMA_BASE_URL: http://host.docker.internal:11434
      QUARKUS_LOG_FILE_PATH: /deployments/logs/quarkus.log
    extra_hosts:
      - "host.docker.internal:host-gateway"
    volumes:
      - ./logs:/deployments/logs
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8090/q/health/ready"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 40s
```

**Key Features:**
- **JVM-based build**: Uses `Dockerfile.jvm` for faster development iteration
- **Host connectivity**: Maps `host.docker.internal` for Linux compatibility
- **Port mapping**: Exposes 8090 for HTTP/health endpoints
- **Log persistence**: Mounts `./logs` directory for persistent logging
- **Auto-restart**: `unless-stopped` policy for resilience
- **Health monitoring**: Integrated health checks (see DONE/019)

### 2. Created Docker Environment Template

**File**: `.env.docker.example`

Comprehensive environment configuration template with:

**Memory Service Connection:**
```bash
MEMORY_SERVICE_GRPC_HOST=host.docker.internal
MEMORY_SERVICE_GRPC_PORT=8082
MEMORY_SERVICE_API_KEY=cognition-processor-key-123
MEMORY_SERVICE_CLIENT_ID=cognition_processor
```

**Worker Identity:**
```bash
COGNITION_WORKER_ID=cognition_processor
COGNITION_RUNTIME_ID=cognition-processor-v1
COGNITION_RUNTIME_VERSION=1.0.0-SNAPSHOT
COGNITION_CHECKPOINT_RESET_ON_STARTUP=false
```

**Logging Configuration:**
```bash
QUARKUS_HTTP_PORT=8090
QUARKUS_LOG_LEVEL=INFO
QUARKUS_LOG_CATEGORY__IO_GITHUB_RIGAZILLA_MEMORY__LEVEL=DEBUG
QUARKUS_LOG_FILE_ENABLE=true
QUARKUS_LOG_FILE_PATH=/deployments/logs/quarkus.log
```

**LLM Provider Options:**
- Ollama (default): `http://host.docker.internal:11434`
- OpenAI-compatible endpoints
- Google Gemini

**Two-tier LLM Configuration:**
1. **Memory Model** - For extraction/verification (default: llama3.2)
2. **Global LLM Defaults** - For all cognitive processes

### 3. Updated README Documentation

**File**: `README.md`

Added comprehensive Docker Dev/Test section:

**Prerequisites:**
- Docker
- Memory Service running on host at `http://localhost:8082`
- Optional: Ollama on host at `http://localhost:11434`

**Workflow:**
```bash
# 1. Build the application
./mvnw package

# 2. Configure environment
cp .env.docker.example .env.docker
# Edit .env.docker as needed

# 3. Start container
docker compose up --build

# 4. View logs
docker compose logs -f cognition-processor
# or
tail -f logs/quarkus.log
```

## Architecture

### Container-to-Host Communication

The setup uses `host.docker.internal` to allow the containerized cognition processor to communicate with services running on the host:

```
┌─────────────────────────────────────┐
│ Docker Container                    │
│ ┌─────────────────────────────────┐ │
│ │ Cognition Processor             │ │
│ │ Port: 8090                      │ │
│ └─────────────────────────────────┘ │
│              ↓                      │
│    host.docker.internal             │
└─────────────────────────────────────┘
              ↓
┌─────────────────────────────────────┐
│ Host Machine                        │
│ ┌─────────────────┐ ┌─────────────┐ │
│ │ Memory Service  │ │   Ollama    │ │
│ │ Port: 8082      │ │ Port: 11434 │ │
│ └─────────────────┘ └─────────────┘ │
└─────────────────────────────────────┘
```

### Volume Mounts

- **`./logs:/deployments/logs`** - Persistent log storage on host filesystem
- Allows log inspection without entering container
- Survives container restarts and removals

### Environment Configuration Pattern

1. **Inline `environment:`** - Provides sensible defaults
2. **`env_file: .env.docker`** - Allows user customization
3. **`.env.docker.example`** - Template showing all options

Users can override any inline environment variable by setting it in their `.env.docker` file.

## Usage Scenarios

### Development Testing
```bash
# Quick iteration cycle
./mvnw package && docker compose up --build
```

### Integration Testing
```bash
# Test with different LLM providers
# Edit .env.docker to switch between Ollama/OpenAI/Gemini
docker compose up --build
```

### Log Analysis
```bash
# Real-time logs
docker compose logs -f cognition-processor

# Persisted logs
tail -f logs/quarkus.log
grep "ERROR" logs/quarkus.log
```

### Container Management
```bash
# Stop container
docker compose down

# Restart with fresh state
docker compose down && docker compose up --build

# View container status
docker compose ps
```

## Benefits

1. **Consistent Environment**: Same runtime environment across development machines
2. **Easy Setup**: Single command to start the service
3. **Host Integration**: Seamless connection to host services
4. **Log Persistence**: Logs survive container lifecycle
5. **Health Monitoring**: Built-in health checks for reliability
6. **Flexible Configuration**: Easy to switch LLM providers or adjust settings
7. **Production-like**: Closer to production deployment patterns

## Comparison with Native Development

| Aspect | Native (`./mvnw quarkus:dev`) | Docker Compose |
|--------|-------------------------------|----------------|
| Startup Time | Faster (no build) | Slower (build + start) |
| Hot Reload | Yes (dev mode) | No (requires rebuild) |
| Environment | Host environment | Isolated container |
| Dependencies | Must install locally | Bundled in image |
| Port Conflicts | Possible | Isolated |
| Best For | Active development | Integration testing |

## Available Dockerfiles

The project includes multiple Dockerfile options:

1. **`Dockerfile.jvm`** (used by compose) - JVM mode, faster builds
2. **`Dockerfile.legacy-jar`** - Traditional fat JAR
3. **`Dockerfile.native`** - GraalVM native compilation
4. **`Dockerfile.native-micro`** - Minimal native image

Docker Compose uses JVM mode by default for development convenience.

## Testing

Verified successful build and startup:
```bash
./mvnw package
docker compose up --build
```

Container health check passes when gRPC connection is established.

## Future Enhancements

Potential improvements:
- Multi-stage builds for smaller images
- Native image support in compose
- Development mode with volume mounts for hot reload
- Separate compose profiles for different LLM providers
- Integration with memory-service compose stack

## References

- [Docker Compose Documentation](https://docs.docker.com/compose/)
- [Quarkus Container Images Guide](https://quarkus.io/guides/container-image)
- [Docker Networking](https://docs.docker.com/network/)
- DONE/019-health-endpoints.md - Health check integration
