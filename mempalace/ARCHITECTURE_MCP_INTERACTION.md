# MemPalace MCP Interaction: AI Agent ↔ MCP Server ↔ Storage

## Overview

This document describes the interactive use case where an AI agent (Claude, or any MCP-compatible agent) actively queries and writes to MemPalace during conversations through the MCP (Model Context Protocol) server.

This is distinct from automatic background mining. Here, the agent makes deliberate tool calls to search memories, file new insights, query relationships, and maintain a personal diary.

---

## Architecture Overview

```
┌─────────────────┐
│   AI AGENT      │  Claude Code, custom agents, or any MCP client
│   (Claude/Bob)  │
└────────┬────────┘
         │
         │ MCP JSON-RPC over stdio
         │ (tools/call, tools/list, etc.)
         ▼
┌─────────────────────────────────────────────────────────────────┐
│  MCP SERVER                                                      │
│  mempalace/mcp_server.py                                        │
│                                                                  │
│  • 30+ tools exposed via JSON-RPC                               │
│  • Request validation & sanitization                            │
│  • Client/collection caching                                    │
│  • Write-ahead logging                                          │
│  • HNSW capacity monitoring                                     │
└────────┬────────────────────────────────────────────────────────┘
         │
         │ Python API calls
         ▼
┌─────────────────────────────────────────────────────────────────┐
│  STORAGE LAYER                                                   │
│                                                                  │
│  ┌──────────────────┐  ┌──────────────────┐  ┌───────────────┐ │
│  │  ChromaDB        │  │  Knowledge Graph │  │  WAL          │ │
│  │  (vector+BM25)   │  │  (SQLite)        │  │  (JSONL)      │ │
│  └──────────────────┘  └──────────────────┘  └───────────────┘ │
│                                                                  │
│  palace_path/                                                    │
│    ├── chroma.sqlite3                                            │
│    ├── <uuid>/index.bin                                          │
│    └── knowledge_graph.sqlite3                                   │
└──────────────────────────────────────────────────────────────────┘
```

---

## MCP Protocol Layer

### Connection Setup

**1. Client connects to MCP server**

The AI harness (Claude Code, custom agent) launches the MCP server as a subprocess:

```bash
mempalace-mcp --palace /path/to/palace
```

**2. Protocol handshake**

```json
→ {"jsonrpc": "2.0", "id": 1, "method": "initialize", 
   "params": {"protocolVersion": "2025-11-25"}}

← {"jsonrpc": "2.0", "id": 1, "result": {
    "protocolVersion": "2025-11-25",
    "capabilities": {"tools": {}},
    "serverInfo": {"name": "mempalace", "version": "0.4.0"}
  }}
```

**3. Tool discovery**

```json
→ {"jsonrpc": "2.0", "id": 2, "method": "tools/list"}

← {"jsonrpc": "2.0", "id": 2, "result": {
    "tools": [
      {
        "name": "mempalace_search",
        "description": "Semantic search. Returns verbatim drawer content...",
        "inputSchema": {
          "type": "object",
          "properties": {
            "query": {"type": "string", "description": "..."},
            "limit": {"type": "integer", ...},
            ...
          },
          "required": ["query"]
        }
      },
      ...
    ]
  }}
```

### Tool Invocation

Every tool call follows the same pattern:

```json
→ {"jsonrpc": "2.0", "id": 3, "method": "tools/call",
   "params": {
     "name": "mempalace_search",
     "arguments": {
       "query": "chromadb setup",
       "limit": 5,
       "wing": "wing_myproject"
     }
   }}

← {"jsonrpc": "2.0", "id": 3, "result": {
    "content": [
      {"type": "text", "text": "{ ... JSON result ... }"}
    ]
  }}
```

---

## Information Flow: Read Operations

### Example: `mempalace_search`

**Scenario:** Claude is asked "What did we decide about ChromaDB configuration?"

#### Step 1: AI Agent → MCP Server

```json
{
  "jsonrpc": "2.0",
  "id": 42,
  "method": "tools/call",
  "params": {
    "name": "mempalace_search",
    "arguments": {
      "query": "chromadb configuration decisions",
      "limit": 5,
      "max_distance": 1.5
    }
  }
}
```

#### Step 2: MCP Server Processing

**File:** `mcp_server.py:tool_search()`

```python
def tool_search(query, limit=5, wing=None, room=None, max_distance=1.5, ...):
    # 1. Validate & clamp limit
    limit = max(1, min(limit, 100))
    
    # 2. Sanitize inputs (prevent injection)
    wing = _sanitize_optional_name(wing, "wing")
    room = _sanitize_optional_name(room, "room")
    
    # 3. Query sanitization (prevent prompt contamination)
    sanitized = sanitize_query(query)
    
    # 4. Check HNSW capacity status
    _refresh_vector_disabled_flag()
    
    # 5. Delegate to search layer
    result = search_memories(
        sanitized["clean_query"],
        palace_path=_config.palace_path,
        wing=wing,
        room=room,
        n_results=limit,
        max_distance=max_distance,
        vector_disabled=_vector_disabled,
        collection_name=_config.collection_name,
    )
    
    # 6. Attach metadata
    if sanitized["was_sanitized"]:
        result["query_sanitized"] = True
    if _vector_disabled:
        result["vector_disabled"] = True
        
    return result
```

#### Step 3: Search Layer → ChromaDB

**File:** `searcher.py:search_memories()`

```python
def search_memories(query, palace_path, wing=None, room=None, 
                   n_results=5, max_distance=1.5, vector_disabled=False):
    # 1. Get collection (cached)
    col = get_collection(palace_path)
    
    # 2. Build ChromaDB where-filter
    where = {}
    if wing:
        where["wing"] = wing
    if room:
        where["room"] = room
    where_clause = where if where else None
    
    # 3. Vector search OR BM25 fallback
    if vector_disabled:
        # BM25-only via raw SQLite query
        results = _bm25_only_via_sqlite(query, where_clause, n_results)
    else:
        # Hybrid search (BM25 + HNSW)
        results = col.query(
            query_texts=[query],
            n_results=n_results,
            where=where_clause,
            include=["documents", "metadatas", "distances"]
        )
    
    # 4. Filter by distance threshold
    filtered = []
    for i, drawer_id in enumerate(results["ids"][0]):
        distance = results["distances"][0][i]
        if max_distance == 0 or distance <= max_distance:
            meta = results["metadatas"][0][i]
            doc = results["documents"][0][i]
            
            # Sanitize source_file (basename only, no full path)
            safe_meta = dict(meta)
            if safe_meta.get("source_file"):
                safe_meta["source_file"] = Path(safe_meta["source_file"]).name
            
            filtered.append({
                "drawer_id": drawer_id,
                "content": doc,
                "wing": meta.get("wing", ""),
                "room": meta.get("room", ""),
                "distance": round(distance, 3),
                "similarity": round(1 - distance, 3),
                "metadata": safe_meta
            })
    
    return {
        "results": filtered,
        "count": len(filtered),
        "query": query
    }
```

#### Step 4: ChromaDB Processing

**Vector search (when HNSW available):**
1. Embed query text via local model (sentence-transformers)
2. HNSW index lookup for approximate nearest neighbors
3. Compute cosine distances
4. BM25 keyword scoring via SQLite FTS
5. Hybrid score fusion (weighted combination)
6. Return top N results

**BM25-only fallback (when HNSW diverged):**
1. Direct SQLite query on `embedding_fulltext` table
2. Full-text search with `MATCH` operator
3. Rank by BM25 score
4. Return top N results

#### Step 5: MCP Server → AI Agent

```json
{
  "jsonrpc": "2.0",
  "id": 42,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "{
  \"results\": [
    {
      \"drawer_id\": \"drawer_wing_myproject_technical_abc123...\",
      \"content\": \"> how should we configure chromadb?\\n\\nUse hnsw:num_threads=1 to avoid the race condition in repairConnectionsForUpdate. Also set hnsw:space=cosine for similarity search.\",
      \"wing\": \"wing_myproject\",
      \"room\": \"technical\",
      \"distance\": 0.234,
      \"similarity\": 0.766,
      \"metadata\": {
        \"source_file\": \"session_2026-05-10.jsonl\",
        \"filed_at\": \"2026-05-10T14:23:00\",
        \"hall\": \"hall_decisions\"
      }
    },
    ...
  ],
  \"count\": 5,
  \"query\": \"chromadb configuration decisions\"
}"
      }
    ]
  }
}
```

#### Step 6: AI Agent Uses Result

Claude reads the search results and incorporates them into the response:

```
Based on our previous discussions, we decided to configure ChromaDB with:
- `hnsw:num_threads=1` to avoid the race condition in repairConnectionsForUpdate
- `hnsw:space=cosine` for similarity search

This was decided on May 10th during the technical architecture discussion.
```

---

## Information Flow: Write Operations

### Example: `mempalace_add_drawer`

**Scenario:** Claude wants to file a decision made during the conversation.

#### Step 1: AI Agent → MCP Server

```json
{
  "jsonrpc": "2.0",
  "id": 43,
  "method": "tools/call",
  "params": {
    "name": "mempalace_add_drawer",
    "arguments": {
      "wing": "wing_myproject",
      "room": "decisions",
      "content": "> should we use REST or GraphQL for the API?\n\nWe chose GraphQL because:\n1. Frontend needs nested data in single request\n2. Mobile bandwidth constraints favor precise queries\n3. Type system helps with API evolution\n\nTrade-off: More complex backend setup, but worth it for our use case.",
      "added_by": "claude"
    }
  }
}
```

#### Step 2: MCP Server Processing

**File:** `mcp_server.py:tool_add_drawer()`

```python
def tool_add_drawer(wing, room, content, source_file=None, added_by="mcp"):
    # 1. Sanitize inputs
    try:
        wing = sanitize_name(wing, "wing")
        room = sanitize_name(room, "room")
        content = sanitize_content(content)
    except ValueError as e:
        return {"success": False, "error": str(e)}
    
    # 2. Get collection (create if needed)
    col = _get_collection(create=True)
    if not col:
        return _no_palace()
    
    # 3. Generate deterministic drawer ID
    drawer_id = f"drawer_{wing}_{room}_{hashlib.sha256((wing + room + content).encode()).hexdigest()[:24]}"
    
    # 4. Write-ahead log (with content redaction)
    _wal_log("add_drawer", {
        "drawer_id": drawer_id,
        "wing": wing,
        "room": room,
        "added_by": added_by,
        "content_length": len(content),
        "content_preview": content[:200],
    })
    
    # 5. Idempotency check
    try:
        existing = col.get(ids=[drawer_id], include=[])
        if existing.ids:
            return {"success": True, "reason": "already_exists", "drawer_id": drawer_id}
    except Exception:
        pass
    
    # 6. Upsert to ChromaDB
    try:
        col.upsert(
            ids=[drawer_id],
            documents=[content],
            metadatas=[{
                "wing": wing,
                "room": room,
                "source_file": source_file or "",
                "chunk_index": 0,
                "added_by": added_by,
                "filed_at": datetime.now().isoformat(),
            }]
        )
        
        # 7. Verify write succeeded
        inserted = col.get(ids=[drawer_id], include=[])
        if not inserted.ids:
            raise RuntimeError("Drawer write was acknowledged but ID is not readable")
        
        # 8. Invalidate metadata cache
        _metadata_cache = None
        
        logger.info(f"Filed drawer: {drawer_id} → {wing}/{room}")
        return {"success": True, "drawer_id": drawer_id, "wing": wing, "room": room}
    except Exception as e:
        return {"success": False, "error": str(e)}
```

#### Step 3: ChromaDB Processing

1. **Embed content** via local model (sentence-transformers)
2. **Insert into SQLite:**
   - `embeddings` table (metadata + vector reference)
   - `embedding_metadata` table (key-value metadata)
   - `embedding_fulltext` table (BM25 index)
3. **Update HNSW index:**
   - Add vector to in-memory graph
   - Lazy flush to disk (not immediate)
4. **Return acknowledgment**

#### Step 4: Write-Ahead Log

**File:** `~/.mempalace/wal/write_log.jsonl`

```json
{
  "timestamp": "2026-05-15T10:23:45.123456",
  "operation": "add_drawer",
  "params": {
    "drawer_id": "drawer_wing_myproject_decisions_abc123...",
    "wing": "wing_myproject",
    "room": "decisions",
    "added_by": "claude",
    "content_length": 347,
    "content_preview": "[REDACTED 347 chars]"
  },
  "result": null
}
```

**Purpose:**
- Audit trail for memory poisoning detection
- Review/rollback capability for writes from external sources
- Content is redacted to avoid logging sensitive data

#### Step 5: MCP Server → AI Agent

```json
{
  "jsonrpc": "2.0",
  "id": 43,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "{
  \"success\": true,
  \"drawer_id\": \"drawer_wing_myproject_decisions_abc123...\",
  \"wing\": \"wing_myproject\",
  \"room\": \"decisions\"
}"
      }
    ]
  }
}
```

#### Step 6: AI Agent Confirmation

Claude acknowledges the write:

```
I've filed that decision to the palace under wing_myproject/decisions. 
It's now searchable and will be available in future conversations.
```

---

## Information Flow: Knowledge Graph

### Example: `mempalace_kg_add`

**Scenario:** Claude learns that Alice started a new role.

#### Step 1: AI Agent → MCP Server

```json
{
  "jsonrpc": "2.0",
  "id": 44,
  "method": "tools/call",
  "params": {
    "name": "mempalace_kg_add",
    "arguments": {
      "subject": "Alice",
      "predicate": "works_at",
      "object": "Anthropic",
      "valid_from": "2026-03-01"
    }
  }
}
```

#### Step 2: MCP Server Processing

**File:** `mcp_server.py:tool_kg_add()`

```python
def tool_kg_add(subject, predicate, object, valid_from=None, valid_to=None,
                source_closet=None, source_file=None, source_drawer_id=None):
    # 1. Sanitize inputs
    try:
        subject = sanitize_kg_value(subject, "subject")
        predicate = sanitize_name(predicate, "predicate")
        object = sanitize_kg_value(object, "object")
        valid_from = sanitize_iso_temporal(valid_from, "valid_from")
        valid_to = sanitize_iso_temporal(valid_to, "valid_to")
    except ValueError as e:
        return {"success": False, "error": str(e)}
    
    # 2. Write-ahead log
    _wal_log("kg_add", {
        "subject": subject,
        "predicate": predicate,
        "object": object,
        "valid_from": valid_from,
        "valid_to": valid_to,
        "source_drawer_id": source_drawer_id,
    })
    
    # 3. Call knowledge graph with retry on connection close
    triple_id = _call_kg(lambda kg: kg.add_triple(
        subject, predicate, object,
        valid_from=valid_from,
        valid_to=valid_to,
        source_closet=source_closet,
        source_file=source_file,
        source_drawer_id=source_drawer_id,
    ))
    
    return {
        "success": True,
        "triple_id": triple_id,
        "fact": f"{subject} → {predicate} → {object}"
    }
```

#### Step 3: Knowledge Graph Processing

**File:** `knowledge_graph.py:add_triple()`

```python
def add_triple(self, subject, predicate, object, valid_from=None, valid_to=None, ...):
    # 1. Resolve temporal bounds
    if valid_from is None:
        valid_from = date.today().isoformat()
    
    # 2. Generate triple ID
    triple_id = hashlib.sha256(
        f"{subject}|{predicate}|{object}|{valid_from}".encode()
    ).hexdigest()[:24]
    
    # 3. Insert into SQLite
    self.conn.execute("""
        INSERT OR REPLACE INTO triples 
        (id, subject, predicate, object, valid_from, valid_to, 
         source_closet, source_file, source_drawer_id, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """, (
        triple_id, subject, predicate, object, valid_from, valid_to,
        source_closet, source_file, source_drawer_id, datetime.now().isoformat()
    ))
    self.conn.commit()
    
    return triple_id
```

**Database schema:**
```sql
CREATE TABLE triples (
    id TEXT PRIMARY KEY,
    subject TEXT NOT NULL,
    predicate TEXT NOT NULL,
    object TEXT NOT NULL,
    valid_from TEXT,          -- ISO date/datetime
    valid_to TEXT,            -- NULL = still valid
    source_closet TEXT,
    source_file TEXT,
    source_drawer_id TEXT,    -- provenance
    created_at TEXT
);

CREATE INDEX idx_subject ON triples(subject);
CREATE INDEX idx_object ON triples(object);
CREATE INDEX idx_predicate ON triples(predicate);
CREATE INDEX idx_temporal ON triples(valid_from, valid_to);
```

#### Step 4: Query Example

Later, Claude can query:

```json
{
  "name": "mempalace_kg_query",
  "arguments": {
    "entity": "Alice",
    "direction": "outgoing"
  }
}
```

Response:
```json
{
  "entity": "Alice",
  "facts": [
    {
      "subject": "Alice",
      "predicate": "works_at",
      "object": "Anthropic",
      "valid_from": "2026-03-01",
      "valid_to": null,
      "is_current": true
    },
    {
      "subject": "Alice",
      "predicate": "child_of",
      "object": "Riley",
      "valid_from": null,
      "valid_to": null,
      "is_current": true
    }
  ],
  "count": 2
}
```

---

## Information Flow: Agent Diary

### Example: `mempalace_diary_write`

**Scenario:** Claude writes a session diary entry in AAAK format.

#### Step 1: AI Agent → MCP Server

```json
{
  "jsonrpc": "2.0",
  "id": 45,
  "method": "tools/call",
  "params": {
    "name": "mempalace_diary_write",
    "arguments": {
      "agent_name": "Claude",
      "entry": "SESSION:2026-05-15|helped.ALC.setup.chromadb.palace|TECH:hnsw.race.fix+cosine.similarity|DEC:graphql.over.rest(mobile.bw+nested.data)|★★★",
      "topic": "architecture"
    }
  }
}
```

#### Step 2: MCP Server Processing

**File:** `mcp_server.py:tool_diary_write()`

```python
def tool_diary_write(agent_name, entry, topic="general", wing=""):
    # 1. Sanitize inputs
    try:
        agent_name = sanitize_name(agent_name, "agent_name").lower()
        entry = sanitize_content(entry)
        topic = sanitize_name(topic, "topic")
    except ValueError as e:
        return {"success": False, "error": str(e)}
    
    # 2. Determine wing (agent-specific or project-specific)
    if wing:
        wing = sanitize_name(wing)
    else:
        wing = f"wing_{agent_name.replace(' ', '_')}"
    
    room = "diary"
    
    # 3. Get collection
    col = _get_collection(create=True)
    if not col:
        return _no_palace()
    
    # 4. Generate timestamped entry ID
    now = datetime.now()
    entry_id = f"diary_{wing}_{now.strftime('%Y%m%d_%H%M%S%f')}_{hashlib.sha256(entry.encode()).hexdigest()[:12]}"
    
    # 5. Write-ahead log
    _wal_log("diary_write", {
        "agent_name": agent_name,
        "topic": topic,
        "entry_id": entry_id,
        "entry_preview": entry[:200],
    })
    
    # 6. Add to ChromaDB
    try:
        col.add(
            ids=[entry_id],
            documents=[entry],
            metadatas=[{
                "wing": wing,
                "room": room,
                "hall": "hall_diary",
                "topic": topic,
                "type": "diary_entry",
                "agent": agent_name,
                "filed_at": now.isoformat(),
                "date": now.strftime("%Y-%m-%d"),
            }]
        )
        
        return {
            "success": True,
            "entry_id": entry_id,
            "agent": agent_name,
            "topic": topic,
            "timestamp": now.isoformat(),
        }
    except Exception as e:
        return {"success": False, "error": str(e)}
```

#### Step 3: Reading Diary Later

Another agent (or the same Claude in a future session) can read:

```json
{
  "name": "mempalace_diary_read",
  "arguments": {
    "agent_name": "Claude",
    "last_n": 10
  }
}
```

Response:
```json
{
  "agent": "claude",
  "entries": [
    {
      "date": "2026-05-15",
      "timestamp": "2026-05-15T10:45:00",
      "topic": "architecture",
      "content": "SESSION:2026-05-15|helped.ALC.setup.chromadb.palace|TECH:hnsw.race.fix+cosine.similarity|DEC:graphql.over.rest(mobile.bw+nested.data)|★★★"
    },
    ...
  ],
  "total": 47,
  "showing": 10
}
```

---

## Security & Safety Mechanisms

### 1. Input Sanitization

**Prevents injection attacks:**

```python
def sanitize_name(value: str, field_name: str) -> str:
    """Wing/room names: alphanumeric + underscore/hyphen only."""
    if not value or not isinstance(value, str):
        raise ValueError(f"{field_name} cannot be empty")
    
    # Strip leading/trailing whitespace
    value = value.strip()
    
    # Max length
    if len(value) > 128:
        raise ValueError(f"{field_name} too long (max 128 chars)")
    
    # Allowed chars: letters, digits, underscore, hyphen, dot
    if not re.match(r'^[a-zA-Z0-9_.\-]+$', value):
        raise ValueError(f"{field_name} contains invalid characters")
    
    return value
```

### 2. Query Sanitization

**Prevents prompt contamination (issue #333):**

```python
def sanitize_query(query: str) -> dict:
    """Strip system prompts, Claude Code tags, hook injection attempts."""
    original_length = len(query)
    
    # Remove system tags
    for tag in ["<system-reminder>", "<command-message>", ...]:
        query = re.sub(f"<{tag}.*?</{tag}>", "", query, flags=re.DOTALL)
    
    # Remove hook output blocks
    query = re.sub(r"<hook_output>.*?</hook_output>", "", query, flags=re.DOTALL)
    
    # Truncate to max length
    if len(query) > 250:
        query = query[:250]
    
    clean_length = len(query)
    
    return {
        "clean_query": query.strip(),
        "was_sanitized": clean_length != original_length,
        "method": "regex",
        "original_length": original_length,
        "clean_length": clean_length,
    }
```

### 3. Parameter Whitelisting

**Only schema-declared parameters accepted:**

```python
# Whitelist arguments to declared schema properties only.
schema_props = TOOLS[tool_name]["input_schema"].get("properties", {})
tool_args = {k: v for k, v in tool_args.items() if k in schema_props}
```

Prevents:
- Spoofing `added_by` to look like human-filed content
- Injecting fake `source_file` for provenance manipulation
- Passing internal-only parameters

### 4. Write-Ahead Logging

**Every write operation logged before execution:**

```python
def _wal_log(operation: str, params: dict, result: dict = None):
    # Redact sensitive content
    safe_params = {}
    for k, v in params.items():
        if k in {"content", "query", "text", "document"}:
            safe_params[k] = f"[REDACTED {len(v)} chars]"
        else:
            safe_params[k] = v
    
    entry = {
        "timestamp": datetime.now().isoformat(),
        "operation": operation,
        "params": safe_params,
        "result": result,
    }
    
    # Append to ~/.mempalace/wal/write_log.jsonl
    with os.fdopen(os.open(str(_WAL_FILE), os.O_WRONLY | os.O_APPEND), "a") as f:
        f.write(json.dumps(entry) + "\n")
```

**File permissions:** `0o600` (user read/write only)

### 5. Path Sanitization

**Source file paths reduced to basename before MCP export:**

```python
# Reduce to basename to avoid leaking full filesystem paths
safe_meta = dict(meta)
if safe_meta.get("source_file"):
    safe_meta["source_file"] = Path(safe_meta["source_file"]).name
```

Prevents:
- Leaking user's home directory structure
- Information disclosure in multi-tenant MCP setups

### 6. HNSW Capacity Guard

**Prevents segfaults from index divergence:**

```python
def _refresh_vector_disabled_flag():
    """Probe HNSW capacity before loading segment."""
    try:
        info = hnsw_capacity_status(_config.palace_path, _config.collection_name)
    except Exception:
        return
    
    if info.get("diverged"):
        # Segment count << SQLite count → loading segment would SIGSEGV
        _vector_disabled = True
        _vector_disabled_reason = info.get("message", "")
        # Route to BM25-only fallback
```

Pure SQLite/pickle probe, never touches ChromaDB binary files.

---

## Caching Strategy

### 1. Client/Collection Cache

**Avoid redundant ChromaDB client creation:**

```python
_client_cache = None
_collection_cache = None
_palace_db_inode = 0
_palace_db_mtime = 0.0

def _get_client():
    global _client_cache, _collection_cache
    
    # Check if palace DB changed on disk (inode or mtime)
    db_path = os.path.join(_config.palace_path, "chroma.sqlite3")
    st = os.stat(db_path)
    current_inode = st.st_ino
    current_mtime = st.st_mtime
    
    if (_client_cache is None or 
        current_inode != _palace_db_inode or
        abs(current_mtime - _palace_db_mtime) > 0.01):
        # Rebuild client
        _client_cache = ChromaBackend.make_client(_config.palace_path)
        _collection_cache = None
        _palace_db_inode = current_inode
        _palace_db_mtime = current_mtime
    
    return _client_cache
```

**Invalidation triggers:**
- Inode change (palace rebuild/nuke/repair)
- Mtime change (external writes)
- Manual `mempalace_reconnect` call

### 2. Metadata Cache

**Avoid re-fetching full metadata for status queries:**

```python
_metadata_cache = None
_metadata_cache_time = 0
_METADATA_CACHE_TTL = 5.0  # seconds

def _get_cached_metadata(col, where=None):
    global _metadata_cache, _metadata_cache_time
    now = time.time()
    
    if (where is None and 
        _metadata_cache is not None and
        (now - _metadata_cache_time) < _METADATA_CACHE_TTL):
        return _metadata_cache
    
    # Fetch via pagination (avoid 10K silent truncation)
    result = _fetch_all_metadata(col, where=where)
    
    if where is None:
        _metadata_cache = result
        _metadata_cache_time = now
    
    return result
```

### 3. Knowledge Graph Cache

**Per-path singleton with lock:**

```python
_kg_by_path: dict[str, KnowledgeGraph] = {}
_kg_cache_lock = threading.Lock()

def _get_kg() -> KnowledgeGraph:
    path = os.path.abspath(_resolve_kg_path())
    kg = _kg_by_path.get(path)
    if kg is not None:
        return kg
    
    with _kg_cache_lock:
        # Double-check after acquiring lock
        kg = _kg_by_path.get(path)
        if kg is None:
            kg = KnowledgeGraph(db_path=path)
            _kg_by_path[path] = kg
    
    return kg
```

Prevents:
- Multiple SQLite connections to same DB file
- Thread-unsafe concurrent queries

---

## Error Handling

### 1. Transient Index Errors

**ChromaDB HNSW flush window (issue #1315):**

```python
def _is_transient_index_error(result) -> bool:
    """Detect post-bulk-write HNSW flush lag."""
    if not isinstance(result, dict):
        return False
    err = result.get("error", "")
    return isinstance(err, str) and ("Error finding id" in err or "Internal error" in err)

# In tool_search:
result = search_memories(...)
if _is_transient_index_error(result):
    # Drop caches, sleep, retry once
    _force_chroma_cache_reset()
    time.sleep(2)
    result = search_memories(...)
    if not _is_transient_index_error(result):
        result["index_recovered"] = True
```

SQLite rows committed but HNSW segment not flushed yet → self-heals in ~30-60s.

### 2. Stale Handle Recovery

**Collection cache auto-retry on failure:**

```python
def _get_collection(create=False):
    for attempt in range(2):
        try:
            client = _get_client()
            # ... open collection ...
            return _collection_cache
        except Exception:
            if attempt == 0:
                # Clear all caches, force rebuild
                _client_cache = None
                _collection_cache = None
                continue
            raise
```

First failure → rebuild from scratch, retry once.

### 3. Knowledge Graph Connection Close

**Race-safe retry wrapper:**

```python
def _call_kg(op):
    """Run op(kg) with one-shot retry on SQLite close."""
    for attempt in range(2):
        kg = _get_kg()
        try:
            return op(kg)
        except sqlite3.ProgrammingError:
            if attempt == 0:
                # Evict stale instance, retry with fresh KG
                path = os.path.abspath(_resolve_kg_path())
                with _kg_cache_lock:
                    if _kg_by_path.get(path) is kg:
                        _kg_by_path.pop(path, None)
                continue
            raise
```

Handles `mempalace_reconnect` closing the connection mid-operation.

---

## Tool Categories Reference

### Read Tools

| Tool | Purpose | Returns |
|------|---------|---------|
| `mempalace_status` | Palace overview | Total drawers, wings, rooms, AAAK spec |
| `mempalace_search` | Semantic search | Verbatim content + metadata |
| `mempalace_list_wings` | Browse wings | Wing names + drawer counts |
| `mempalace_list_rooms` | Browse rooms | Room names + drawer counts (optional wing filter) |
| `mempalace_get_taxonomy` | Full hierarchy | Wing → Room → Count tree |
| `mempalace_get_drawer` | Fetch by ID | Full content + metadata |
| `mempalace_list_drawers` | Paginated listing | IDs + previews (optional wing/room filter) |
| `mempalace_check_duplicate` | Pre-file check | Near-duplicate matches (similarity threshold) |

### Write Tools

| Tool | Purpose | Input |
|------|---------|-------|
| `mempalace_add_drawer` | File new content | wing, room, content (verbatim) |
| `mempalace_update_drawer` | Modify existing | drawer_id, optional content/wing/room |
| `mempalace_delete_drawer` | Remove by ID | drawer_id |
| `mempalace_sync` | Prune stale files | project_dir (optional), wing (optional), apply flag |

### Knowledge Graph Tools

| Tool | Purpose | Temporal Support |
|------|---------|------------------|
| `mempalace_kg_query` | Query entity relationships | as_of filter (point-in-time) |
| `mempalace_kg_add` | Add fact | valid_from, valid_to (optional) |
| `mempalace_kg_invalidate` | Mark fact as ended | ended date (default: today) |
| `mempalace_kg_timeline` | Chronological facts | Full history or per-entity |
| `mempalace_kg_stats` | Overview | Entities, triples, relationship types |

### Palace Graph Tools

| Tool | Purpose | Use Case |
|------|---------|----------|
| `mempalace_traverse` | Walk connections | Discover related topics across wings |
| `mempalace_find_tunnels` | Bridge discovery | Find rooms connecting two domains |
| `mempalace_graph_stats` | Connectivity overview | Rooms, tunnels, cross-wing edges |
| `mempalace_create_tunnel` | Explicit link | Connect related content across wings |
| `mempalace_list_tunnels` | Show tunnels | Optional wing filter |
| `mempalace_delete_tunnel` | Remove link | By tunnel ID |
| `mempalace_follow_tunnels` | Traverse from room | See connected drawers in other wings |

### Agent Diary Tools

| Tool | Purpose | Format |
|------|---------|--------|
| `mempalace_diary_write` | Write entry | AAAK compressed format |
| `mempalace_diary_read` | Read entries | Last N entries, chronological |

### Maintenance Tools

| Tool | Purpose | When to Use |
|------|---------|-------------|
| `mempalace_reconnect` | Force cache clear | After external CLI/script modifications |
| `mempalace_hook_settings` | Get/set hook behavior | Configure silent_save, desktop_toast |
| `mempalace_memories_filed_away` | Check silent checkpoint | Poll for background save completion |
| `mempalace_get_aaak_spec` | AAAK dialect reference | Learn compressed memory format |

---

## Example Multi-Tool Workflow

### Scenario: Claude helps user remember a past decision

**User:** "Why did we choose PostgreSQL over MySQL again?"

**Claude's internal process:**

**Step 1: Search for the decision**
```json
mempalace_search({
  "query": "postgresql mysql database decision",
  "limit": 3,
  "room": "decisions"
})
```

**Step 2: Check knowledge graph for context**
```json
mempalace_kg_query({
  "entity": "PostgreSQL",
  "direction": "both"
})
```

**Step 3: Traverse to related technical discussions**
```json
mempalace_traverse({
  "start_room": "database-choice",
  "max_hops": 2
})
```

**Step 4: Respond to user with findings**

**Step 5: File this query as a preference (optional)**
```json
mempalace_add_drawer({
  "wing": "wing_user",
  "room": "preferences",
  "content": "> Why did we choose PostgreSQL over MySQL again?\n\nUser frequently asks about database decisions. Key points: JSONB support, better concurrency, proven at scale."
})
```

**Step 6: Update knowledge graph**
```json
mempalace_kg_add({
  "subject": "user",
  "predicate": "frequently_asks_about",
  "object": "database_decisions",
  "valid_from": "2026-05-15"
})
```

**Step 7: Write diary entry**
```json
mempalace_diary_write({
  "agent_name": "Claude",
  "entry": "SESSION:2026-05-15|helped.recall.db.decision|USR.pattern:forgets.db.rationale(→.filed.preference)|★★",
  "topic": "support"
})
```

---

## Summary

The MCP interaction flow is **AI-driven and bidirectional:**

1. **AI Agent initiates** tool calls based on conversation context
2. **MCP Server** validates, sanitizes, and routes requests
3. **Storage Layer** (ChromaDB + KG) performs the actual operation
4. **Response flows back** through MCP to AI
5. **AI incorporates results** into conversation seamlessly

This enables:
- **Contextual memory recall** during conversations
- **Proactive knowledge filing** by the AI
- **Temporal fact tracking** via knowledge graph
- **Cross-session continuity** via agent diary

The AI becomes **stateful** across conversations — it remembers, learns, and evolves.

---

## References

- Main architecture: `/home/rigazilla/git/mempalace/ARCHITECTURE_MEMORY_FLOW.md`
- MCP server implementation: `mempalace/mcp_server.py`
- Search layer: `mempalace/searcher.py`
- Knowledge graph: `mempalace/knowledge_graph.py`
- ChromaDB backend: `mempalace/backends/chroma.py`
