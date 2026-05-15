# MemPalace Architecture: User Chat → Memory Storage

## Overview

This document traces the complete flow from a user's conversation with Claude Code through to persistent storage in the MemPalace memory system.

---

## The Flow: 7 Stages

```
┌─────────────────────────────────────────────────────────────────────┐
│ 1. USER CONVERSATION                                                 │
│    Claude Code REPL / Desktop / Web                                  │
└─────────────────┬───────────────────────────────────────────────────┘
                  │ 
                  │ Every N messages (default: 15)
                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│ 2. STOP HOOK TRIGGER                                                 │
│    hooks/mempal_save_hook.sh                                         │
│                                                                       │
│    • Counts user messages in session                                 │
│    • Every SAVE_INTERVAL messages → triggers save                    │
│    • Gets transcript path from Claude Code stdin JSON                │
│    • Runs: mempalace mine <transcript_dir> --mode convos             │
└─────────────────┬───────────────────────────────────────────────────┘
                  │
                  │ Background process
                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│ 3. CONVERSATION MINER                                                │
│    mempalace/convo_miner.py                                          │
│                                                                       │
│    • Scans for .json/.jsonl/.txt/.md files                           │
│    • Skips already-mined files (via ChromaDB source_file check)      │
│    • Processes each file:                                            │
└─────────────────┬───────────────────────────────────────────────────┘
                  │
                  │ For each transcript file
                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│ 4. NORMALIZATION                                                     │
│    mempalace/normalize.py                                            │
│                                                                       │
│    • Detects format:                                                 │
│      - Claude Code JSONL (message role + content blocks)             │
│      - ChatGPT JSON (mapping tree)                                   │
│      - Claude.ai JSON export                                         │
│      - Codex/Gemini CLI JSONL                                        │
│      - Slack JSON                                                    │
│      - Plain text with > markers                                     │
│                                                                       │
│    • Converts to uniform format:                                     │
│      "> user message"                                                │
│      "assistant response"                                            │
│      ""                                                              │
│                                                                       │
│    • Strips noise:                                                   │
│      - System tags (<system-reminder>, <task-notification>)          │
│      - Hook output                                                   │
│      - UI chrome ("Ran 2 Stop hooks")                                │
│      - Collapsed output markers                                      │
│                                                                       │
│    • Formats tool calls:                                             │
│      [Bash] command                                                  │
│      [Read file.py:10-50]                                            │
│      → output (truncated intelligently)                              │
└─────────────────┬───────────────────────────────────────────────────┘
                  │
                  │ Normalized transcript text
                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│ 5. CHUNKING STRATEGY                                                 │
│    Two modes available:                                              │
│                                                                       │
│  A) EXCHANGE MODE (default)                                          │
│     convo_miner.chunk_exchanges()                                    │
│     • One user turn + AI response = 1 drawer                         │
│     • If exchange > 800 chars → split into multiple drawers          │
│     • Detects room via keywords (technical/architecture/planning)    │
│                                                                       │
│  B) GENERAL EXTRACTION MODE                                          │
│     general_extractor.extract_memories()                             │
│     • Pure regex/keyword heuristics (no LLM)                         │
│     • Extracts 5 memory types:                                       │
│       - DECISIONS    ("we chose X because Y")                        │
│       - PREFERENCES  ("always use X", "never do Y")                  │
│       - MILESTONES   ("it works!", "figured out", "shipped")         │
│       - PROBLEMS     ("bug", "fixed by", "root cause")               │
│       - EMOTIONAL    ("love", "scared", "proud", "miss")             │
│     • Skips code lines (bash, python, config)                        │
│     • Each memory type becomes a ROOM                                │
└─────────────────┬───────────────────────────────────────────────────┘
                  │
                  │ List of chunks with metadata
                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│ 6. FILING INTO PALACE                                                │
│    convo_miner._file_chunks_locked()                                 │
│                                                                       │
│    For each chunk:                                                   │
│      • Generate drawer_id (hash of wing+room+content)                │
│      • Detect HALL via cached keywords (general/facts/events/etc)    │
│      • Batch upsert (1000 drawers at a time)                         │
│                                                                       │
│    Metadata stored per drawer:                                       │
│      {                                                               │
│        "wing": "wing_claude_code_session",                           │
│        "room": "technical" | "decision" | "milestone" | ...,         │
│        "hall": "hall_facts" | "hall_events" | ...,                   │
│        "source_file": "/path/to/transcript.jsonl",                   │
│        "chunk_index": 0,                                             │
│        "added_by": "mempalace",                                      │
│        "filed_at": "2026-05-14T15:30:00",                            │
│        "ingest_mode": "convos",                                      │
│        "extract_mode": "exchange" | "general",                       │
│        "normalize_version": "v2"                                     │
│      }                                                               │
└─────────────────┬───────────────────────────────────────────────────┘
                  │
                  │ VERBATIM content (never summarized)
                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│ 7. STORAGE BACKEND                                                   │
│    backends/chroma.py → ChromaDB                                     │
│                                                                       │
│    • Embedding: local models via sentence-transformers              │
│      (Ollama, LM Studio, llama.cpp, vLLM, unsloth studio)            │
│    • Vector index: HNSW (cosine similarity)                          │
│    • Full-text: BM25 via SQLite                                      │
│    • Hybrid search: combine both scores                              │
│                                                                       │
│    Storage structure:                                                │
│      palace_path/                                                    │
│        ├── chroma.sqlite3          ← SQLite metadata + FTS           │
│        ├── <uuid>/                 ← HNSW binary segments            │
│        └── knowledge_graph.sqlite3 ← Entity relationships            │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Key Components Detail

### 1. User Conversation

The user interacts with Claude Code via:
- **CLI REPL** (terminal)
- **Desktop app** (Mac/Windows)
- **Web interface** (claude.ai/code)
- **IDE extensions** (VS Code, JetBrains)

Every message is logged to a session transcript (JSONL format) at a path provided by Claude Code.

---

### 2. Stop Hook Trigger

**File:** `hooks/mempal_save_hook.sh`

**Configuration:**
```json
"hooks": {
  "Stop": [{
    "matcher": "*",
    "hooks": [{
      "type": "command",
      "command": "/path/to/mempal_save_hook.sh",
      "timeout": 30
    }]
  }]
}
```

**Behavior:**
- Triggered after every assistant response
- Receives JSON on stdin with:
  - `session_id` — unique identifier
  - `stop_hook_active` — prevents infinite loop
  - `transcript_path` — JSONL transcript location
- Counts user messages in session
- Every `SAVE_INTERVAL` messages (default: 15):
  - Runs `mempalace mine <transcript_dir> --mode convos` in background
  - Either blocks AI for diary write (verbose mode) or continues silently

**Two modes:**
- **Silent mode** (default): mines in background, no chat interruption
- **Verbose mode**: blocks AI, asks for structured diary entry

---

### 3. Conversation Miner

**File:** `mempalace/convo_miner.py`

**Process:**
1. Scan directory for conversation files (`.json`, `.jsonl`, `.txt`, `.md`)
2. Skip files already mined (check `source_file` metadata in ChromaDB)
3. For each file:
   - Normalize to standard format
   - Chunk into drawers
   - File to palace

**Optimizations:**
- Bulk prefetch of already-mined files (one query vs N queries)
- File-level locking prevents concurrent duplicate work
- Batch upserts (1000 drawers at a time) for large transcripts
- Per-file purge-before-insert on schema version bumps

---

### 4. Normalization

**File:** `mempalace/normalize.py`

**Supported formats:**
- **Claude Code JSONL** — `type: "human"/"assistant"`, content blocks
- **ChatGPT JSON** — mapping tree with parent/children
- **Claude.ai export** — flat messages array or privacy export
- **Codex CLI JSONL** — `event_msg` entries with `user_message`/`agent_message`
- **Gemini CLI JSONL** — `session_metadata` + `user`/`gemini` turns
- **Slack JSON** — multi-party messages with speaker alternation
- **Plain text** — with `>` markers or paragraph breaks

**Output format:**
```
> user question here

assistant response here with tool calls formatted as:
[Bash] ls -la
→ file1.txt
→ file2.py
[Read config.py:10-50]

> next user turn
```

**Noise removal:**
- System tags: `<system-reminder>`, `<task-notification>`, `<hook_output>`
- Hook chrome: "Ran 2 Stop hooks"
- Collapsed output: "… +N lines"
- Token markers: "[1234 tokens] (ctrl+o to expand)"

**Tool call formatting:**
- `[Bash] command` → head + tail (20 lines each) if long
- `[Read file:10-50]` → result omitted (content in git/palace)
- `[Grep pattern in path]` → first 20 matches
- `→ ` prefix for tool output

All patterns are **line-anchored** to prevent accidental content deletion.

---

### 5. Chunking Strategy

#### A) Exchange Mode (default)

**Function:** `convo_miner.chunk_exchanges()`

**Logic:**
- One user turn (starts with `>`) + AI response = 1 drawer
- If combined length > 800 chars, split AI response across multiple drawers
- Preserves full verbatim content (no truncation)
- Room detection via keyword scoring:
  - `technical`: code, python, bug, api, database, git, test
  - `architecture`: design, pattern, schema, module, service
  - `planning`: roadmap, milestone, sprint, requirement
  - `decisions`: decided, chose, trade-off, alternative
  - `problems`: issue, broken, crash, fix, workaround

#### B) General Extraction Mode

**File:** `mempalace/general_extractor.py`

**No LLM required** — pure regex/keyword heuristics.

**5 memory types extracted:**

1. **DECISIONS** — "we chose X because Y", architecture choices
   - Markers: "let's use", "we decided", "trade-off", "instead of"
   
2. **PREFERENCES** — "always use X", "never do Y", coding style
   - Markers: "I prefer", "always use", "never use", "my rule is"
   
3. **MILESTONES** — breakthroughs, shipping, successes
   - Markers: "it works", "figured out", "shipped", "deployed", "breakthrough"
   
4. **PROBLEMS** — bugs, root causes, fixes
   - Markers: "bug", "crash", "root cause", "the fix was", "workaround"
   
5. **EMOTIONAL** — feelings, relationships, vulnerability
   - Markers: "love", "scared", "proud", "I feel", "I miss", "\*action\*"

**Disambiguation:**
- Resolved problems → milestones
- Problems + positive sentiment → milestones or emotional
- Skips code lines (bash, python, config via pattern detection)
- Extracts only prose for classification

---

### 6. Filing into Palace

**Function:** `convo_miner._file_chunks_locked()`

**Per chunk:**
1. Generate deterministic `drawer_id`:
   - `drawer_{wing}_{room}_{sha256(source_file + chunk_index)[:24]}`
2. Detect HALL from content (cached keyword lookup):
   - `hall_facts`, `hall_events`, `hall_discoveries`, `hall_preferences`, `hall_advice`
3. Batch upsert to ChromaDB (1000 at a time)

**Metadata fields:**
```python
{
    "wing": "wing_claude_code_session",     # or project name
    "room": "technical",                     # topic/memory type
    "hall": "hall_facts",                    # broader category
    "source_file": "/path/to/transcript.jsonl",
    "chunk_index": 0,
    "added_by": "mempalace",
    "filed_at": "2026-05-14T15:30:00",
    "ingest_mode": "convos",                 # vs "projects"
    "extract_mode": "exchange",              # vs "general"
    "normalize_version": "v2"                # schema version
}
```

**Locking strategy:**
- File-level lock prevents concurrent agents from duplicating work
- Re-check after acquiring lock (other agent may have just finished)
- Purge stale drawers first (when normalize version bumps)
- All-or-nothing per source file

---

### 7. Storage Backend

**File:** `mempalace/backends/chroma.py`

**ChromaDB configuration:**
- **Collection metadata:**
  - `hnsw:space = "cosine"` — cosine similarity for vectors
  - `hnsw:num_threads = 1` — disables multi-threading (race fix)
  - HNSW bloat guard parameters

**Embedding function:**
- Local models via `sentence-transformers`
- Supported runtimes:
  - Ollama
  - LM Studio  
  - llama.cpp
  - vLLM
  - unsloth studio
- **No external APIs by default** — fully local
- External providers (Anthropic, OpenAI) available via BYOK

**Search strategy:**
- **Hybrid:** BM25 (keyword) + HNSW (semantic)
- Fallback to BM25-only if HNSW segment diverges (safety)
- Distance threshold: default 1.5 (configurable via `max_distance`)

**File structure:**
```
palace_path/
├── chroma.sqlite3           ← metadata + full-text search
├── <collection_uuid>/       ← HNSW binary segments
│   ├── index_metadata.pickle
│   └── index.bin
└── knowledge_graph.sqlite3  ← temporal entity relationships
```

---

## Design Principles Demonstrated

### 1. Verbatim Always
- Never summarizes user data
- Noise stripping is surgical (line-anchored patterns)
- Tool output is formatted compactly but losslessly

### 2. Incremental Only
- Append-only after initial build
- File-level skip via `source_file` metadata check
- Schema migrations are purge-per-file, not full-palace nuke

### 3. Entity-First
- Wing = person/project (real names)
- Room = topic/memory-type (semantic grouping)
- Hall = broader category (facts/events/discoveries)

### 4. Local-First, Zero External API
- All extraction/chunking/embedding happens locally
- External providers are opt-in, never required
- System cannot send data to services user hasn't configured

### 5. Performance Budgets
- Hooks < 500ms (runs in background)
- Batch upserts (1000 drawers at a time)
- Bulk prefetch of already-mined set (O(1) lookup vs O(N) queries)
- Metadata cache (5s TTL) for status queries

### 6. Privacy by Architecture
- Data never leaves user's machine
- Write-ahead log redacts content
- Source file paths reduced to basename before MCP export
- No telemetry, no phone-home

### 7. Background Everything
- Hook mines in background (`&`)
- Filing/indexing happens asynchronously
- Zero tokens spent on bookkeeping in chat window

---

## Alternative Flows

### Silent Mode (default)
```
User chats → Hook counts messages → Every 15: mine in background
          ↓
AI continues immediately (no blocking)
          ↓
Checkpoint written to ~/.mempalace/hook_state/last_checkpoint
          ↓
AI can poll via mempalace_memories_filed_away MCP tool
```

### Verbose Mode
```
User chats → Hook counts messages → Every 15: mine in background
          ↓
Hook blocks AI with system message
          ↓
AI writes structured diary entry (AAAK format)
          ↓
AI organizes memories into wings/rooms
          ↓
AI continues conversation
```

---

## MCP Server Role

The MCP server (`mempalace/mcp_server.py`) is a **proxy layer** that exposes palace operations to Claude Code via JSON-RPC:

**Read tools:**
- `mempalace_status` — overview + AAAK spec
- `mempalace_search` — hybrid semantic search
- `mempalace_list_wings/rooms` — browse taxonomy
- `mempalace_get_drawer` — fetch by ID

**Write tools:**
- `mempalace_add_drawer` — file new content
- `mempalace_update_drawer` — modify existing
- `mempalace_delete_drawer` — remove by ID
- `mempalace_sync` — prune deleted/gitignored files

**Knowledge graph:**
- `mempalace_kg_query` — temporal entity relationships
- `mempalace_kg_add` — add facts with validity dates
- `mempalace_kg_invalidate` — mark facts as no longer true

**Agent diary:**
- `mempalace_diary_write` — agent's personal journal (AAAK)
- `mempalace_diary_read` — retrieve past entries

The MCP server does **not** trigger mining — that's the hook's job. It provides read/write access to the already-mined palace.

---

## Summary

The flow is elegantly simple:

**Chat → Normalize → Chunk → Embed → Store**

Every step preserves the user's exact words. The system is designed for **100% recall** — if you said it, it's stored verbatim and instantly searchable.

Memory is identity. MemPalace makes it permanent.
