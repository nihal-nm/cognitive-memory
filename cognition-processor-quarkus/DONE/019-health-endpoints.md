# 019 - Health Endpoints

**Status**: ✅ Complete  
**Date**: 2026-06-24

## Overview

Added SmallRye Health extension to expose standard health check endpoints for monitoring and container orchestration. Includes a custom readiness check that monitors the gRPC connection to memory-service.

## Changes Made

### 1. Added Health Extension Dependency

**File**: `pom.xml`

Added `quarkus-smallrye-health` dependency to enable health check endpoints:

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-smallrye-health</artifactId>
</dependency>
```

### 2. Created Custom gRPC Connection Health Check

**File**: `src/main/java/io/github/rigazilla/memory/cognition/health/GrpcConnectionHealthCheck.java`

Implemented a `@Readiness` health check that:
- Reports the connection status of the gRPC event stream client
- Includes connection metadata (host, port, status)
- Handles errors gracefully with detailed error messages
- Uses proper MicroProfile Health API with `HealthCheckResponseBuilder`

Key features:
- **Readiness probe**: Service is ready only when gRPC is connected
- **Metadata**: Exposes host, port, and connection status
- **Error handling**: Catches exceptions and reports them in health response

### 3. Extended GrpcAdminEventClient

**File**: `src/main/java/io/github/rigazilla/memory/cognition/event/GrpcAdminEventClient.java`

Added getter methods for health check integration:
- `getHost()` - Returns configured gRPC host
- `getPort()` - Returns configured gRPC port

These methods allow the health check to include connection details in its response.

### 4. Updated Docker Compose Configuration

**File**: `docker-compose.yml`

Added health check configuration:
```yaml
healthcheck:
  test: ["CMD", "curl", "-f", "http://localhost:8090/q/health/ready"]
  interval: 30s
  timeout: 10s
  retries: 3
  start_period: 40s
```

Benefits:
- Docker can monitor container health
- Automatic restart on health check failures
- 40s start period allows for initialization
- Uses readiness endpoint to ensure gRPC is connected

### 5. Updated Documentation

**File**: `README.md`

Updated the "Verify it's running" section with health check endpoints:
- Liveness: `http://localhost:8090/q/health/live`
- Readiness: `http://localhost:8090/q/health/ready` (includes gRPC status)
- Full health: `http://localhost:8090/q/health`

## Available Endpoints

After this change, the following health endpoints are available:

1. **`/q/health`** - Combined health check (all checks)
2. **`/q/health/live`** - Liveness probe (is the app running?)
3. **`/q/health/ready`** - Readiness probe (is the app ready to serve traffic?)
4. **`/q/health/started`** - Startup probe (has the app started?)

## Health Check Response Example

**When connected:**
```json
{
  "status": "UP",
  "checks": [
    {
      "name": "grpc-memory-service",
      "status": "UP",
      "data": {
        "status": "connected",
        "host": "localhost",
        "port": "8082"
      }
    }
  ]
}
```

**When disconnected:**
```json
{
  "status": "DOWN",
  "checks": [
    {
      "name": "grpc-memory-service",
      "status": "DOWN",
      "data": {
        "status": "disconnected",
        "host": "localhost",
        "port": "8082"
      }
    }
  ]
}
```

## Benefits

1. **Container Orchestration**: Kubernetes and Docker Compose can monitor service health
2. **Automatic Recovery**: Containers can be restarted when unhealthy
3. **Monitoring Integration**: Health endpoints can be scraped by monitoring systems
4. **Operational Visibility**: Quick way to check if gRPC connection is established
5. **Graceful Startup**: Start period allows initialization without false negatives

## Testing

Compilation verified successfully:
```bash
./mvnw clean compile -DskipTests
```

To test health endpoints locally:
```bash
# Start the application
./mvnw quarkus:dev

# Check readiness (includes gRPC status)
curl http://localhost:8090/q/health/ready

# Check liveness
curl http://localhost:8090/q/health/live

# Check all health checks
curl http://localhost:8090/q/health
```

## Future Enhancements

Potential additional health checks:
- LLM provider connectivity (Ollama/OpenAI/Gemini)
- Checkpoint service health
- Window registry status (number of dirty windows)
- Job queue depth
- Memory usage thresholds

## References

- [SmallRye Health Documentation](https://quarkus.io/guides/smallrye-health)
- [MicroProfile Health Specification](https://github.com/eclipse/microprofile-health)
- [Docker Health Checks](https://docs.docker.com/engine/reference/builder/#healthcheck)
