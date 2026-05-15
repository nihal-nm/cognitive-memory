# Phase 1 Analysis: Infinispan Vector Store Implementation

## Executive Summary

Successfully analyzed llama-stack's Infinispan vector store implementation. Infinispan **DOES** support native vector search through:
- **Vector annotations** in Protobuf schemas (`@Vector(dimension=N, similarity=COSINE)`)
- **Ickle query language** with `<->` operator for vector similarity
- **KNN search** using `~ k` syntax for top-k nearest neighbors

## Key Findings

### 1. Vector Search Capabilities ✅

**Infinispan supports native vector search** via Protobuf annotations:

```protobuf
/**
 * @Vector(dimension={DIMENSION}, similarity=COSINE)
 */
repeated float floatVector = 2;
```

**Query Syntax** (Ickle):
```sql
SELECT i, score(i) FROM VectorItem1536 i 
WHERE i.floatVector <-> [0.1, 0.2, ...] ~ 10
```

- `<->` operator: Vector similarity comparison
- `~ k` suffix: Returns top-k nearest neighbors
- `score(i)`: Returns similarity score (0-1 range for cosine)

### 2. Protobuf Schema Structure

**Template**: `vector_chunk.proto`
```protobuf
syntax = "proto2";

/**
 * @Indexed
 */
message VectorItem{DIMENSION} {
  /** @Keyword */
  optional string id = 1;
  
  /** @Vector(dimension={DIMENSION}, similarity=COSINE) */
  repeated float floatVector = 2;
  
  /** @Text */
  optional string text = 3;
  
  /** @Basic */
  optional string metadata = 4;
  optional string chunkMetadata = 5;
  optional string embeddingModel = 6;
}
```

**Key Annotations**:
- `@Indexed`: Enables indexing for the message
- `@Vector(dimension=N, similarity=COSINE)`: Vector field with cosine similarity
- `@Text`: Full-text search indexing
- `@Keyword`: Exact match indexing
- `@Basic`: Non-indexed field

**Schema Registration**:
- Endpoint: `PUT /rest/v3/caches/___protobuf_metadata/entries/{schema_name}`
- Content-Type: `text/plain`
- Schema name: `vector_chunk_{dimension}.proto`

### 3. Cache Configuration

**Template**: `cache_config.xml`
```xml
<distributed-cache name="{CACHE_NAME}">
  <indexing storage="local-heap">
    <indexed-entities>
      <indexed-entity>{ENTITY_NAME}</indexed-entity>
    </indexed-entities>
  </indexing>
</distributed-cache>
```

**Creation**:
- Endpoint: `POST /rest/v3/caches/{cacheName}`
- Content-Type: `application/xml`
- Must register schema BEFORE creating cache

### 4. REST API Operations (v3)

#### Cache Management
- **Check existence**: `HEAD /rest/v3/caches/{cacheName}` → 204 (exists) or 404 (not exists)
- **Create cache**: `POST /rest/v3/caches/{cacheName}` with XML config
- **Delete cache**: `DELETE /rest/v3/caches/{cacheName}`

#### Entry Operations
- **Insert/Update**: `PUT /rest/v3/caches/{cacheName}/entries/{key}`
  - Content-Type: `application/json`
  - Body: JSON with `_type` field matching Protobuf message name
- **Delete**: `DELETE /rest/v3/caches/{cacheName}/entries/{key}`

#### Search Operations
- **Vector/Keyword Search**: `POST /rest/v3/caches/{cacheName}/_search`
  - Content-Type: `application/json`
  - Body: `{"query": "Ickle query", "max_results": k, "query_mode": "INDEXED"}`
  - Response: `{"hits": [...], "hit_count": N}`

### 5. Query Patterns

#### Vector Similarity Search
```json
{
  "query": "SELECT i, score(i) FROM VectorItem1536 i WHERE i.floatVector <-> [0.1, 0.2, ...] ~ 10",
  "max_results": 10,
  "query_mode": "INDEXED"
}
```

#### Keyword Search
```json
{
  "query": "SELECT i, score(i) FROM VectorItem1536 i WHERE text : 'search terms' ~ 2",
  "max_results": 10,
  "query_mode": "INDEXED"
}
```

**Query Operators**:
- `:` - Full-text search (with `@Text` annotation)
- `<->` - Vector similarity
- `~ k` - Top-k results
- `score(i)` - Returns relevance score

### 6. Authentication

**Supported Mechanisms**:
- **Basic Auth**: `httpx.BasicAuth(username, password)`
- **Digest Auth**: `httpx.DigestAuth(username, password)` (default)

**Configuration**:
```python
auth_mechanism: str = "digest"  # or "basic"
username: str | None
password: SecretStr | None
```

### 7. Data Model Mapping

**llama-stack EmbeddedChunk → Infinispan VectorItem**:

| EmbeddedChunk Field | VectorItem Field | Type | Notes |
|---------------------|------------------|------|-------|
| chunk_id | id | string | Primary key |
| embedding | floatVector | repeated float | Vector data |
| content | text | string | Full-text indexed |
| metadata | metadata | string | JSON serialized |
| chunk_metadata | chunkMetadata | string | JSON serialized |
| embedding_model | embeddingModel | string | Model identifier |

**JSON Encoding**:
```json
{
  "_type": "VectorItem1536",
  "id": "chunk-123",
  "floatVector": [0.1, 0.2, ...],
  "text": "chunk content",
  "metadata": "{\"key\": \"value\"}",
  "chunkMetadata": "{\"document_id\": \"doc-1\"}",
  "embeddingModel": "text-embedding-3-small"
}
```

### 8. Search Response Format

```json
{
  "hit_count": 5,
  "hits": [
    {
      "hit": {
        "*": {
          "id": "chunk-123",
          "floatVector": [0.1, 0.2, ...],
          "text": "content",
          "metadata": "{}",
          "chunkMetadata": "{}",
          "embeddingModel": "model"
        }
      },
      "score()": 0.95
    }
  ]
}
```

**Score Extraction**: `hit.get("score()", 1.0)`

### 9. Hybrid Search Implementation

llama-stack uses **client-side aggregation**:
1. Execute vector search → get results + scores
2. Execute keyword search → get results + scores
3. Combine using `WeightedInMemoryAggregator`:
   - **RRF** (Reciprocal Rank Fusion): `1 / (k + rank)`
   - **Weighted**: `alpha * vector_score + (1-alpha) * keyword_score`
4. Sort by combined score and return top-k

**No native hybrid search in Infinispan** - must be implemented client-side.

## Answers to Open Questions

### 1. Vector Search Syntax ✅
- **Native support**: YES, via `@Vector` annotation
- **Ickle syntax**: `WHERE floatVector <-> [vector] ~ k`
- **Distance function**: Cosine similarity (configurable in annotation)
- **Score range**: 0-1 (cosine similarity)

### 2. Embedding Storage Format ✅
- **Format**: Protobuf `repeated float` (JSON array in REST API)
- **Serialization**: JSON with `_type` field
- **Performance**: Good - native vector indexing

### 3. Index Configuration ✅
- **Type**: Lucene-based with vector extension
- **Storage**: `local-heap` (in-memory) or `filesystem`
- **Required**: XML cache config + Protobuf schema registration

### 4. Batch Operations ❌
- **No bulk upsert**: Must iterate individual PUT requests
- **Workaround**: Use concurrent requests (httpx async)
- **Future**: Could use batch API if available

### 5. Authentication ✅
- **Mechanisms**: Basic or Digest (Digest recommended)
- **Implementation**: httpx auth handlers
- **TLS**: Optional with `verify_ssl` flag

## Implementation Recommendations

### For Memory Service Go Implementation

#### 1. REST Client Structure
```go
type InfinispanClient struct {
    baseURL    string
    httpClient *http.Client
    username   string
    password   string
    authType   string // "basic" or "digest"
}
```

#### 2. Schema Management
- Embed Protobuf template in Go binary
- Replace `{DIMENSION}` placeholder at runtime
- Register schema before cache creation
- Cache schema name: `vector_chunk_{dimension}.proto`

#### 3. Cache Naming Convention
Similar to Qdrant:
```go
func cacheName(cfg *config.Config) string {
    prefix := cfg.InfinispanCachePrefix // default: "memory-service"
    model := cfg.EmbedType // e.g., "openai-text-embedding-3-small"
    dim := cfg.OpenAIDimensions // e.g., 1536
    return fmt.Sprintf("%s_%s-%d", prefix, model, dim)
}
```

#### 4. Query Builder
```go
func buildVectorQuery(embedding []float32, k int, dimension int) string {
    vectorStr := floatSliceToString(embedding)
    entity := fmt.Sprintf("VectorItem%d", dimension)
    return fmt.Sprintf(
        "SELECT i, score(i) FROM %s i WHERE i.floatVector <-> %s ~ %d",
        entity, vectorStr, k,
    )
}
```

#### 5. Error Handling
- 404: Cache/entry not found (acceptable for delete)
- 200/204: Success
- 400: Invalid query/config
- 401/403: Authentication failure
- 500: Server error

#### 6. Concurrency
- Use goroutines for batch upsert
- Limit concurrent requests (e.g., 10 workers)
- Use `errgroup` for error collection

### Configuration Mapping

| Memory Service Config | Infinispan Config | Default |
|-----------------------|-------------------|---------|
| `InfinispanURL` | `url` | `http://localhost:11222` |
| `InfinispanUsername` | `username` | `admin` |
| `InfinispanPassword` | `password` | (empty) |
| `InfinispanUseTLS` | `use_https` | `false` |
| `InfinispanAuthType` | `auth_mechanism` | `digest` |
| `InfinispanVerifySSL` | `verify_ssl` | `true` |
| `InfinispanCacheName` | cache name | (auto-generated) |

### Migration Strategy

1. **Check cache existence** (HEAD request)
2. **Register Protobuf schema** if cache doesn't exist
3. **Create cache** with XML config
4. **Verify indexing** is enabled

### Testing Strategy

1. **Unit tests**: Mock HTTP client
2. **Integration tests**: Real Infinispan instance
3. **BDD tests**: Cucumber scenarios
4. **Performance tests**: Batch operations, concurrent queries

## Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| No bulk upsert | Medium | Use concurrent goroutines |
| REST overhead vs gRPC | Low | HTTP/2 + connection pooling |
| Schema version conflicts | Medium | Include dimension in schema name |
| Cache config complexity | Low | Embed XML template |
| Auth mechanism differences | Low | Support both Basic and Digest |

## Next Steps

1. ✅ **Phase 1 Complete**: Research and analysis
2. **Phase 2**: Implement core Go code
   - Create `internal/plugin/vector/infinispan/` directory
   - Implement REST client
   - Add schema management
   - Build query constructors
3. **Phase 3**: Configuration integration
4. **Phase 4**: Migration support
5. **Phase 5**: Testing
6. **Phase 6**: Documentation

## References

- llama-stack implementation: `/tmp/llama-stack/src/llama_stack/providers/remote/vector_io/infinispan/`
- Infinispan REST API v3: `http://localhost:11222/rest/v3/openapi`
- Protobuf schema: `vector_chunk.proto`
- Cache config: `cache_config.xml`
