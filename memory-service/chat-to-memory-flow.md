# User Chat to Memory Service Data Flow

This document illustrates how user interactions with AI agents (like Claude Code) are transformed and stored in the Memory Service.

## Overview

The Memory Service acts as a **durable conversation store** for AI agents. It's not a direct chat interface - instead, AI agents and applications sit between users and the memory service, mediating all interactions.

## Architecture Layers

```
┌─────────────────────────────────────────────────────────────┐
│                         END USER                             │
│                    (Human interacting)                       │
└────────────────────────┬────────────────────────────────────┘
                         │
                         │ User sends message
                         ▼
┌─────────────────────────────────────────────────────────────┐
│                    FRONTEND APPLICATION                      │
│              (React SPA, Desktop App, CLI)                   │
│                                                              │
│  • Captures user input                                       │
│  • Handles attachments (images, files)                       │
│  • Sends POST to /chat/{conversationId}                      │
│                                                              │
│  Example payload:                                            │
│  {                                                           │
│    "message": "How do I fix this bug?",                      │
│    "attachments": [{                                         │
│      "attachmentId": "uuid",                                 │
│      "contentType": "image/png",                             │
│      "name": "screenshot.png"                                │
│    }],                                                       │
│    "forkedAtConversationId": null,                           │
│    "forkedAtEntryId": null                                   │
│  }                                                           │
└────────────────────────┬────────────────────────────────────┘
                         │
                         │ HTTP POST
                         ▼
┌─────────────────────────────────────────────────────────────┐
│                    AGENT APPLICATION                         │
│         (Quarkus/Spring app with LLM integration)            │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  ChatResource / API Endpoint                         │   │
│  │  • Receives user message                             │   │
│  │  • Validates conversation ID                         │   │
│  │  • Resolves attachments                              │   │
│  └──────────────┬───────────────────────────────────────┘   │
│                 │                                            │
│                 ▼                                            │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  HistoryRecordingAgent (@RecordConversation)         │   │
│  │  • Annotated method that auto-records to memory      │   │
│  │  • Passes message to LLM agent                       │   │
│  └──────────────┬───────────────────────────────────────┘   │
│                 │                                            │
│                 ▼                                            │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  LLM Agent (LangChain4j/SpringAI)                    │   │
│  │  • Processes user message                            │   │
│  │  • May call tools                                    │   │
│  │  • Generates AI response                             │   │
│  │  • Streams events: PartialResponse, ToolExecution    │   │
│  └──────────────┬───────────────────────────────────────┘   │
│                 │                                            │
│                 ▼                                            │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  Memory Service Client Library                       │   │
│  │  • Intercepts conversation events                    │   │
│  │  • Transforms to memory service format               │   │
│  │  • Makes HTTP/gRPC calls to memory service           │   │
│  └──────────────┬───────────────────────────────────────┘   │
└─────────────────┼────────────────────────────────────────────┘
                  │
                  │ REST API Calls
                  ▼
┌─────────────────────────────────────────────────────────────┐
│                    MEMORY SERVICE                            │
│                     (Go Backend)                             │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  HTTP/gRPC API Layer                                 │   │
│  │  • POST /v1/conversations (create)                   │   │
│  │  • POST /v1/conversations/{id}/entries (append)      │   │
│  │  • GET  /v1/conversations/{id}/entries (list)        │   │
│  │  • POST /v1/conversations/search                     │   │
│  └──────────────┬───────────────────────────────────────┘   │
│                 │                                            │
│                 ▼                                            │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  Business Logic Layer                                │   │
│  │  • Validates requests                                │   │
│  │  • Enforces access control (owner/manager/writer)    │   │
│  │  • Handles fork logic                                │   │
│  │  • Manages conversation groups                       │   │
│  └──────────────┬───────────────────────────────────────┘   │
│                 │                                            │
│                 ▼                                            │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  Data Store Layer                                    │   │
│  │  • Writes to primary DB (Postgres/SQLite/Mongo)     │   │
│  │  • Updates cache (Redis/Infinispan/local)            │   │
│  │  • Indexes for search (FTS5/PGVector/Qdrant)         │   │
│  │  • Stores attachments (FS/DB/S3)                     │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

## Data Transformation Flow

### 1. User Message → API Request

**Frontend sends:**
```json
POST /chat/{conversationId}
{
  "message": "How do I implement authentication?",
  "attachments": [...]
}
```

### 2. Agent Processing

The agent application:
- Creates/loads the conversation
- Appends USER entry to memory service
- Invokes LLM with conversation history
- Streams AI response events
- Appends AI entry to memory service

### 3. Memory Service Entry Creation

**Agent sends to Memory Service:**
```json
POST /v1/conversations/{conversationId}/entries
{
  "channel": "history",
  "contentType": "history/lc4j",
  "content": [
    {
      "role": "USER",
      "text": "How do I implement authentication?",
      "attachments": [
        {
          "name": "diagram.png",
          "contentType": "image/png",
          "href": "/v1/attachments/abc-123"
        }
      ]
    }
  ],
  "indexedContent": "How do I implement authentication?"
}
```

**Then appends AI response:**
```json
POST /v1/conversations/{conversationId}/entries
{
  "channel": "history",
  "contentType": "history/lc4j",
  "content": [
    {
      "role": "AI",
      "text": "To implement authentication, you can use...",
      "events": [
        {
          "eventType": "PartialThinking",
          "chunk": "I should recommend OAuth 2.0..."
        },
        {
          "eventType": "BeforeToolExecution",
          "toolName": "search_docs",
          "input": "OAuth 2.0 best practices"
        },
        {
          "eventType": "ToolExecuted",
          "toolName": "search_docs",
          "output": "Found 5 relevant docs..."
        },
        {
          "eventType": "PartialResponse",
          "chunk": "To implement authentication"
        },
        {
          "eventType": "PartialResponse",
          "chunk": ", you can use OAuth 2.0..."
        }
      ]
    }
  ],
  "indexedContent": "To implement authentication, you can use OAuth 2.0..."
}
```

### 4. Storage in Memory Service

The entry is stored with:

**Conversation:**
```
conversations table:
- id: UUID
- title: "Authentication help"
- owner_user_id: "alice"
- client_id: "chat-app-123"
- agent_id: "coding-assistant"
- created_at: timestamp
- updated_at: timestamp
- forked_at_conversation_id: null (or parent UUID if forked)
- forked_at_entry_id: null (or parent entry UUID)
- conversation_group_id: UUID (shared by all forks)
```

**Entry:**
```
entries table:
- id: UUID
- conversation_id: UUID (FK)
- user_id: "alice"
- channel: "history"
- epoch: null (for history; increments for context)
- content_type: "history/lc4j"
- content: JSON array (see above)
- indexed_content: "searchable text"
- created_at: timestamp
```

**Search Index:**
```
FTS5 index (SQLite) or vector embeddings (PGVector/Qdrant):
- entry_id → searchable_text
- enables semantic and keyword search
```

**Cache:**
```
Redis/Infinispan (or local memory):
- conversation:{id} → conversation JSON
- entries:{conversation_id}:{channel}:{epoch} → entry list
- enables fast retrieval without DB queries
```

## MCP Server: A Different Use Case

The **MCP server** (`memory-service-mcp`) is **not** part of the agent→memory flow above. Instead, it's a **separate tool** that allows AI coding assistants (like Claude Code) to:

1. **Save session notes** after completing work
2. **Search past sessions** to recall previous solutions
3. **Retrieve context** from earlier conversations

### MCP Flow

```
┌─────────────────┐
│  Claude Code    │
│  (AI Assistant) │
└────────┬────────┘
         │
         │ Uses MCP protocol
         ▼
┌─────────────────────────────┐
│  memory-service-mcp         │
│  (MCP Server)               │
│                             │
│  Tools:                     │
│  • save_session_notes       │
│  • search_sessions          │
│  • list_sessions            │
│  • get_session              │
│  • append_note              │
└────────┬────────────────────┘
         │
         │ HTTP API calls
         ▼
┌─────────────────────────────┐
│  Memory Service API         │
│  • POST /v1/conversations   │
│  • POST .../entries         │
│  • POST .../search          │
└─────────────────────────────┘
```

**Example MCP usage:**

After fixing a bug, Claude Code might:
```
save_session_notes(
  title="Fixed cache serialization bug",
  notes="The Entry type had asymmetric JSON marshal/unmarshal...",
  tags="bugfix,cache,go"
)
```

This creates a new conversation in the memory service tagged with `[claude-code]`, allowing future sessions to search for "cache bug" and find this solution.

## Key Concepts

### Conversations
- Container for a thread of entries
- Has owner and access levels (owner/manager/writer/reader)
- Can be forked to create branches
- All forks share a "conversation group"

### Entries
- Individual messages in a conversation
- Two channels: `history` (user-visible) and `context` (agent-managed)
- Content is flexible JSON array
- Can include text, events, attachments, tool calls

### Channels
- **history**: User-visible conversation (user ↔ AI)
- **context**: Agent-managed context (not shown to users)
  - Used for RAG, system prompts, background info
  - Scoped to agent's client ID
  - Supports epochs for context versioning

### Content Types
- `history`: Simple text messages
- `history/lc4j`: LangChain4j rich events (tool calls, thinking, streaming)
- `LC4J`, `SpringAI`: Agent framework-specific context formats

### Forks
- Create branches from any entry in a conversation
- Useful for "what if" scenarios or parallel explorations
- Share history up to fork point
- Each fork is a separate conversation but linked via conversation_group_id

## Summary

1. **User** types message in frontend
2. **Frontend** sends to agent application
3. **Agent** processes with LLM, records both USER and AI entries
4. **Memory Service** stores entries with rich metadata
5. **Search/Retrieval** enables finding past conversations
6. **MCP Server** (separate flow) allows AI assistants to save/retrieve session notes

The memory service is the **single source of truth** for all conversation history, enabling features like:
- Conversation replay
- Fork exploration
- Semantic search
- Multi-agent orchestration
- Conversation transfer between users
