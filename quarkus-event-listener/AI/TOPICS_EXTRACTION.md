# Topic Extraction Feature

## Overview

The topic extraction feature automatically detects and extracts main topics from conversations using LLM-based analysis via Ollama. When a new message is added to a conversation, the system asynchronously analyzes the entire conversation history and identifies 3-5 key topics being discussed.

## Architecture

### Components

```
Event Stream → Content Processor → Topic Detection Service → Ollama LLM
                                          ↓
                                   Topic Repository (in-memory)
                                          ↓
                                    REST API Endpoints
```

### Flow

1. **Event Trigger**: When an entry (message) is created, the event handler triggers topic detection
2. **Async Processing**: Topic detection runs asynchronously to avoid blocking event processing
3. **Content Fetching**: The service fetches the full conversation and all its entries
4. **LLM Analysis**: Builds a prompt and sends it to Ollama for analysis
5. **Topic Extraction**: Parses the LLM response to extract topics
6. **Storage**: Stores detected topics in-memory with metadata
7. **API Access**: Topics can be queried via REST endpoints

### Authentication Context

Each listener has its own authentication context (token + apiKey). When processing events:
- Event → EventContext (wraps Event + auth credentials)
- Auth context flows through: EventHandler → ContentProcessor → TopicDetectionService
- All API calls (fetch conversation, fetch entries) use the listener's credentials
- This prevents 403 Forbidden errors when multiple listeners with different users are active

## Code Implementation

### Core Classes

#### 1. TopicDetectionService
**Location**: `src/main/java/io/github/rigazilla/memory/topics/TopicDetectionService.java`

Main service responsible for topic detection.

**Key Methods**:
```java
public CompletionStage<ConversationTopics> detectTopicsForConversation(
    String conversationId, String token, String apiKey)
```

**Process**:
1. Fetches conversation details using `MemoryServiceClient.getConversation()`
2. Fetches all entries using `MemoryServiceClient.getEntries()`
3. Builds a prompt with conversation title and all messages
4. Calls Ollama via `OllamaClient.generate()`
5. Parses topics from LLM response
6. Returns `ConversationTopics` object

**Prompt Structure**:
```
Analyze the following conversation and extract the main topics discussed.

Conversation Title: [title]

Messages:
- [role]: [content]
- [role]: [content]
...

Extract 3-5 main topics from this conversation.
Return ONLY the topics as a comma-separated list, nothing else.
Example format: artificial intelligence, machine learning, neural networks

Topics:
```

**Configuration**:
- `topic-detection.enabled` - Enable/disable feature (default: true)
- `topic-detection.model` - Ollama model to use (default: qwen2.5:1.5b)
- `quarkus.rest-client."ollama".read-timeout` - Timeout for LLM calls (default: 120000ms)

#### 2. TopicRepository
**Location**: `src/main/java/io/github/rigazilla/memory/topics/TopicRepository.java`

In-memory storage using `ConcurrentHashMap`.

**Key Methods**:
```java
void save(ConversationTopics topics)
Optional<ConversationTopics> findByConversationId(String conversationId)
List<ConversationTopics> findAll()
TopicStats getStats()
void clear()
```

**Data Structure**:
```java
private final Map<String, ConversationTopics> topicsMap = new ConcurrentHashMap<>();
```

#### 3. ConversationTopics
**Location**: `src/main/java/io/github/rigazilla/memory/topics/ConversationTopics.java`

DTO representing detected topics for a conversation.

**Fields**:
```java
String conversationId       // The conversation ID
String conversationTitle    // The conversation title
List<String> topics         // Detected topics (3-5 items)
int messageCount           // Number of messages analyzed
Instant detectedAt         // When topics were detected
```

#### 4. OllamaClient
**Location**: `src/main/java/io/github/rigazilla/memory/ollama/OllamaClient.java`

MicroProfile REST Client for Ollama API.

**Interface**:
```java
@POST
@Path("/api/generate")
CompletionStage<OllamaResponse> generate(OllamaRequest request);
```

**Request/Response**:
```java
OllamaRequest {
    String model;
    String prompt;
    Double temperature;
    Boolean stream;
}

OllamaResponse {
    String model;
    String response;  // LLM generated text
    Boolean done;
}
```

#### 5. TopicResource
**Location**: `src/main/java/io/github/rigazilla/memory/api/TopicResource.java`

REST API endpoints for accessing topics.

#### 6. EventContext
**Location**: `src/main/java/io/github/rigazilla/memory/model/EventContext.java`

Wrapper that carries an event along with its authentication context.

**Fields**:
```java
Event event
String token
String apiKey
```

**Purpose**: Ensures that when a listener receives an event, all subsequent API calls use that listener's credentials.

### Integration Points

#### ContentProcessor
When an entry is created, triggers async topic detection:

```java
public void processEntryCreated(Entry entry, String token, String apiKey) {
    topicDetectionService.detectTopicsForConversation(
        entry.getConversationId(), token, apiKey)
        .thenAccept(topics -> {
            if (topics != null) {
                LOG.infof("🏷️ Topics detected: %s", topics.getTopics());
            }
        });
}
```

#### Event Flow with Authentication

```
Listener (token=alice) → EventContext(event, alice, key) 
    → ConversationEventHandler.onEvent(EventContext)
    → ContentProcessor.processEntryCreated(entry, alice, key)
    → TopicDetectionService.detectTopicsForConversation(id, alice, key)
    → MemoryServiceClient.getConversation(id, "Bearer alice", key)
    → MemoryServiceClient.getEntries(id, "Bearer alice", key)
    → OllamaClient.generate(prompt)
    → TopicRepository.save(topics)
```

## REST API

### Base Path
All endpoints are under `/api/topics`

### Endpoints

#### 1. List All Detected Topics
```http
GET /api/topics
```

**Response**:
```json
[
  {
    "conversationId": "e8981dfb-2597-430b-9536-b31339297870",
    "conversationTitle": "Project Discussion",
    "topics": ["api design", "authentication", "testing"],
    "messageCount": 15,
    "detectedAt": "2026-05-18T10:19:38Z"
  }
]
```

**Example**:
```bash
curl http://localhost:8090/api/topics
```

#### 2. Get Topics for Specific Conversation
```http
GET /api/topics/{conversationId}
```

**Response**:
```json
{
  "conversationId": "e8981dfb-2597-430b-9536-b31339297870",
  "conversationTitle": "Project Discussion",
  "topics": ["api design", "authentication", "testing"],
  "messageCount": 15,
  "detectedAt": "2026-05-18T10:19:38Z"
}
```

**Example**:
```bash
curl http://localhost:8090/api/topics/e8981dfb-2597-430b-9536-b31339297870
```

**Error Response** (404 if not found):
```json
{
  "error": "Topics not found for conversation"
}
```

#### 3. Manually Trigger Topic Detection
```http
POST /api/topics/{conversationId}/detect
```

Manually triggers topic detection for a conversation. Useful for:
- Re-analyzing a conversation
- Detecting topics for existing conversations
- Testing the feature

**Response**:
```json
{
  "conversationId": "e8981dfb-2597-430b-9536-b31339297870",
  "conversationTitle": "Project Discussion",
  "topics": ["api design", "authentication", "testing"],
  "messageCount": 15,
  "detectedAt": "2026-05-18T10:25:12Z"
}
```

**Example**:
```bash
curl -X POST http://localhost:8090/api/topics/e8981dfb-2597-430b-9536-b31339297870/detect
```

**Note**: Uses default authentication from config. For multi-user scenarios, topics are auto-detected when messages arrive via the event stream with proper user credentials.

#### 4. Get Statistics
```http
GET /api/topics/stats
```

Returns statistics about detected topics.

**Response**:
```json
{
  "totalConversations": 5,
  "totalUniqueTopics": 12,
  "topTopics": {
    "api design": 3,
    "authentication": 2,
    "testing": 2,
    "deployment": 1
  }
}
```

**Example**:
```bash
curl http://localhost:8090/api/topics/stats
```

#### 5. Clear All Topics
```http
DELETE /api/topics
```

Clears all stored topics. Useful for testing or resetting state.

**Response**:
```json
{
  "message": "All topics cleared"
}
```

**Example**:
```bash
curl -X DELETE http://localhost:8090/api/topics
```

## Configuration

### application.properties

```properties
# Ollama Configuration
quarkus.rest-client."ollama".url=${OLLAMA_URL:http://localhost:11434}
quarkus.rest-client."ollama".read-timeout=120000

# Topic Detection Configuration
topic-detection.enabled=true
topic-detection.model=${OLLAMA_MODEL:qwen2.5:1.5b}
```

### Environment Variables

- `OLLAMA_URL` - Ollama server URL (default: http://localhost:11434)
- `OLLAMA_MODEL` - Model to use for topic detection (default: qwen2.5:1.5b)

### Model Recommendations

**Fast, Lightweight** (recommended for production):
- `qwen2.5:1.5b` - Very fast, good quality (default)
- `gemma2:2b` - Fast, good quality

**Higher Quality** (slower):
- `qwen2.5:7b` - Better quality, slower
- `llama3.2:3b` - Good balance

**Maximum Quality** (much slower):
- `gemma2:9b` - High quality
- `llama3.1:8b` - Excellent quality

## Asynchronous Processing

The feature is designed to be non-blocking:

1. **Event Processing**: Events are processed immediately, topic detection happens in background
2. **CompletableFuture**: All topic detection methods return `CompletionStage`
3. **Timeout Handling**: LLM calls have 120-second timeout
4. **Error Handling**: Failures are logged but don't break event processing

**Example Flow**:
```
Entry Created Event
    ↓
processEntryCreated (returns immediately)
    ↓
detectTopicsForConversation (async)
    ↓
    ├─ fetchConversationContent (API calls)
    ├─ analyzeTopics (LLM call - may take 30-60s)
    └─ save to repository
```

## Storage

### In-Memory Repository

Currently uses `ConcurrentHashMap` for in-memory storage:

**Pros**:
- Fast access
- No database setup required
- Good for prototyping

**Cons**:
- Data lost on restart
- No persistence
- Limited to single instance

### Future Enhancements

For production use, consider:
- **Database Storage**: PostgreSQL, MongoDB
- **Caching Layer**: Redis for fast access
- **Event Sourcing**: Store topic detection events
- **Versioning**: Track topic changes over time

## Performance Considerations

### LLM Inference Time

Typical processing times (depends on hardware and model):
- `qwen2.5:1.5b`: 5-15 seconds
- `gemma2:2b`: 10-20 seconds
- `qwen2.5:7b`: 30-60 seconds

### Optimization Strategies

1. **Model Selection**: Use smaller models for faster inference
2. **Incremental Updates**: Only re-analyze when significant changes occur
3. **Caching**: Cache topics and only refresh periodically
4. **Batching**: Process multiple conversations in parallel
5. **GPU Acceleration**: Run Ollama with GPU support

### Resource Usage

- **Memory**: 2-8GB depending on model size
- **CPU**: High during inference, idle otherwise
- **Network**: Minimal (local Ollama preferred)

## Error Handling

The system handles various error scenarios:

### Authentication Errors (403 Forbidden)
- **Cause**: Using wrong credentials for conversation access
- **Solution**: EventContext ensures each listener uses its own credentials
- **Logged**: Error message with conversation ID

### Timeout Errors
- **Cause**: LLM taking too long (>120 seconds)
- **Solution**: Increase `quarkus.rest-client."ollama".read-timeout`
- **Logged**: Timeout exception with conversation ID

### Empty Response
- **Cause**: LLM returns no topics
- **Solution**: Returns empty list, doesn't crash
- **Logged**: Warning message

### Ollama Connection Failed
- **Cause**: Ollama server not running or unreachable
- **Solution**: Check Ollama is running on configured URL
- **Logged**: Connection error

## Testing

### Manual Testing

1. **Start Ollama**:
```bash
docker start ollama
```

2. **Send a message** in the chat UI (triggers auto-detection)

3. **Check logs** for topic detection:
```
🔍 Starting topic detection for conversation: ...
🏷️ Topics detected: [api design, authentication, testing]
```

4. **Query via API**:
```bash
curl http://localhost:8090/api/topics
```

### Manual Trigger

Force detection for a specific conversation:
```bash
curl -X POST http://localhost:8090/api/topics/{conversationId}/detect
```

## Example Usage Scenarios

### Scenario 1: Monitor Topics in Real-Time

Start a listener and watch topics being detected automatically:

```bash
# Start listener for alice
curl -X POST http://localhost:8090/api/listeners \
  -H "Content-Type: application/json" \
  -d '{"token": "alice", "apiKey": "test-key"}'

# Send messages in chat UI

# Query detected topics
curl http://localhost:8090/api/topics
```

### Scenario 2: Analyze Existing Conversations

Manually trigger analysis for existing conversations:

```bash
# Get conversation IDs from memory-service
curl http://localhost:8082/v1/conversations \
  -H "Authorization: Bearer alice" \
  -H "X-API-Key: test-key"

# Trigger topic detection
curl -X POST http://localhost:8090/api/topics/{conversationId}/detect

# Get results
curl http://localhost:8090/api/topics/{conversationId}
```

### Scenario 3: Get Topic Statistics

Analyze topic trends across all conversations:

```bash
curl http://localhost:8090/api/topics/stats
```

## Multi-Tenant Support

The system supports multiple listeners with different user credentials:

```bash
# Listener for alice
curl -X POST http://localhost:8090/api/listeners \
  -d '{"token": "alice", "apiKey": "test-key"}'

# Listener for bob  
curl -X POST http://localhost:8090/api/listeners \
  -d '{"token": "bob", "apiKey": "test-key"}'
```

Each listener:
- Receives only events for conversations the user can access
- Uses its own credentials for API calls
- Topics are detected with proper authorization
- All stored in the same repository (globally accessible via API)

## Limitations

1. **No Persistence**: Topics lost on application restart
2. **Single Instance**: Repository not shared across multiple app instances
3. **No Delta Detection**: Re-analyzes entire conversation each time
4. **No Topic Evolution**: Doesn't track how topics change over time
5. **Limited Filtering**: Can't query by topic or date range
6. **No User Isolation**: Topics from all users stored together

## Future Improvements

1. **Persistent Storage**: Add database backend
2. **Topic Similarity**: Merge similar topics (e.g., "api design" and "API architecture")
3. **Incremental Updates**: Only analyze new messages
4. **Topic Trends**: Track topic evolution over time
5. **Advanced Queries**: Search conversations by topic
6. **Confidence Scores**: LLM confidence for each topic
7. **User Preferences**: Per-user topic models or preferences
8. **Webhooks**: Notify external systems when new topics detected
9. **Topic Hierarchies**: Organize topics in categories
10. **Multi-language**: Support non-English conversations
