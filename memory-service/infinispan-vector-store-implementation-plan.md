# Implementation Plan: Infinispan Vector Store Provider

## Analysis Summary

### Current State
- Memory service has 2 vector providers: **pgvector** (PostgreSQL) and **qdrant** (gRPC-based)
- Plugin architecture via `internal/registry/vector/plugin.go`
- Common interface: `VectorStore` with Search, Upsert, DeleteByConversationGroupID, IsEnabled, Name

### Infinispan Capabilities (from OpenAPI v3)
- **REST API v16.2.0-SNAPSHOT** at `http://localhost:11222/rest/v3/`
- **Indexed caches** with Ickle query language (SQL-like)
- **Protobuf schema** support for structured data
- **Search endpoint**: `/rest/v3/caches/{cacheName}/_search` (GET/POST)
- **Index management**: clear, reindex, schema updates
- **Entry CRUD**: PUT/GET/DELETE at `/rest/v3/caches/{cacheName}/entries/{key}`
- **Content types**: JSON, XML, YAML, Protobuf

### Key Challenge
Infinispan's OpenAPI doesn't explicitly expose "vector search" endpoints like Qdrant. However, the llama-stack reference implementation (which I cannot access from this workspace) likely uses:
1. Protobuf schema with vector/array fields
2. Ickle queries with distance/similarity functions
3. Indexed cache configuration

## Proposed Implementation Plan

### Phase 1: Research & Design (REQUIRED FIRST)
**Goal**: Understand how llama-stack implements vector search on Infinispan

**Tasks**:
1. **Access llama-stack code** - User needs to provide:
   - `/home/rigazilla/llama/llama-stack/src/llama_stack/providers/remote/vector_io/infinispan/` implementation
   - Key files: client code, schema definitions, query patterns
   
2. **Determine vector search approach**:
   - Does Infinispan support native vector similarity (cosine, euclidean)?
   - What Ickle query syntax is used for vector search?
   - How are embeddings stored (Protobuf bytes, JSON arrays)?
   - What index configuration is needed?

3. **Design decisions**:
   - Cache naming convention (similar to Qdrant's collection naming)
   - Protobuf schema structure for embeddings
   - REST client library (net/http vs specialized client)
   - Error handling and retry logic

### Phase 2: Core Implementation
**Location**: `internal/plugin/vector/infinispan/`

**Files to create**:
```
internal/plugin/vector/infinispan/
├── infinispan.go          # Main plugin implementation
├── client.go              # REST API client wrapper
├── schema.go              # Protobuf schema management
└── queries.go             # Ickle query builders
```

**Key components**:

1. **InfinispanStore struct**:
```go
type InfinispanStore struct {
    client    *InfinispanClient
    cacheName string
    baseURL   string
}
```

2. **REST Client** (`client.go`):
   - HTTP client with authentication (basic auth, bearer token)
   - Methods: CreateCache, PutEntry, GetEntry, DeleteEntry, Search
   - Content-Type negotiation (JSON vs Protobuf)
   - Connection pooling and timeouts

3. **Schema Management** (`schema.go`):
   - Define Protobuf schema for vector embeddings
   - Schema registration via `/rest/v3/schemas/{schemaName}`
   - Example schema structure:
   ```protobuf
   message VectorEntry {
     string entry_id = 1;
     string conversation_id = 2;
     string conversation_group_id = 3;
     repeated float embedding = 4;
     string model = 5;
   }
   ```

4. **Query Builder** (`queries.go`):
   - Ickle query construction for vector search
   - Distance function integration (if available)
   - Filter by conversation_group_id
   - Pagination and limit handling

5. **VectorStore Interface Implementation**:
   - `Search()`: POST to `/_search` with Ickle query
   - `Upsert()`: PUT to `/entries/{entryId}` for each embedding
   - `DeleteByConversationGroupID()`: DELETE by query or iterate entries
   - `IsEnabled()`: Check cache existence via HEAD request
   - `Name()`: Return "infinispan"

### Phase 3: Configuration Integration

**Config additions** (in `internal/config/config.go`):
```go
// Infinispan vector store
InfinispanURL          string // e.g., http://localhost:11222
InfinispanCacheName    string // default: "memory-service-vectors"
InfinispanUsername     string
InfinispanPassword     string
InfinispanUseTLS       bool
InfinispanTimeout      time.Duration
```

**Environment variables**:
- `MEMORY_SERVICE_INFINISPAN_URL`
- `MEMORY_SERVICE_INFINISPAN_CACHE_NAME`
- `MEMORY_SERVICE_INFINISPAN_USERNAME`
- `MEMORY_SERVICE_INFINISPAN_PASSWORD`
- `MEMORY_SERVICE_INFINISPAN_USE_TLS`

### Phase 4: Migration Support

**Migrator implementation**:
```go
type infinispanMigrator struct{}

func (m *infinispanMigrator) Migrate(ctx context.Context) error {
    // 1. Register Protobuf schema
    // 2. Create indexed cache with proper config
    // 3. Verify cache is searchable
}
```

**Register with order 200** (same as pgvector/qdrant)

### Phase 5: Testing

**Test files**:
1. `infinispan_test.go` - Unit tests with mock HTTP client
2. `infinispan_integration_test.go` - Integration tests (requires running Infinispan)
3. BDD feature file: `internal/bdd/features/vector_infinispan.feature`

**Test scenarios**:
- Cache creation and schema registration
- Upsert single and batch embeddings
- Vector search with conversation group filtering
- Delete by conversation group
- Error handling (connection failures, invalid queries)
- Authentication (with/without credentials)

### Phase 6: Documentation

**Updates needed**:
1. `AGENTS.md` - Add Infinispan to vector store providers list
2. `site/src/pages/docs/configuration.mdx` - Document Infinispan config options
3. `README.md` - Add Infinispan to supported backends
4. `internal/plugin/vector/infinispan/README.md` - Provider-specific docs

## Open Questions (MUST RESOLVE BEFORE CODING)

1. **Vector search syntax**: What Ickle query performs cosine similarity search?
   - Does Infinispan have built-in vector distance functions?
   - Or does llama-stack implement custom scoring?

2. **Embedding storage format**:
   - Protobuf `repeated float` vs JSON array vs binary blob?
   - Performance implications of each approach?

3. **Index configuration**:
   - What index type is used (Lucene-based)?
   - Required cache configuration XML/JSON?

4. **Batch operations**:
   - Does Infinispan support bulk upsert via REST?
   - Or must we iterate individual PUT requests?

5. **Authentication**:
   - Basic auth vs bearer token vs client certificates?
   - How does llama-stack handle auth?

## Next Steps

**IMMEDIATE ACTION REQUIRED**:
1. User must provide llama-stack Infinispan implementation code
2. Analyze how vector search queries are constructed
3. Identify any Infinispan-specific vector search extensions
4. Determine if custom distance calculation is needed

**Once research is complete**:
1. Create `internal/plugin/vector/infinispan/` directory
2. Implement REST client with schema management
3. Add configuration options
4. Write tests
5. Update documentation

## Estimated Effort
- **Research**: 2-4 hours (depends on llama-stack code complexity)
- **Implementation**: 6-8 hours
- **Testing**: 3-4 hours
- **Documentation**: 1-2 hours
- **Total**: ~12-18 hours

## Risk Assessment
- **HIGH**: Unknown vector search capabilities in Infinispan
- **MEDIUM**: REST API complexity vs gRPC (qdrant)
- **LOW**: Plugin architecture is well-established
