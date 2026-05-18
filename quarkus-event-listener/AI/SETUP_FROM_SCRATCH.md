# Setup from Scratch - Complete Guide

This guide will walk you through setting up the entire system from scratch, including Ollama, memory-service, and the quarkus-event-listener.

## Prerequisites

- Docker and Docker Compose installed
- Git installed
- Java 17+ installed
- Maven installed (or use the included `./mvnw`)
- At least 8GB of RAM available for Docker

## Step 1: Start Ollama in Docker

First, we need to run Ollama as a standalone container that will be accessible to both memory-service and quarkus-event-listener.

### 1.1 Create and Start Ollama Container

```bash
# Create and run Ollama container with port exposed
docker run -d \
  --name ollama \
  -p 11434:11434 \
  -v ollama-data:/root/.ollama \
  --restart unless-stopped \
  ollama/ollama:latest
```

### 1.2 Pull Required Models

Pull the models needed for both memory-service and the event listener:

```bash
# Pull model for memory-service (embeddings and chat)
docker exec ollama ollama pull nomic-embed-text

# Pull model for quarkus-event-listener topic detection
# Option 1: Fast, lightweight (recommended)
docker exec ollama ollama pull qwen2.5:1.5b

# Option 2: Alternative fast model
docker exec ollama ollama pull gemma2:2b

# Option 3: Higher quality (slower)
docker exec ollama ollama pull qwen2.5:7b
```

### 1.3 Verify Ollama is Running

```bash
# Check container is running
docker ps | grep ollama

# Test Ollama API
curl http://localhost:11434/api/tags

# You should see the pulled models listed
```

## Step 2: Clone and Configure Memory-Service

### 2.1 Clone the Repository

```bash
# Clone memory-service repository
git clone https://github.com/chirino/memory-service.git
cd memory-service
```

### 2.2 Patch compose.yaml for External Ollama

The memory-service docker-compose file needs to be patched to use the external Ollama container running on the host.

Create or edit `compose.yaml` with these changes:

```bash
# Apply the patch
cat > /tmp/ollama-patch.diff << 'EOF'
diff --git a/compose.yaml b/compose.yaml
index 77b9b723..fd95fa11 100644
--- a/compose.yaml
+++ b/compose.yaml
@@ -103,6 +103,7 @@ services:
       start_period: 30s
 
   memory-service:
+    extra_hosts: ["host.docker.internal:host-gateway"]
 
     image: ghcr.io/chirino/memory-service:latest
     ports:
@@ -286,6 +287,8 @@ services:
       OPENAI_API_KEY: ${OPENAI_API_KEY:-none}
       OPENAI_BASE_URL: ${OPENAI_BASE_URL:-https://api.openai.com}
       OPENAI_MODEL: ${OPENAI_MODEL:-gpt-4o}
+    extra_hosts:
+      - "host.docker.internal:host-gateway"
     depends_on:
       memory-service:
         condition: service_healthy
EOF

# Apply the patch
patch -p1 < /tmp/ollama-patch.diff
```

**Or manually edit `compose.yaml`:**

1. Find the `memory-service` section and add:
```yaml
memory-service:
  extra_hosts: ["host.docker.internal:host-gateway"]
  # ... rest of config
```

2. Find the `memory-service-chat-quarkus` section and add:
```yaml
chat-quarkus:
  # ... existing config ...
  extra_hosts:
    - "host.docker.internal:host-gateway"
  # ... rest of config
```

### 2.3 Configure Environment Variables

Create a `.env` file in the memory-service directory to configure Ollama:

```bash
cat > .env << 'EOF'
OLLAMA_API_URL=http://host.docker.internal:11434
OPENAI_BASE_URL=http://host.docker.internal:11434
OPENAI_API_KEY=ollama
OPENAI_MODEL=qwen2.5:1.5b

MEMORY_SERVICE_EMBEDDING_OPENAI_API_KEY=ollama
MEMORY_SERVICE_EMBEDDING_OPENAI_BASE_URL=http://host.docker.internal:11434/v1
MEMORY_SERVICE_EMBEDDING_OPENAI_MODEL_NAME=nomic-embed-text
MEMORY_SERVICE_EMBEDDING_OPENAI_DIMENSIONS=768

EOF
```

## Step 3: Start Memory-Service

### 3.1 Start All Services

```bash
# From the memory-service directory
docker compose up -d
```

This will start:
- PostgreSQL database
- Memory-service API (port 8082)
- Memory-service Chat UI (port 8080)
- Supporting services (embedding service, etc.)

### 3.2 Wait for Services to be Healthy

```bash
# Monitor the startup
docker compose logs -f

# Wait for "healthy" status (Ctrl+C to stop watching)
# You should see messages indicating services are ready
```

### 3.3 Verify Memory-Service is Running

```bash
# Check health endpoint
curl http://localhost:8082/v1/health

# Check if you can list conversations (should return empty list initially)
curl http://localhost:8082/v1/conversations \
  -H "Authorization: Bearer alice" \
  -H "X-API-Key: test-key"

# Access the chat UI in your browser
# http://localhost:8080
```

## Step 4: Setup and Run Quarkus Event Listener

### 4.1 Clone or Navigate to Event Listener

```bash
# If you haven't cloned the cognitive-memory repo yet
git clone https://github.com/rigazilla/cognitive-memory.git
cd cognitive-memory/quarkus-event-listener

# Or if you already have it
cd /path/to/cognitive-memory/quarkus-event-listener
```

### 4.2 Configure Event Listener

The default configuration should work if you've followed the steps above. Verify `src/main/resources/application.properties`:

```properties
# Memory Service Configuration
memory-service.url=http://localhost:8082
memory-service.token=${MEMORY_SERVICE_TOKEN:alice}
memory-service.api-key=${MEMORY_SERVICE_API_KEY:test-key}
memory-service.event-kinds=conversation,entry
memory-service.detail-level=full
memory-service.enabled=true

# Quarkus HTTP Configuration
quarkus.http.port=8090

# REST Client Configuration
quarkus.rest-client."memory-service-api".url=${memory-service.url}
quarkus.rest-client."ollama".url=${OLLAMA_URL:http://localhost:11434}
quarkus.rest-client."ollama".read-timeout=120000

# Topic Detection Configuration
topic-detection.enabled=true
topic-detection.model=${OLLAMA_MODEL:qwen2.5:1.5b}
```

### 4.3 Start the Event Listener

```bash
# Start in dev mode (hot reload enabled)
./mvnw quarkus:dev
```

The application will:
- Start on port 8090
- NOT automatically connect to event streams
- Wait for you to start listeners via API

### 4.4 Verify Event Listener is Running

```bash
# Check health endpoint
curl http://localhost:8090/q/health

# Check if Ollama is accessible
curl http://localhost:11434/api/tags
```

## Step 5: Start Your First Listener

Now that everything is running, start a listener to receive events:

### 5.1 Start Listener for User 'alice'

```bash
curl -X POST http://localhost:8090/api/listeners \
  -H "Content-Type: application/json" \
  -d '{
    "token": "alice",
    "apiKey": "test-key"
  }'
```

**Response:**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "token": "alice",
  "conversationId": null,
  "status": "starting",
  "startedAt": "2026-05-18T16:40:00Z"
}
```

Save the listener `id` - you'll need it to stop the listener later.

### 5.2 Verify Listener is Connected

```bash
# List all active listeners
curl http://localhost:8090/api/listeners

# Check specific listener status
curl http://localhost:8090/api/listeners/{listener-id}
```

You should see `"status": "connected"` in the response.

## Step 6: Test the System

### 6.1 Create a Conversation via Chat UI

1. Open the chat UI in your browser: http://localhost:8080
2. Login with username `alice` (or any username)
3. Start a new conversation
4. Send a few messages about a specific topic, for example:
   - "I want to discuss API design patterns"
   - "We should focus on REST vs GraphQL"
   - "Authentication is also important"

### 6.2 Watch the Event Listener Logs

In the terminal where `./mvnw quarkus:dev` is running, you should see:

```
📨 Entry created: id=xxx, conversation=xxx, role=user
📦 Processing entry created: ...
🔍 Starting topic detection for conversation: xxx
Fetched conversation content: 3 entries
🏷️  Topics detected for conversation xxx: [api design, rest api, graphql, authentication]
```

### 6.3 Query Detected Topics

```bash
# List all detected topics
curl http://localhost:8090/api/topics

# Get topics for specific conversation
curl http://localhost:8090/api/topics/{conversationId}

# Get statistics
curl http://localhost:8090/api/topics/stats
```

**Example response:**
```json
[
  {
    "conversationId": "e8981dfb-2597-430b-9536-b31339297870",
    "conversationTitle": "API Design Discussion",
    "topics": ["api design", "rest api", "graphql", "authentication"],
    "messageCount": 5,
    "detectedAt": "2026-05-18T16:45:23Z"
  }
]
```

## Step 7: Multi-User Setup (Optional)

You can run multiple listeners for different users:

### 7.1 Start Listener for Another User

```bash
# Start listener for user 'bob'
curl -X POST http://localhost:8090/api/listeners \
  -H "Content-Type: application/json" \
  -d '{
    "token": "bob",
    "apiKey": "test-key"
  }'
```

### 7.2 Test with Different User

1. Open a private/incognito browser window
2. Go to http://localhost:8080
3. Login as `bob`
4. Create conversations and send messages
5. Topics will be detected for bob's conversations too

### 7.3 View All Listeners

```bash
curl http://localhost:8090/api/listeners
```

You'll see both alice's and bob's listeners active.

## Troubleshooting

### Ollama Connection Issues

**Problem:** "Connection refused to localhost:11434"

**Solution:**
```bash
# Check if Ollama container is running
docker ps | grep ollama

# Check Ollama logs
docker logs ollama

# Restart Ollama if needed
docker restart ollama

# Test connectivity
curl http://localhost:11434/api/tags
```

### Memory-Service Can't Connect to Ollama

**Problem:** "Failed to connect to Ollama" in memory-service logs

**Solution:**
```bash
# Verify extra_hosts is configured in compose.yaml
docker compose config | grep extra_hosts

# Test from inside memory-service container
docker compose exec memory-service curl http://host.docker.internal:11434/api/tags

# If fails, check Docker network
docker network inspect memory-service_default
```

### Topic Detection Timeout

**Problem:** "The timeout period of 30000ms has been exceeded"

**Solution:**
- The timeout is now set to 120 seconds in application.properties
- If still timing out, use a faster model:
  ```bash
  # Pull faster model
  docker exec ollama ollama pull qwen2.5:1.5b
  
  # Update application.properties
  topic-detection.model=qwen2.5:1.5b
  ```

### 403 Forbidden on Topic Detection

**Problem:** "Received: 'Forbidden, status code 403'"

**Solution:**
- This means authentication context isn't being passed correctly
- Make sure you're using the latest code with EventContext support
- Verify listener was started with correct token and apiKey

### Port Already in Use

**Problem:** "Port 8090 already in use"

**Solution:**
```bash
# Find what's using the port
lsof -i :8090

# Either kill that process or change the port in application.properties
quarkus.http.port=8091
```

## Stopping Services

### Stop Event Listener

```bash
# In the terminal running ./mvnw quarkus:dev, press 'q' or Ctrl+C

# Or stop specific listener via API
curl -X DELETE http://localhost:8090/api/listeners/{listener-id}

# Or stop all listeners
curl -X DELETE http://localhost:8090/api/listeners
```

### Stop Memory-Service

```bash
cd memory-service
docker compose down

# To also remove volumes (deletes all data)
docker compose down -v
```

### Stop Ollama

```bash
docker stop ollama

# To remove completely
docker rm ollama
docker volume rm ollama-data
```

## Next Steps

Now that everything is running:

1. **Explore the APIs**: Check out the API documentation in README.md
2. **Customize Topic Detection**: Try different Ollama models
3. **Build Custom Processors**: Extend ContentProcessor for your use case
4. **Add Persistence**: Replace in-memory TopicRepository with database
5. **Production Deployment**: Review deployment options in README.md

## Quick Reference

### Ports Used
- **8080**: Memory-service Chat UI
- **8082**: Memory-service API
- **8090**: Quarkus Event Listener
- **11434**: Ollama API
- **5432**: PostgreSQL (memory-service)

### Default Credentials
- **Users**: alice, bob (any username works for testing)
- **API Key**: test-key
- **Database**: postgres/postgres (configured in compose.yaml)

### Useful Commands

```bash
# View all running containers
docker ps

# View memory-service logs
cd memory-service && docker compose logs -f

# View event listener logs
# (already visible in terminal running ./mvnw quarkus:dev)

# View Ollama logs
docker logs -f ollama

# List all topics detected
curl http://localhost:8090/api/topics

# List active listeners
curl http://localhost:8090/api/listeners

# Health checks
curl http://localhost:8082/q/health  # memory-service
curl http://localhost:8090/q/health  # event listener
curl http://localhost:11434/api/tags # ollama
```

## Architecture Overview

```
┌─────────────────┐
│   Chat UI       │
│  (Port 8080)    │
└────────┬────────┘
         │
         ▼
┌─────────────────────────────────┐
│   Memory-Service                │
│   (Port 8082)                   │
│                                 │
│   - Stores conversations        │
│   - Generates embeddings        │────┐
│   - Sends SSE events            │    │
└──────┬──────────────────────────┘    │
       │                               │
       │ SSE Events                    │
       │                               │
       ▼                               │
┌─────────────────────────────────┐    │
│  Quarkus Event Listener         │    │
│  (Port 8090)                    │    │
│                                 │    │
│  - Multi-tenant listeners       │    │
│  - Topic detection              │────┤
│  - REST API                     │    │
└─────────────────────────────────┘    │
                                       │
                                       ▼
                              ┌─────────────────┐
                              │   Ollama        │
                              │  (Port 11434)   │
                              │                 │
                              │  - Embeddings   │
                              │  - Topic LLM    │
                              └─────────────────┘
```

## Support

- Memory-Service: https://github.com/chirino/memory-service
- Ollama: https://ollama.com/
- Quarkus: https://quarkus.io/

For issues with the event listener, check the logs and refer to the troubleshooting section above.
