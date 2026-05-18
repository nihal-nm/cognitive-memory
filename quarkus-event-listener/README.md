# Memory Service Event Listener (Quarkus)

A Quarkus-based event listener application that connects to memory-service and processes conversation events in real-time using Server-Sent Events (SSE).

## 🚀 Features

- ✅ **Quarkus framework** - Cloud-native, supersonic, subatomic Java
- ✅ **CDI beans** - Dependency injection for clean architecture
- ✅ **SSE streaming** - Real-time event streaming from memory-service
- ✅ **Health checks** - Built-in liveness/readiness probes
- ✅ **Metrics** - Prometheus metrics for monitoring
- ✅ **Hot reload** - Fast development with Quarkus dev mode
- ✅ **Native compilation** - Optional GraalVM native image support
- ✅ **Structured logging** - JBoss Logging with colored output
- ✅ **Type-safe config** - Configuration via `@ConfigMapping`

## 📋 Requirements

- Java 17 or higher
- Maven 3.9+
- Running memory-service instance
- Valid authentication token

## 🏃 Quick Start

See [QUICKSTART.md](QUICKSTART.md) for a 5-minute getting started guide.

```bash
export MEMORY_SERVICE_TOKEN="your-token-here"
./mvnw quarkus:dev
```

## 🏃 Running the Application

### Development Mode (with hot reload)

```bash
cd /home/rigazilla/git/cognitive-memory/quarkus-event-listener

# Set your token
export MEMORY_SERVICE_TOKEN="your-token-here"

# Run in dev mode
./mvnw quarkus:dev
```

This starts the application in dev mode with live coding enabled. Any code changes will trigger automatic recompilation.

**Dev Mode UI:** http://localhost:8080/q/dev/

### Production Mode (JVM)

```bash
# Build the application
./mvnw package

# Run the JAR
export MEMORY_SERVICE_TOKEN="your-token-here"
java -jar target/quarkus-app/quarkus-run.jar
```

### Production Mode (Uber JAR)

```bash
# Build uber JAR
./mvnw package -Dquarkus.package.jar.type=uber-jar

# Run
export MEMORY_SERVICE_TOKEN="your-token-here"
java -jar target/quarkus-event-listener-1.0.0-SNAPSHOT-runner.jar
```

### Native Executable (GraalVM)

```bash
# Requires GraalVM with native-image
./mvnw package -Dnative

# Run native executable
export MEMORY_SERVICE_TOKEN="your-token-here"
./target/quarkus-event-listener-1.0.0-SNAPSHOT-runner
```

Native executable benefits:
- **Sub-second startup time** (~0.01s)
- **Minimal memory footprint** (~20MB)
- **Ideal for containers** and serverless

## ⚙️ Configuration

Configuration is in `src/main/resources/application.properties`:

```properties
# Memory Service Connection
memory-service.url=http://localhost:9090
memory-service.token=${MEMORY_SERVICE_TOKEN:}
memory-service.event-kinds=conversation
memory-service.detail-level=summary
memory-service.enabled=true
```

### Configuration Options

| Property | Description | Default |
|----------|-------------|---------|
| `memory-service.url` | Base URL of memory-service | `http://localhost:9090` |
| `memory-service.token` | Bearer authentication token | Environment variable |
| `memory-service.event-kinds` | Event types (comma-separated) | `conversation` |
| `memory-service.detail-level` | `summary` or `full` | `summary` |
| `memory-service.enabled` | Enable/disable listener | `true` |

### Environment Variables

You can override any property using environment variables:

```bash
export MEMORY_SERVICE_URL=http://production.example.com:9090
export MEMORY_SERVICE_TOKEN=your-production-token
export MEMORY_SERVICE_EVENT_KINDS=conversation,entry,response
export MEMORY_SERVICE_DETAIL_LEVEL=full
```

## 🏥 Health Checks

The application exposes health check endpoints:

```bash
# Combined health check
curl http://localhost:8080/q/health

# Liveness probe (is the app running?)
curl http://localhost:8080/q/health/live

# Readiness probe (is the app ready to serve requests?)
curl http://localhost:8080/q/health/ready
```

Response example:
```json
{
  "status": "UP",
  "checks": [
    {
      "name": "memory-service-event-stream",
      "status": "UP",
      "data": {
        "connected": true
      }
    }
  ]
}
```

**Health UI:** http://localhost:8080/q/health-ui/

## 📊 Metrics

Prometheus metrics are exposed at `/q/metrics`:

```bash
# View all metrics
curl http://localhost:8080/q/metrics
```

Custom metrics:
- `memory_service_events_received_total` - Total events received
- `memory_service_conversations_created_total` - Conversations created
- `memory_service_conversations_updated_total` - Conversations updated
- `memory_service_conversations_deleted_total` - Conversations deleted

## 🎯 Customizing Event Handlers

The main event handling logic is in `ConversationEventHandler.java`. Override the methods to implement your business logic:

```java
@ApplicationScoped
public class MyCustomEventHandler extends ConversationEventHandler {

    @Inject
    MyCache cache;

    @Override
    protected void onConversationCreated(ConversationEventData data) {
        super.onConversationCreated(data);
        
        // Your custom logic
        cache.invalidate("conversation-list");
        notificationService.send("New conversation created!");
    }

    @Override
    protected void onConversationUpdated(ConversationEventData data) {
        super.onConversationUpdated(data);
        
        // Your custom logic
        cache.invalidate("conversation:" + data.getConversationId());
    }

    @Override
    protected void onConversationDeleted(ConversationEventData data) {
        super.onConversationDeleted(data);
        
        // Your custom logic
        cache.remove("conversation:" + data.getConversationId());
    }
}
```

## 🧪 Testing

To test the event listener:

### Terminal 1: Start the application
```bash
export MEMORY_SERVICE_TOKEN=$(get-token)
./mvnw quarkus:dev
```

### Terminal 2: Create a test conversation
```bash
curl -sSfX POST http://localhost:9090/chat/e2c9a1b0-0001-4000-8000-000000000001 \
  -H "Content-Type: text/plain" \
  -H "Authorization: Bearer $(get-token)" \
  -d "Hello, this is a test."
```

You should see events in Terminal 1:
```
15:30:45 INFO  [io.gi.ri.me.se.ConversationEventHandler] ✅ Connected to memory-service event stream
15:30:45 INFO  [io.gi.ri.me.se.ConversationEventHandler] 👂 Listening for conversation events...
15:30:45 INFO  [io.gi.ri.me.se.ConversationEventHandler] 📡 Stream phase: live
15:30:46 INFO  [io.gi.ri.me.se.ConversationEventHandler] ✨ Conversation CREATED: id=e2c9a1b0-0001-4000-8000-000000000001, group=...
```

## 🐳 Docker

### Build Docker image (JVM)

```bash
./mvnw package
docker build -f src/main/docker/Dockerfile.jvm -t quarkus-event-listener:jvm .
```

### Build Docker image (Native)

```bash
./mvnw package -Dnative -Dquarkus.native.container-build=true
docker build -f src/main/docker/Dockerfile.native -t quarkus-event-listener:native .
```

### Run container

```bash
docker run -e MEMORY_SERVICE_TOKEN=your-token \
           -e MEMORY_SERVICE_URL=http://host.docker.internal:9090 \
           -p 8080:8080 \
           quarkus-event-listener:jvm
```

## 📁 Project Structure

```
quarkus-event-listener/
├── src/main/java/io/github/rigazilla/memory/
│   ├── config/
│   │   └── MemoryServiceConfig.java      # Type-safe configuration
│   ├── health/
│   │   └── EventStreamHealthCheck.java   # Health check
│   ├── lifecycle/
│   │   └── EventStreamLifecycle.java     # Startup/shutdown hooks
│   ├── model/
│   │   ├── Event.java                    # Event model
│   │   └── ConversationEventData.java    # Conversation data model
│   └── service/
│       ├── EventHandler.java             # Event handler interface
│       ├── ConversationEventHandler.java # Default event handler
│       └── MemoryServiceEventClient.java # SSE client
├── src/main/resources/
│   └── application.properties            # Configuration
└── pom.xml                               # Maven build file
```

## 🛠️ Development

### Continuous Testing

```bash
./mvnw quarkus:test
```

### Update dependencies

```bash
./mvnw quarkus:update
```

### Add extensions

```bash
./mvnw quarkus:add-extension -Dextensions="jdbc-postgresql"
```

### List extensions

```bash
./mvnw quarkus:list-extensions
```

## 🚀 Deployment

### Kubernetes

Quarkus can generate Kubernetes manifests:

```bash
./mvnw quarkus:add-extension -Dextensions="kubernetes"
./mvnw package
```

Manifests are generated in `target/kubernetes/`.

### OpenShift

```bash
./mvnw quarkus:add-extension -Dextensions="openshift"
./mvnw package
```

## 🔧 Advanced Configuration

### Listening to Multiple Event Types

In `application.properties`:
```properties
memory-service.event-kinds=conversation,entry,response,membership
```

### Full Detail Mode

Get complete resource objects instead of just IDs:
```properties
memory-service.detail-level=full
```

### Custom Logging Levels

```properties
quarkus.log.category."io.github.rigazilla.memory".level=DEBUG
quarkus.log.category."okhttp3".level=DEBUG
```

## 📖 Quarkus Resources

- **Quarkus Website:** https://quarkus.io/
- **Guides:** https://quarkus.io/guides/
- **Dev UI:** http://localhost:8080/q/dev/ (in dev mode)

## 🆚 Comparison: Plain Java vs Quarkus

| Feature | Plain Java | Quarkus |
|---------|------------|---------|
| Startup time | ~2-3s | ~0.7s (JVM), ~0.01s (native) |
| Memory usage | ~150MB | ~80MB (JVM), ~20MB (native) |
| Hot reload | No | Yes (dev mode) |
| Dependency injection | Manual | CDI |
| Health checks | Manual | Built-in |
| Metrics | Manual | Built-in (Micrometer) |
| Configuration | Environment vars | Type-safe `@ConfigMapping` |
| Cloud-native | Manual | Built-in (Kubernetes, containers) |

## 🐛 Troubleshooting

### Connection refused
```
ERROR SSE connection failed
```
- Check `memory-service.url` is correct
- Ensure memory-service is running

### Token missing
```
WARN Memory service event listener is disabled
```
- Set `MEMORY_SERVICE_TOKEN` environment variable

### Native build fails
```bash
# Install GraalVM native-image
gu install native-image

# Or use container build
./mvnw package -Dnative -Dquarkus.native.container-build=true
```

## 📝 License

See parent project for license information.
