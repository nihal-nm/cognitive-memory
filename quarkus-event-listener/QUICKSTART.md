# Quick Start Guide - Quarkus Event Listener

## 🚀 5-Minute Quick Start

### 1. Set your authentication token

```bash
export MEMORY_SERVICE_TOKEN="your-token-here"
```

### 2. Run in development mode

```bash
cd /home/rigazilla/git/cognitive-memory/quarkus-event-listener
./mvnw quarkus:dev
```

That's it! The application is now running and connected to memory-service.

## ✅ Verify it's working

### Check the logs

You should see:
```
15:30:45 INFO  🚀 Starting memory-service event listener
15:30:45 INFO  ✅ Connected to memory-service event stream
15:30:45 INFO  👂 Listening for conversation events...
```

### Check the health endpoint

```bash
curl http://localhost:8080/q/health/live
```

Expected response:
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

### Open the Dev UI

Browse to: http://localhost:8080/q/dev/

The Dev UI shows:
- Configuration
- Beans
- Health checks
- Metrics
- Build info

## 🧪 Test Event Reception

### Terminal 1: Watch the logs
The application is already running from step 2.

### Terminal 2: Create a test conversation

```bash
curl -sSfX POST http://localhost:9090/chat/e2c9a1b0-0001-4000-8000-000000000001 \
  -H "Content-Type: text/plain" \
  -H "Authorization: Bearer $MEMORY_SERVICE_TOKEN" \
  -d "Hello, this is a test message."
```

### Terminal 1: See the events

You should see:
```
15:30:46 INFO  ✨ Conversation CREATED: id=e2c9a1b0-0001-4000-8000-000000000001, group=...
```

## 📊 View Metrics

```bash
curl http://localhost:8080/q/metrics | grep memory_service
```

Output:
```
memory_service_events_received_total 3.0
memory_service_conversations_created_total 1.0
memory_service_conversations_updated_total 0.0
memory_service_conversations_deleted_total 0.0
```

## 🎨 Development Mode Features

### Hot Reload

While in `quarkus:dev` mode, edit any Java file and save. The application automatically recompiles and reloads!

Try it:
1. Open `ConversationEventHandler.java`
2. Change the log message in `onConversationCreated()`
3. Save the file
4. Create another test conversation
5. See your new log message immediately!

### Continuous Testing

Press `r` in the terminal running `quarkus:dev` to run tests, or `w` to toggle continuous testing.

## 🐳 Running in Docker

### Quick Docker Run

```bash
# Build
./mvnw package
docker build -f src/main/docker/Dockerfile.jvm -t event-listener .

# Run
docker run --rm \
  -e MEMORY_SERVICE_TOKEN=$MEMORY_SERVICE_TOKEN \
  -e MEMORY_SERVICE_URL=http://host.docker.internal:9090 \
  -p 8080:8080 \
  event-listener
```

## ⚙️ Configuration Quick Reference

Edit `src/main/resources/application.properties`:

```properties
# Change memory-service URL
memory-service.url=http://production.example.com:9090

# Listen to all event types
memory-service.event-kinds=conversation,entry,response,membership

# Get full objects instead of IDs
memory-service.detail-level=full

# Enable debug logging
quarkus.log.category."io.github.rigazilla.memory".level=DEBUG
```

Or use environment variables (takes precedence):

```bash
export MEMORY_SERVICE_URL=http://production.example.com:9090
export MEMORY_SERVICE_EVENT_KINDS=conversation,entry
export MEMORY_SERVICE_DETAIL_LEVEL=full
```

## 🔧 Common Tasks

### Build production JAR

```bash
./mvnw package
java -jar target/quarkus-app/quarkus-run.jar
```

### Build uber JAR (single file)

```bash
./mvnw package -Dquarkus.package.jar.type=uber-jar
java -jar target/quarkus-event-listener-1.0.0-SNAPSHOT-runner.jar
```

### Build native executable (fast startup!)

```bash
# Requires GraalVM
./mvnw package -Dnative

# Or use container build (no GraalVM needed locally)
./mvnw package -Dnative -Dquarkus.native.container-build=true

# Run
./target/quarkus-event-listener-1.0.0-SNAPSHOT-runner
```

Native executable benefits:
- Starts in **~10ms** (vs 700ms for JVM)
- Uses **~20MB RAM** (vs 80MB for JVM)
- Perfect for containers and serverless

## 🎯 Next Steps

### 1. Implement your cache invalidation logic

Edit `ConversationEventHandler.java`:

```java
@Override
protected void onConversationCreated(ConversationEventData data) {
    super.onConversationCreated(data);
    
    // Add your logic here
    myCache.invalidate("conversation-list");
    pubSub.publish("conversation-created", data);
}
```

### 2. Add your own CDI beans

Create `src/main/java/io/github/rigazilla/memory/service/CacheService.java`:

```java
@ApplicationScoped
public class CacheService {
    
    private final Map<String, Object> cache = new ConcurrentHashMap<>();
    
    public void invalidate(String key) {
        cache.remove(key);
        LOG.infof("Invalidated cache key: %s", key);
    }
}
```

Inject it:
```java
@Inject
CacheService cacheService;
```

### 3. Add more event types

Change in `application.properties`:
```properties
memory-service.event-kinds=conversation,entry,response
```

### 4. Set up monitoring

- **Health:** http://localhost:8080/q/health
- **Metrics:** http://localhost:8080/q/metrics
- **Dev UI:** http://localhost:8080/q/dev

Integrate with:
- Prometheus (metrics already exposed)
- Grafana (visualize metrics)
- Kubernetes liveness/readiness probes (health checks)

## 📚 Learn More

- **Full README:** [README.md](README.md)
- **Quarkus Guides:** https://quarkus.io/guides/
- **Quarkus Dev UI:** http://localhost:8080/q/dev/ (in dev mode)

## 🆘 Quick Troubleshooting

### "MEMORY_SERVICE_TOKEN is required"
```bash
export MEMORY_SERVICE_TOKEN="your-token"
```

### "Connection refused"
- Check memory-service is running on port 9090
- Or set: `export MEMORY_SERVICE_URL=http://correct-host:port`

### "Permission denied: ./mvnw"
```bash
chmod +x mvnw
```

### Clear and rebuild
```bash
./mvnw clean compile
```

## 🎓 Quarkus Dev Mode Keyboard Shortcuts

While in `./mvnw quarkus:dev`:
- `r` - Run tests
- `w` - Toggle continuous testing
- `e` - Edit command line args
- `s` - Display runtime info
- `h` - Show help
- `q` - Quit

Enjoy building with Quarkus! 🚀
