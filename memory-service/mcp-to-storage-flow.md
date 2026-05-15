# MCP to Memory Service Storage Flow

This document clarifies **who processes what** when Claude Code (or another AI assistant) stores information via the MCP server.

## Short Answer

**The AI assistant decides what to store**, calls the MCP tool, and the data goes to memory storage with **minimal additional processing**. The memory service's LLM (embedding model) is used **asynchronously** for search indexing, **not** for content transformation.

---

## Detailed Flow

### 1. AI Assistant Decision-Making

```
┌──────────────────────────────────────────────────────────────┐
│              CLAUDE CODE (AI Assistant)                      │
│                                                              │
│  • User completes a task (e.g., "fix cache bug")            │
│  • Claude Code decides what's worth saving                   │
│  • Extracts key information:                                 │
│    - What was the problem?                                   │
│    - What was the solution?                                  │
│    - Which files were changed?                               │
│    - Any gotchas discovered?                                 │
│                                                              │
│  • Decides: "I should save this as a session note"          │
│                                                              │
│  • Calls MCP tool: save_session_notes()                     │
│    - title: "Fixed cache serialization bug"                 │
│    - notes: "The Entry type had asymmetric JSON..."         │
│    - tags: "bugfix,cache,go"                                 │
└────────────────────┬─────────────────────────────────────────┘
                     │
                     │ MCP Protocol
                     ▼
┌──────────────────────────────────────────────────────────────┐
│              memory-service-mcp (MCP Server)                 │
│                                                              │
│  📍 internal/cmd/mcp/tools.go:98                             │
│  func handleSaveSessionNotes():                              │
│  1. Formats title: "[claude-code] 2026-05-14 - {title}"     │
│  2. Combines notes + tags into text                          │
│  3. Creates conversation via HTTP API (line 107)             │
│  4. Appends entry with content (line 134):                   │
│     {                                                        │
│       "role": "USER",                                        │
│       "text": "{notes}\n\n---\nTags: {tags}"                │
│     }                                                        │
│                                                              │
│  ⚠️ NO LLM processing here - just formatting!                │
└────────────────────┬─────────────────────────────────────────┘
                     │
                     │ HTTP REST API
                     ▼
┌──────────────────────────────────────────────────────────────┐
│              MEMORY SERVICE (Go Backend)                     │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ POST /v1/conversations                                 │ │
│  │ 📍 internal/plugin/route/conversations/conversations.go│ │
│  │    Line 45: Route definition                           │ │
│  │    Line 125: func createConversation()                 │ │
│  │ Creates conversation with title                        │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ POST /v1/conversations/{id}/entries                    │ │
│  │ 📍 internal/plugin/route/entries/entries.go            │ │
│  │    Line 32: Route definition                           │ │
│  │    Line 124: func appendEntry()                        │ │
│  │                                                        │ │
│  │ Request body:                                          │ │
│  │ {                                                      │ │
│  │   "channel": "history",                                │ │
│  │   "contentType": "history",                            │ │
│  │   "content": [                                         │ │
│  │     {                                                  │ │
│  │       "role": "USER",                                  │ │
│  │       "text": "The Entry type had..."                 │ │
│  │     }                                                  │ │
│  │   ],                                                   │ │
│  │   "indexedContent": null  ⬅️ MCP doesn't set this!    │ │
│  │ }                                                      │ │
│  │                                                        │ │
│  │ Storage operations:                                    │ │
│  │ 1. Validates request                                   │ │
│  │ 2. Checks user permissions                             │ │
│  │ 3. Writes to database (Postgres/SQLite/Mongo)          │ │
│  │    📍 internal/plugin/store/{postgres,sqlite,mongo}/  │ │
│  │ 4. Updates cache (Redis/Infinispan/local)              │ │
│  │    📍 internal/plugin/cache/{redis,infinispan,local}/ │ │
│  │ 5. Returns entry ID                                    │ │
│  │                                                        │ │
│  │ ⚠️ Entry is stored immediately WITHOUT embedding!      │ │
│  │    The entry is marked as "pending indexing"           │ │
│  └────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
                     │
                     │ (Data is now persisted)
                     │
                     │ Later (async)...
                     ▼
┌──────────────────────────────────────────────────────────────┐
│           BACKGROUND INDEXER (runs every 30s)                │
│           📍 internal/service/indexer.go                     │
│                                                              │
│  Line 45: func Start() - main loop                           │
│  Line 64: func indexBatch() - process batch                  │
│                                                              │
│  1. Queries database for unindexed entries (line 68)         │
│     - Looks for entries where indexed_at IS NULL            │
│     - Requires indexedContent to be non-null/non-empty      │
│                                                              │
│  ⚠️ MCP entries have indexedContent=null, so they           │
│     WON'T be indexed automatically!                          │
│                                                              │
│  For entries WITH indexedContent:                            │
│  2. Batch collect entry texts (line 92)                      │
│  3. Call embedding model (line 96):                          │
│     📍 internal/plugin/embed/openai/openai.go:87             │
│     embeddings = EmbedTexts(["text1", "text2", ...])        │
│  4. Store embeddings in vector store (line 112)              │
│     📍 internal/plugin/vector/{pgvector,qdrant,...}/         │
│     (PGVector/Qdrant/SQLite-vec/Infinispan)                 │
│  5. Mark entries as indexed (line 124)                       │
│     indexed_at = NOW()                                       │
│  6. Trigger knowledge clustering (line 138, optional)        │
│     📍 internal/knowledge/clusterer.go                       │
└──────────────────────────────────────────────────────────────┘
```

---

## What the Memory Service LLM Does

### The Embedding Model (e.g., `text-embedding-3-small`)

**Purpose:** Generate vector embeddings for **semantic search**

**When it runs:**
- **Asynchronously** via background indexer (every 30 seconds)
- **On-demand** when user searches (embeds search query only)

**What it processes:**
- The `indexedContent` field from entries
- Search queries (to find similar entries)

**Configuration:**
```bash
MEMORY_SERVICE_EMBEDDING_KIND=openai
MEMORY_SERVICE_OPENAI_API_KEY=sk-...
```

**Models supported:**
- `openai` - OpenAI API (`text-embedding-3-small`, `text-embedding-3-large`)
- `local` - Local embedding models (e.g., `all-MiniLM-L6-v2` via sentence-transformers)
- `none` - Disabled (only keyword search available)

---

## Processing Breakdown: MCP Session Notes

### What Claude Code Does:
✅ **Decides** what's important to save  
✅ **Summarizes** the work done  
✅ **Extracts** key details (problem, solution, files changed)  
✅ **Formats** as structured notes  
✅ **Adds** tags for categorization  

### What MCP Server Does:
✅ Adds `[claude-code]` prefix to title  
✅ Adds timestamp to title  
✅ Combines notes + tags into text field  
✅ Makes HTTP POST to memory service  
❌ **Does NOT** call any LLM  
❌ **Does NOT** transform content  
❌ **Does NOT** set `indexedContent`  

### What Memory Service Does (Immediately):
✅ Validates request format  
✅ Checks user authentication/authorization  
✅ Writes conversation to database  
✅ Writes entry to database  
✅ Updates cache  
✅ Returns entry ID  
❌ **Does NOT** call embedding model yet  
❌ **Does NOT** index for search yet  

### What Memory Service Does (Later, Async):
⚠️ **For MCP entries: NOTHING** (because `indexedContent` is null)  

For entries that DO have `indexedContent`:  
✅ Background indexer finds them  
✅ Calls OpenAI/local embedding model  
✅ Stores vector embeddings  
✅ Enables semantic search  

---

## Information Retrieval Flow

When the AI assistant needs to recall past information, there are three main retrieval patterns:

### 1. Semantic Search (search_sessions)

```
┌──────────────────────────────────────────────────────────────┐
│              CLAUDE CODE (AI Assistant)                      │
│                                                              │
│  • Working on new task: "implement authentication"          │
│  • Decides: "I should check if we've done this before"      │
│  • Calls: search_sessions(query="authentication setup")     │
└────────────────────┬─────────────────────────────────────────┘
                     │
                     │ MCP Protocol
                     ▼
┌──────────────────────────────────────────────────────────────┐
│              memory-service-mcp (MCP Server)                 │
│              📍 internal/cmd/mcp/tools.go                    │
│                                                              │
│  Line 145: func handleSearchSessions()                       │
│  • Receives: query="authentication setup", limit=5          │
│  • Makes HTTP call to memory service (line 153)              │
└────────────────────┬─────────────────────────────────────────┘
                     │
                     │ POST /v1/conversations/search
                     │ {
                     │   "query": "authentication setup",
                     │   "searchType": "auto",
                     │   "limit": 5,
                     │   "includeEntry": true
                     │ }
                     ▼
┌──────────────────────────────────────────────────────────────┐
│              MEMORY SERVICE (Go Backend)                     │
│              📍 internal/plugin/route/search/search.go       │
│                                                              │
│  Line 42: Route POST "/conversations/search"                 │
│  Line 68: func searchConversations()                         │
│  Line 131: executeAutoSearch() - try semantic first          │
│                                                              │
│  Search Handler (auto mode):                                 │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ 1. Try Semantic Search (if enabled):                   │ │
│  │    📍 Line 468: func doSemanticSearch()                │ │
│  │                                                        │ │
│  │    a) Get user's conversation group IDs (line 474)     │ │
│  │                                                        │ │
│  │    b) Embed the query using LLM (line 480):            │ │
│  │       📍 internal/plugin/embed/openai/openai.go:87     │ │
│  │       embeddings = EmbedTexts(["authentication setup"])│ │
│  │       ⬅️ OpenAI/local model called HERE                 │ │
│  │                                                        │ │
│  │    c) Search vector store (line 485):                  │ │
│  │       📍 internal/plugin/vector/{type}/search.go       │ │
│  │       vectorResults = VectorSearch(                    │ │
│  │         embedding: [0.123, -0.456, ...],               │ │
│  │         groupIDs: [user's groups],                     │ │
│  │         limit: 100 candidates                          │ │
│  │       )                                                │ │
│  │       Returns: [{entryID, score}, ...]                │ │
│  │                                                        │ │
│  │    d) Fetch entry metadata from DB (line 499):         │ │
│  │       📍 internal/plugin/store/{type}/entries.go       │ │
│  │       entries = FetchEntriesByIDs(entryIDs)            │ │
│  │                                                        │ │
│  │    e) Filter by user permissions (line 511)            │ │
│  │                                                        │ │
│  │    f) Group by conversation (line 532)                 │ │
│  │       - Keep highest-scoring entry per conversation    │ │
│  │                                                        │ │
│  │    g) Sort by score descending (line 556)              │ │
│  │                                                        │ │
│  │    h) Limit to requested count (5, line 563)           │ │
│  │                                                        │ │
│  │ 2. Fallback to Fulltext Search (line 248):            │ │
│  │    📍 internal/plugin/store/{type}/search.go           │ │
│  │    a) SQLite FTS5 or PostgreSQL text search            │ │
│  │    b) Match keywords in indexedContent                 │ │
│  │    c) No LLM involved ❌                                │ │
│  │                                                        │ │
│  │ 3. Build response (line 244):                          │ │
│  │    {                                                   │ │
│  │      "data": [                                         │ │
│  │        {                                               │ │
│  │          "conversationId": "uuid-123",                 │ │
│  │          "conversationTitle": "[claude-code] ...",     │ │
│  │          "score": 0.89,                                │ │
│  │          "highlights": "...authentication with OAuth", │ │
│  │          "entry": {                                    │ │
│  │            "id": "entry-uuid",                         │ │
│  │            "content": [{                               │ │
│  │              "role": "USER",                           │ │
│  │              "text": "Set up OAuth 2.0 authentication" │ │
│  │            }],                                         │ │
│  │            "createdAt": "2026-05-10T..."               │ │
│  │          }                                             │ │
│  │        }                                               │ │
│  │      ]                                                 │ │
│  │    }                                                   │ │
│  └────────────────────────────────────────────────────────┘ │
└────────────────────┬─────────────────────────────────────────┘
                     │
                     │ HTTP Response (JSON)
                     ▼
┌──────────────────────────────────────────────────────────────┐
│              memory-service-mcp (MCP Server)                 │
│              📍 internal/cmd/mcp/tools.go                    │
│                                                              │
│  Line 145: handleSearchSessions() continued                  │
│  • Receives JSON response (line 159)                         │
│  • Formats as markdown for Claude (lines 171-192):           │
│                                                              │
│    Found 1 result(s):                                        │
│                                                              │
│    ### 1. [claude-code] 2026-05-10 - OAuth setup            │
│    - Conversation ID: `uuid-123`                             │
│    - Score: 0.89                                             │
│    - Highlights: ...authentication with OAuth...            │
│    - Content: {"role":"USER","text":"Set up OAuth..."}      │
│                                                              │
│  • Returns as MCP tool result (line 194)                     │
└────────────────────┬─────────────────────────────────────────┘
                     │
                     │ MCP Protocol
                     ▼
┌──────────────────────────────────────────────────────────────┐
│              CLAUDE CODE (AI Assistant)                      │
│                                                              │
│  • Receives formatted search results                         │
│  • Reads the past solution                                   │
│  • Uses context to inform current work:                      │
│    "I see we previously used OAuth 2.0. Let me check if      │
│     that's still the right approach..."                      │
│                                                              │
│  • May call: get_session(conversation_id) for full details   │
│    📍 tools.go:240 - handleGetSession()                      │
└──────────────────────────────────────────────────────────────┘
```

### 2. List Recent Sessions (list_sessions)

```
Claude Code                MCP Server                      Memory Service
    │                          │                               │
    │ list_sessions(limit=10)  │                               │
    ├─────────────────────────>│                               │
    │                          │ 📍 tools.go:197                │
    │                          │                               │
    │                          │ GET /v1/conversations         │
    │                          │   ?limit=10                   │
    │                          ├──────────────────────────────>│
    │                          │                               │ 📍 conversations.go:100
    │                          │                               │ func listConversations()
    │                          │                               │
    │                          │                               │ Query DB:
    │                          │                               │ SELECT * FROM conversations
    │                          │                               │ WHERE user_id IN (accessible)
    │                          │                               │ AND archived_at IS NULL
    │                          │                               │ ORDER BY updated_at DESC
    │                          │                               │ LIMIT 10
    │                          │                               │
    │                          │                               │ No LLM/embedding needed ❌
    │                          │<──────────────────────────────┤
    │                          │ {                             │
    │                          │   "data": [                   │
    │                          │     {                         │
    │                          │       "id": "...",            │
    │                          │       "title": "...",         │
    │                          │       "updatedAt": "..."      │
    │                          │     }                         │
    │                          │   ]                           │
    │                          │ }                             │
    │                          │                               │
    │                          │ Format as markdown (line 219):│
    │                          │ "Recent sessions (10):"       │
    │                          │ "1. **Title** - preview"      │
    │<─────────────────────────┤                               │
    │                          │                               │
    │ Displays list to user    │                               │
    │ or uses for context      │                               │
```

### 3. Get Full Session Details (get_session)

```
Claude Code                MCP Server                      Memory Service
    │                          │                               │
    │ get_session(             │                               │
    │   conversation_id=       │                               │
    │   "uuid-123"             │                               │
    │ )                        │                               │
    ├─────────────────────────>│                               │
    │                          │ 📍 tools.go:240                │
    │                          │ func handleGetSession()       │
    │                          │                               │
    │                          │ Step 1: Get metadata (line 245)│
    │                          │ GET /v1/conversations/        │
    │                          │     {uuid-123}                │
    │                          ├──────────────────────────────>│
    │                          │                               │ 📍 conversations.go:227
    │                          │                               │ func getConversation()
    │                          │                               │
    │                          │                               │ Query DB:
    │                          │                               │ SELECT * FROM conversations
    │                          │                               │ WHERE id = 'uuid-123'
    │                          │                               │ AND user has access
    │                          │<──────────────────────────────┤
    │                          │ {                             │
    │                          │   "id": "uuid-123",           │
    │                          │   "title": "...",             │
    │                          │   "createdAt": "...",         │
    │                          │   "updatedAt": "..."          │
    │                          │ }                             │
    │                          │                               │
    │                          │ Step 2: Get entries (line 256)│
    │                          │ GET /v1/conversations/        │
    │                          │     {uuid-123}/entries        │
    │                          │   ?limit=100                  │
    │                          ├──────────────────────────────>│
    │                          │                               │ 📍 entries.go:55
    │                          │                               │ func listEntries()
    │                          │                               │
    │                          │                               │ Query DB:
    │                          │                               │ SELECT * FROM entries
    │                          │                               │ WHERE conversation_id = 'uuid-123'
    │                          │                               │ AND channel = 'history'
    │                          │                               │ ORDER BY created_at ASC
    │                          │                               │ LIMIT 100
    │                          │                               │
    │                          │                               │ Fetch from cache if available:
    │                          │                               │ - Check Redis/Infinispan
    │                          │                               │ - Fallback to DB if not cached
    │                          │                               │
    │                          │                               │ No LLM/embedding needed ❌
    │                          │<──────────────────────────────┤
    │                          │ {                       │
    │                          │   "data": [             │
    │                          │     {                   │
    │                          │       "id": "...",      │
    │                          │       "content": [{     │
    │                          │         "role": "USER", │
    │                          │         "text": "..."   │
    │                          │       }],               │
    │                          │       "createdAt": "..." │
    │                          │     },                  │
    │                          │     { ... }             │
    │                          │   ]                     │
    │                          │ }                       │
    │                          │                         │
    │                          │ Format as markdown:     │
    │                          │ # {title}               │
    │                          │ Created: ... | Updated: │
    │                          │                         │
    │                          │ ---                     │
    │                          │ **Entry** (timestamp)   │
    │                          │ {content JSON}          │
    │<─────────────────────────┤                         │
    │                          │                         │
    │ Reads full conversation  │                         │
    │ history for context      │                         │
```

### Retrieval Performance Optimizations

The memory service uses several techniques to make retrieval fast:

1. **Caching Layer** (Redis/Infinispan/local):
   - Frequently accessed conversations cached
   - Entry lists cached by conversation + channel + epoch
   - Cache-aside pattern (check cache → miss → load from DB → populate cache)

2. **Vector Indexing** (for semantic search):
   - Pre-computed embeddings stored in specialized vector DB
   - Fast approximate nearest neighbor (ANN) search
   - Typically returns results in <100ms even with millions of vectors

3. **Database Indexing**:
   - B-tree indexes on: conversation_id, user_id, created_at, updated_at
   - FTS5 full-text index for keyword search (SQLite)
   - GIN indexes for text search (PostgreSQL)

4. **Batch Operations**:
   - Search returns candidate entry IDs first (lightweight)
   - Fetches full entry content only for top results
   - Joins conversation metadata in single query

---

## Key Insights

### ❓ Does the AI assistant decide what to store?
**YES** - Claude Code (or any MCP client) decides when and what to save. The memory service is just storage.

### ❓ Is there processing between MCP call and storage?
**Minimal** - Only formatting (title prefix, timestamp, JSON structure). No AI/LLM processing.

### ❓ What is the memory service's LLM used for?
**Semantic search only:**
1. **Background indexing** - Converts text to vectors (if `indexedContent` is provided)
2. **Query embedding** - Converts search queries to vectors
3. **NOT for content transformation or summarization**

### ❓ Why isn't MCP data indexed for search?
The MCP server doesn't set `indexedContent` when creating entries. This means:
- ❌ MCP session notes won't appear in semantic search
- ✅ They WILL appear in:
  - List conversations API
  - Get conversation API  
  - Keyword/fulltext search (if enabled with SQLite FTS5)

### 🔧 How to make MCP notes searchable?

The MCP server would need to set `indexedContent`:

```go
// In tools.go, handleSaveSessionNotes:
indexedContent := content.String()
_, err = s.client.AppendConversationEntryWithResponse(ctx, *conv.Id, 
  apiclient.AppendConversationEntryJSONRequestBody{
    ContentType: contentType,
    Content:     entryContent,
    IndexedContent: &indexedContent,  // ⬅️ Add this!
  })
```

Then the background indexer would:
1. Find the entry (indexed_at IS NULL, indexedContent IS NOT NULL)
2. Generate embedding via OpenAI/local model
3. Store in vector store
4. Enable semantic search

---

## Summary Diagram

```
Claude Code          MCP Server           Memory Service         Background Indexer
    │                    │                       │                        │
    │ save_session_notes │                       │                        │
    ├───────────────────>│                       │                        │
    │                    │ POST /conversations   │                        │
    │                    ├──────────────────────>│                        │
    │                    │                       │ Write to DB            │
    │                    │                       │ (no embedding)         │
    │                    │<──────────────────────┤                        │
    │                    │ POST .../entries      │                        │
    │                    ├──────────────────────>│                        │
    │                    │                       │ Write entry            │
    │                    │                       │ indexed_at=NULL        │
    │                    │                       │ indexedContent=NULL    │
    │<───────────────────┤<──────────────────────┤                        │
    │ "Session saved"    │                       │                        │
    │                    │                       │                        │
    │                    │                       │   (30 seconds later)   │
    │                    │                       │<───────────────────────┤
    │                    │                       │ Find unindexed         │
    │                    │                       │ (indexedContent!=NULL) │
    │                    │                       │                        │
    │                    │                       │ ⚠️ MCP entry skipped   │
    │                    │                       │   (indexedContent=NULL)│
    │                    │                       │                        │
    │                    │                       │                        │
    │ search_sessions    │                       │                        │
    ├───────────────────>│                       │                        │
    │                    │ POST .../search       │                        │
    │                    ├──────────────────────>│                        │
    │                    │                       │ Embed query text       │
    │                    │                       │ (LLM called)           │
    │                    │                       │ Search vectors         │
    │                    │                       │ (MCP entry not found)  │
    │<───────────────────┤<──────────────────────┤                        │
    │ Results            │                       │                        │
```

---

## Complete Round-Trip Summary

### Storage Flow (Claude Code → Memory Service)

```
User finishes task
    ↓
Claude Code decides to save notes
    ↓
Formats: title, summary, tags
    ↓
MCP tool: save_session_notes()
    ↓
MCP server: add prefix, timestamp
    ↓
HTTP POST to memory service
    ↓
Memory service: validate, auth, write DB, cache
    ↓
Response: conversation ID
    ↓
Claude Code: "Session saved"
    
⏱️ Later (async): Background indexer may embed (if indexedContent set)
```

### Retrieval Flow (Claude Code ← Memory Service)

```
Claude Code needs past context
    ↓
Decides search query: "authentication setup"
    ↓
MCP tool: search_sessions(query="...")
    ↓
MCP server: HTTP POST /v1/conversations/search
    ↓
Memory service:
  1. Embed query via LLM ⬅️ ONLY LLM call
  2. Vector search for similar entries
  3. Fetch entry metadata from DB/cache
  4. Filter by permissions
  5. Sort by relevance score
    ↓
HTTP response: JSON with results
    ↓
MCP server: format as markdown
    ↓
Claude Code: reads and uses context
    ↓
May call: get_session(id) for full details
    ↓
Memory service: fetch from cache/DB (no LLM)
    ↓
Claude Code: applies past solution to current task
```

### LLM Usage Summary

| Operation | LLM Used? | When? | What For? |
|-----------|-----------|-------|-----------|
| Save session notes | ❌ No | - | AI assistant already processed content |
| Store entry | ❌ No | - | Direct DB write |
| Background indexing | ✅ Yes | Every 30s (async) | Convert `indexedContent` → vector embedding |
| Search query | ✅ Yes | On search request | Convert query text → vector embedding |
| List conversations | ❌ No | - | Direct DB query |
| Get conversation | ❌ No | - | Direct DB/cache read |
| Fulltext search | ❌ No | - | SQL FTS5/PostgreSQL text search |

**Key Point:** The memory service's LLM is **only** used for:
1. Embedding `indexedContent` during background indexing
2. Embedding search queries for semantic search

It is **never** used for:
- Content transformation
- Summarization
- Decision-making
- Validation

**TL;DR:** AI assistant does all content processing. Memory service just stores it and provides semantic search. The LLM (embedding model) is only used for vector similarity search, not content transformation.

---

## Source Code References

Here are the key files and functions for each operation in the flow:

### MCP Server (Go)

| Operation | File | Function/Line |
|-----------|------|---------------|
| Tool registration | `internal/cmd/mcp/tools.go` | Line 16-20: `registerTools()` |
| Save session notes | `internal/cmd/mcp/tools.go` | Line 98: `handleSaveSessionNotes()` |
| Search sessions | `internal/cmd/mcp/tools.go` | Line 145: `handleSearchSessions()` |
| List sessions | `internal/cmd/mcp/tools.go` | Line 197: `handleListSessions()` |
| Get session | `internal/cmd/mcp/tools.go` | Line 240: `handleGetSession()` |
| Append note | `internal/cmd/mcp/tools.go` | Line 296: `handleAppendNote()` |
| MCP command setup | `internal/cmd/mcp/cmd.go` | Line 28: `Command()` |
| Remote mode | `internal/cmd/mcp/cmd.go` | Line 40: `RemoteCommand()` |
| Embedded mode | `internal/cmd/mcp/cmd.go` | Line 60: `EmbeddedCommand()` |

### Memory Service API Routes (Go)

| Operation | File | Function/Line |
|-----------|------|---------------|
| **Conversations** |
| Route mounting | `internal/plugin/route/conversations/conversations.go` | Line 37: `MountRoutes()` |
| List conversations | `internal/plugin/route/conversations/conversations.go` | Line 42-43, Line 100: `listConversations()` |
| Create conversation | `internal/plugin/route/conversations/conversations.go` | Line 45-46, Line 125: `createConversation()` |
| Get conversation | `internal/plugin/route/conversations/conversations.go` | Line 48-49, Line 227: `getConversation()` |
| Update conversation | `internal/plugin/route/conversations/conversations.go` | Line 51-52: `updateConversation()` |
| **Entries** |
| Route mounting | `internal/plugin/route/entries/entries.go` | Line 24: `MountRoutes()` |
| List entries | `internal/plugin/route/entries/entries.go` | Line 29-30, Line 55: `listEntries()` |
| Append entry | `internal/plugin/route/entries/entries.go` | Line 32-33, Line 124: `appendEntry()` |
| Sync context | `internal/plugin/route/entries/entries.go` | Line 35-36: `syncMemory()` |
| **Search** |
| Route mounting | `internal/plugin/route/search/search.go` | Line 39: `MountRoutes()` |
| Search conversations | `internal/plugin/route/search/search.go` | Line 42, Line 68: `searchConversations()` |
| Semantic search | `internal/plugin/route/search/search.go` | Line 468: `doSemanticSearch()` |
| Execute auto search | `internal/plugin/route/search/search.go` | Line 131: `executeAutoSearch()` |

### Background Services (Go)

| Operation | File | Function/Line |
|-----------|------|---------------|
| Background indexer | `internal/service/indexer.go` | Line 18: `BackgroundIndexer` struct |
| Start indexer | `internal/service/indexer.go` | Line 45: `Start()` |
| Index batch | `internal/service/indexer.go` | Line 64: `indexBatch()` |
| Episodic indexer | `internal/service/episodic_indexer.go` | `EpisodicIndexer` struct |

### Embedding Providers (Go)

| Operation | File | Function/Line |
|-----------|------|---------------|
| Embedder interface | `internal/registry/embed/plugin.go` | Line 12: `Embedder` interface |
| EmbedTexts signature | `internal/registry/embed/plugin.go` | Line 14: `EmbedTexts()` |
| OpenAI embedder | `internal/plugin/embed/openai/openai.go` | Line 55: `OpenAIEmbedder` struct |
| OpenAI EmbedTexts | `internal/plugin/embed/openai/openai.go` | Line 87: `EmbedTexts()` |
| Local embedder | `internal/plugin/embed/local/local.go` | `LocalEmbedder` struct |

### Data Stores (Go)

| Operation | File Pattern | Description |
|-----------|--------------|-------------|
| Store interface | `internal/registry/store/store.go` | `MemoryStore` interface definition |
| PostgreSQL store | `internal/plugin/store/postgres/*.go` | PostgreSQL implementation |
| SQLite store | `internal/plugin/store/sqlite/*.go` | SQLite implementation |
| MongoDB store | `internal/plugin/store/mongo/*.go` | MongoDB implementation |
| Vector stores | `internal/plugin/vector/{pgvector,qdrant,sqlite}/*.go` | Vector search implementations |

### Cache Implementations (Go)

| Operation | File Pattern | Description |
|-----------|--------------|-------------|
| Cache interface | `internal/registry/cache/cache.go` | `Cache` interface definition |
| Redis cache | `internal/plugin/cache/redis/*.go` | Redis implementation |
| Infinispan cache | `internal/plugin/cache/infinispan/*.go` | Infinispan implementation |
| Local cache | `internal/plugin/cache/local/*.go` | In-memory Ristretto cache |

### Key Data Models (Go)

| Model | File | Struct/Line |
|-------|------|-------------|
| Conversation | `internal/model/conversation.go` | `Conversation` struct |
| Entry | `internal/model/entry.go` | `Entry` struct |
| Channel | `internal/model/entry.go` | `Channel` enum (history/context) |
| Search result | `internal/registry/store/store.go` | `SearchResult` struct |

### API Contracts (OpenAPI/Protobuf)

| Contract | File | Description |
|----------|------|-------------|
| REST API spec | `contracts/openapi/openapi.yml` | Full OpenAPI 3.1 specification |
| Admin API spec | `contracts/openapi/openapi-admin.yml` | Admin-only endpoints |
| gRPC service | `contracts/protobuf/memory/v1/memory_service.proto` | Protocol Buffers definition |

### Example Usage (Calling the Memory Service)

| Language | File | Description |
|----------|------|-------------|
| **Java (Quarkus)** |
| Chat resource | `java/quarkus/examples/chat-quarkus/src/main/java/org/acme/ChatResource.java` | REST endpoint that uses memory service |
| Recording agent | `java/quarkus/examples/chat-quarkus/src/main/java/org/acme/HistoryRecordingAgent.java` | `@RecordConversation` annotation usage |
| **TypeScript** |
| React chat panel | `frontends/chat-frontend/src/components/chat-panel.tsx` | Frontend chat UI using memory service |
| API client | `frontends/chat-frontend/src/client/services.gen.ts` | Auto-generated TypeScript client |

### Configuration & Deployment

| File | Purpose |
|------|---------|
| `main.go` | Binary entry point, CLI setup |
| `internal/config/config.go` | Configuration struct and defaults |
| `deploy/dev/air.toml` | Local dev hot-reload config |
| `compose.yaml` | Docker Compose for local development |
| `Taskfile.yml` | Task runner commands |

### LLM Call Sites

These are the **only** places where the embedding LLM is called:

| Location | File | Line | Context |
|----------|------|------|---------|
| Background indexer | `internal/service/indexer.go` | 96 | `embeddings, err := b.embedder.EmbedTexts(ctx, texts)` |
| Semantic search | `internal/plugin/route/search/search.go` | 480 | `embeddings, err := embedder.EmbedTexts(ctx, []string{query})` |
| Episodic indexer | `internal/service/episodic_indexer.go` | ~80 | `embeddings, err := idx.embedder.EmbedTexts(ctx, texts)` |

**All other operations do NOT involve LLM/embedding calls.**
