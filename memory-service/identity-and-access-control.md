# Identity and Access Control in Memory Service

This chapter explains how identity is handled from the AI assistant through to memory storage, and how privacy and access control work.

## Short Answer

**Yes, memory is private per user by default.** Each conversation is owned by a user and can be shared with specific other users at different access levels (owner/manager/writer/reader). Conversations are organized into **conversation groups** (fork trees) that share a common access control list (ACL).

---

## Identity Flow

### Overview Diagram

```
┌──────────────────────────────────────────────────────────────┐
│              CLAUDE CODE (AI Assistant)                      │
│                                                              │
│  Running on behalf of: Alice (human user)                    │
│  Has access to MCP server configuration:                     │
│    - MEMORY_SERVICE_URL                                      │
│    - MEMORY_SERVICE_API_KEY                                  │
│    - MEMORY_SERVICE_BEARER_TOKEN (optional)                  │
└────────────────────┬─────────────────────────────────────────┘
                     │
                     │ MCP call with NO explicit identity
                     │ (Claude doesn't send user info)
                     ▼
┌──────────────────────────────────────────────────────────────┐
│              memory-service-mcp (MCP Server)                 │
│              📍 internal/cmd/mcp/cmd.go                      │
│                                                              │
│  Configuration (lines 40-123):                               │
│  • Remote mode: Uses env vars from config                    │
│    - MEMORY_SERVICE_URL                                      │
│    - MEMORY_SERVICE_API_KEY                                  │
│    - MEMORY_SERVICE_BEARER_TOKEN                             │
│                                                              │
│  • Embedded mode: Auto-configured (line 154-159)             │
│    - Sets API key: "embedded-mcp-api-key"                    │
│    - Sets bearer token: "embedded-mcp-user"                  │
│                                                              │
│  Creates HTTP client with headers (line 143-149):            │
│  📍 internal/cmd/mcp/cmd.go:143                              │
│    req.Header.Set("X-API-Key", apiKey)                       │
│    req.Header.Set("Authorization", "Bearer " + bearerToken)  │
└────────────────────┬─────────────────────────────────────────┘
                     │
                     │ HTTP Request with headers:
                     │ X-API-Key: {apiKey}
                     │ Authorization: Bearer {bearerToken}
                     ▼
┌──────────────────────────────────────────────────────────────┐
│              MEMORY SERVICE (Go Backend)                     │
│              📍 internal/security/auth.go                    │
│                                                              │
│  Authentication Middleware (line 297):                       │
│  func HTTPAuthMiddleware()                                   │
│                                                              │
│  Step 1: Extract credentials from request                    │
│    • API Key: X-API-Key header                               │
│    • Bearer Token: Authorization header                      │
│    • Client ID: X-Client-ID header (testing only)            │
│                                                              │
│  Step 2: Resolve identity (line 149)                         │
│    📍 func (TokenResolver) Resolve()                         │
│                                                              │
│    Option A: OIDC/JWT Token (if OIDC configured)             │
│    ┌────────────────────────────────────────────────────┐   │
│    │ • Verify JWT signature (line 172-176)              │   │
│    │ • Extract user ID from token claims:               │   │
│    │   - preferred_username (Keycloak/Quarkus)          │   │
│    │   - upn (Azure AD)                                 │   │
│    │   - sub (OAuth standard)                           │   │
│    │                                                    │   │
│    │ • Extract roles from token:                        │   │
│    │   - Check for "admin", "auditor", "indexer" roles │   │
│    │                                                    │   │
│    │ Result: userID = "alice" (from token)              │   │
│    └────────────────────────────────────────────────────┘   │
│                                                              │
│    Option B: API Key Auth (simpler, no OIDC)                 │
│    ┌────────────────────────────────────────────────────┐   │
│    │ • Resolve API key to client ID (line 156-162)      │   │
│    │   apiKeys["my-api-key"] = "chat-app-1"            │   │
│    │                                                    │   │
│    │ • Use bearer token directly as user ID (line 216) │   │
│    │   userID = bearerToken  ("alice")                 │   │
│    │                                                    │   │
│    │ Result:                                            │   │
│    │   userID = "alice"                                 │   │
│    │   clientID = "chat-app-1"                          │   │
│    └────────────────────────────────────────────────────┘   │
│                                                              │
│  Step 3: Check role assignments (line 219-245)               │
│    • Admin users: MEMORY_SERVICE_ADMIN_USERS="alice,bob"    │
│    • Auditor users: MEMORY_SERVICE_AUDITOR_USERS="..."      │
│    • Indexer users: MEMORY_SERVICE_INDEXER_USERS="..."      │
│    • Admin clients: MEMORY_SERVICE_ADMIN_CLIENTS="..."      │
│                                                              │
│  Step 4: Store in context (line 247-252)                     │
│    📍 type Identity struct                                   │
│    {                                                         │
│      UserID:   "alice",                                      │
│      ClientID: "chat-app-1",                                 │
│      Roles:    {"admin": false, "auditor": false},           │
│      IsAdmin:  false                                         │
│    }                                                         │
│                                                              │
│  Step 5: Set in Gin context (line 319-322)                   │
│    c.Set("userID", "alice")                                  │
│    c.Set("clientID", "chat-app-1")                           │
│    c.Set("roles", ...)                                       │
│    c.Set("isAdmin", false)                                   │
└──────────────────────────────────────────────────────────────┘
                     │
                     │ Request proceeds with authenticated identity
                     ▼
┌──────────────────────────────────────────────────────────────┐
│              CONVERSATION HANDLERS                           │
│              📍 internal/plugin/route/conversations/         │
│                                                              │
│  Every handler extracts userID from context:                 │
│  📍 internal/security/auth.go:257                            │
│    userID := GetUserID(c)  // Returns "alice"                │
│                                                              │
│  All data operations are scoped to this userID:              │
│  • Creating conversations → owned by "alice"                 │
│  • Listing conversations → only "alice" can access           │
│  • Appending entries → associated with "alice"               │
└──────────────────────────────────────────────────────────────┘
```

---

## Authentication Methods

### Method 1: API Key + Bearer Token (Simple)

**Used by:** MCP server, simple agent apps

**Configuration:**
```bash
# Server config (memory service)
MEMORY_SERVICE_API_KEYS='{"api-key-123":"my-app-client-id"}'

# Client config (MCP server)
MEMORY_SERVICE_API_KEY=api-key-123
MEMORY_SERVICE_BEARER_TOKEN=alice
```

**Request:**
```http
POST /v1/conversations HTTP/1.1
Host: localhost:8082
X-API-Key: api-key-123
Authorization: Bearer alice
```

**Identity Resolution:**
```
API Key: "api-key-123" → Client ID: "my-app-client-id"
Bearer Token: "alice" → User ID: "alice"
```

**Code Path:**
- 📍 `internal/security/auth.go:156-162` - API key to client ID resolution
- 📍 `internal/security/auth.go:216` - Bearer token to user ID

### Method 2: OIDC/JWT Token (Enterprise)

**Used by:** Production deployments with Keycloak, Azure AD, etc.

**Configuration:**
```bash
MEMORY_SERVICE_OIDC_ISSUER=https://keycloak.example.com/realms/myrealm
MEMORY_SERVICE_OIDC_DISCOVERY_URL=http://keycloak:8080/realms/myrealm  # optional
MEMORY_SERVICE_ADMIN_OIDC_ROLE=admin
MEMORY_SERVICE_AUDITOR_OIDC_ROLE=auditor
```

**Request:**
```http
POST /v1/conversations HTTP/1.1
Host: localhost:8082
Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
```

**JWT Token Claims:**
```json
{
  "sub": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "preferred_username": "alice",
  "upn": "alice@example.com",
  "realm_access": {
    "roles": ["user", "admin"]
  }
}
```

**Identity Resolution:**
```
1. Verify JWT signature against OIDC provider
2. Extract user ID: preferred_username → "alice"
3. Extract roles: realm_access.roles → ["admin"]
4. Result: Identity{UserID: "alice", IsAdmin: true}
```

**Code Path:**
- 📍 `internal/security/auth.go:71-138` - `NewTokenResolver()` with OIDC setup
- 📍 `internal/security/auth.go:172-176` - JWT verification
- 📍 `internal/security/auth.go:179-197` - User ID extraction from claims
- 📍 `internal/security/auth.go:199-212` - Role extraction

---

## Access Control Model

### Conversation Ownership & Groups

```
┌─────────────────────────────────────────────────────────────┐
│                   CONVERSATION GROUP                        │
│                   (Fork Tree / ACL Scope)                   │
│                   📍 internal/model/model.go:68             │
│                                                             │
│  ID: uuid-group-1                                           │
│  Created: 2026-05-10                                        │
│  Archived: null                                             │
│                                                             │
│  ┌───────────────────────────────────────────────────────┐ │
│  │              MEMBERSHIPS (ACL)                        │ │
│  │              📍 internal/model/model.go:99            │ │
│  │                                                       │ │
│  │  • alice   → owner    (created the conversation)     │ │
│  │  • bob     → writer   (shared by alice)              │ │
│  │  • charlie → reader   (shared by alice)              │ │
│  └───────────────────────────────────────────────────────┘ │
│                                                             │
│  ┌───────────────────────────────────────────────────────┐ │
│  │           CONVERSATIONS IN GROUP                      │ │
│  │           📍 internal/model/model.go:78               │ │
│  │                                                       │ │
│  │  Conversation A (root)                                │ │
│  │  ├─ owner: alice                                      │ │
│  │  ├─ clientID: "chat-app-1"                            │ │
│  │  └─ Entries: [e1, e2, e3]                            │ │
│  │                                                       │ │
│  │  Conversation B (forked from A at e2)                 │ │
│  │  ├─ owner: alice                                      │ │
│  │  ├─ forkedAtConversationID: A                         │ │
│  │  ├─ forkedAtEntryID: e2                               │ │
│  │  └─ Entries: [e1, e2, e4, e5]  (inherited e1, e2)    │ │
│  │                                                       │ │
│  │  Conversation C (child started from A at e3)          │ │
│  │  ├─ owner: bob  (delegated task)                     │ │
│  │  ├─ startedByConversationID: A                        │ │
│  │  ├─ startedByEntryID: e3                              │ │
│  │  └─ Has its own group (separate ACL)                 │ │
│  └───────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

**Key Concepts:**

1. **ConversationGroup** - Root of fork tree, owns the ACL
   - All forks share the same access control
   - One group can have multiple conversations
   - Archived groups eventually get evicted (hard deleted)

2. **Conversation** - Single thread within a group
   - Has one owner (creator)
   - Belongs to one group
   - Can be forked (creates new conversation, same group)
   - Can spawn child conversations (new group, separate ACL)

3. **Membership** - Per-user access to a group
   - Keyed by: (conversation_group_id, user_id)
   - Access levels: owner, manager, writer, reader

### Access Levels

📍 `internal/model/model.go:20-48`

| Level | Rank | Permissions |
|-------|------|-------------|
| **owner** | 4 | Full control: read, write, share, transfer ownership, delete |
| **manager** | 3 | Can read, write, share with others |
| **writer** | 2 | Can read and append entries |
| **reader** | 1 | Can only read conversations and entries |

**Hierarchy:** `owner >= manager >= writer >= reader`

**Code:**
```go
// internal/model/model.go:31-48
func (a AccessLevel) IsAtLeast(level AccessLevel) bool {
    return accessRank(a) >= accessRank(level)
}
```

**Usage Example:**
```go
// Check if user can write
if accessLevel.IsAtLeast(AccessLevelWriter) {
    // Allow append entry
}

// Check if user can share
if accessLevel.IsAtLeast(AccessLevelManager) {
    // Allow creating memberships
}
```

---

## Privacy Model

### Question: Is memory private for each user?

**Answer: YES, strictly enforced at the database level.**

Every data access operation is scoped to the authenticated user's ID:

#### Creating Conversations

```go
// 📍 internal/plugin/route/conversations/conversations.go:125
func createConversation(c *gin.Context, store registrystore.MemoryStore, ...) {
    userID := security.GetUserID(c)  // Extract from auth context
    
    // Create conversation owned by this user
    conv, err := store.CreateConversation(
        ctx,
        userID,      // ← Owner
        clientID,
        title,
        metadata,
        agentID,
        ...
    )
}
```

#### Listing Conversations

```go
// 📍 internal/plugin/route/conversations/conversations.go:100
func listConversations(c *gin.Context, store registrystore.MemoryStore) {
    userID := security.GetUserID(c)  // Extract from auth context
    
    // Only returns conversations where user has membership
    conversations, err := store.ListConversations(
        ctx,
        userID,  // ← Filter by user access
        mode,
        ancestry,
        archived,
        afterCursor,
        limit,
    )
}
```

The database query joins on `conversation_memberships`:
```sql
-- Simplified from PostgreSQL store implementation
SELECT c.*
FROM conversations c
JOIN conversation_groups g ON c.conversation_group_id = g.id
JOIN conversation_memberships m ON m.conversation_group_id = g.id
WHERE m.user_id = ?  -- Only user's accessible conversations
AND m.access_level >= 'reader'
ORDER BY c.updated_at DESC
LIMIT ?
```

#### Accessing Entries

```go
// 📍 internal/plugin/route/entries/entries.go:55
func listEntries(c *gin.Context, store registrystore.MemoryStore) {
    userID := security.GetUserID(c)  // Extract from auth context
    
    // Verify user has access to this conversation
    entries, err := store.ListEntries(
        ctx,
        userID,         // ← Verify access
        conversationID,
        channel,
        clientID,
        agentID,
        epochFilter,
        forks,
        afterCursor,
        limit,
    )
}
```

### Question: Are there groups?

**Answer: YES, conversation groups provide shared access.**

Groups enable collaboration while maintaining privacy:

```
Alice's Private View:
  ✓ Conversations she owns
  ✓ Conversations shared with her (by user_id membership)
  ✗ Bob's private conversations
  ✗ Conversations in groups she doesn't have membership in

Bob's Private View:
  ✓ Conversations he owns
  ✓ Conversations shared with him
  ✗ Alice's private conversations
  ✗ Conversations in groups he doesn't have membership in
```

---

## Sharing and Collaboration

### Sharing a Conversation

📍 `internal/plugin/route/memberships/memberships.go:102-151`

**API Endpoint:**
```http
POST /v1/conversations/{conversationId}/memberships
Authorization: Bearer alice
Content-Type: application/json

{
  "userId": "bob",
  "accessLevel": "writer"
}
```

**What Happens:**

1. **Verify caller has manager+ access** (can share)
2. **Resolve conversation group ID** from conversation
3. **Create or update membership:**
   ```sql
   INSERT INTO conversation_memberships (
     conversation_group_id,
     user_id,
     access_level
   ) VALUES (?, 'bob', 'writer')
   ON CONFLICT ... DO UPDATE
   ```
4. **Bob now has access** to:
   - The original conversation
   - ALL forks in the group (shared ACL)
   - Can append entries (writer level)
   - Cannot share with others (needs manager level)

**Code Path:**
```go
// internal/plugin/route/memberships/memberships.go:122
membership, err := store.ShareConversation(
    ctx,
    userID,       // "alice" (caller, must be manager+)
    convID,
    req.UserID,   // "bob" (new member)
    req.AccessLevel, // "writer"
)
```

### Updating Access Level

📍 `internal/plugin/route/memberships/memberships.go:164-212`

```http
PATCH /v1/conversations/{conversationId}/memberships/bob
Authorization: Bearer alice

{
  "accessLevel": "reader"
}
```

**Effect:** Bob's access is downgraded from writer to reader (can no longer append entries)

### Revoking Access

📍 `internal/plugin/route/memberships/memberships.go:214-259`

```http
DELETE /v1/conversations/{conversationId}/memberships/bob
Authorization: Bearer alice
```

**Effect:** Bob loses all access to the conversation group

### Transferring Ownership

📍 `internal/plugin/route/transfers/transfers.go`

```http
POST /v1/conversations/{conversationId}/ownership-transfers
Authorization: Bearer alice

{
  "newOwnerUserId": "bob"
}
```

**Two-step process:**
1. Alice creates transfer request
2. Bob accepts the transfer
3. Ownership changes, Alice becomes manager

---

## Client ID vs User ID

### Two Levels of Identity

📍 `internal/security/auth.go:35-41`

```go
type Identity struct {
    UserID   string            // Human user (e.g., "alice")
    ClientID string            // Application/agent (e.g., "chat-app-1")
    Roles    map[string]bool   // Admin, auditor, indexer
    IsAdmin  bool
}
```

### Purpose

| Identity | Scope | Purpose | Example |
|----------|-------|---------|---------|
| **userID** | Human user | Data ownership, privacy, access control | "alice", "bob", "charlie" |
| **clientID** | Application | Agent context scoping, audit trail | "chat-app-1", "slack-bot", "api-service" |

### Where ClientID is Used

1. **Conversation metadata** (internal, not exposed to users)
   - Which app/agent created this conversation
   - 📍 `internal/model/model.go:82`

2. **Entry metadata** (who/what created this entry)
   - User entries: userID set, clientID optional
   - Agent entries: clientID identifies the agent app
   - 📍 `internal/model/model.go:114-116`

3. **Context channel scoping** (agent-managed context)
   - Each clientID has separate context epochs
   - Agent A's context doesn't interfere with Agent B's
   - 📍 Entry channel="context", scoped by clientID

**Example Scenario:**

```
User: alice
Apps: Claude Code (MCP), Slack Bot

MCP creates conversation:
  ownerUserId: "alice"
  clientId: "claude-code-mcp"
  
Slack bot appends to same conversation:
  entry.userId: "alice"  (still alice's data)
  entry.clientId: "slack-bot"  (different app)
  
Both apps see alice's conversations (shared user),
but context entries are separate (different clientIDs).
```

---

## MCP Server Identity Configuration

### Remote Mode

📍 `internal/cmd/mcp/cmd.go:40-56`

```bash
# .mcp.json or environment
MEMORY_SERVICE_URL=http://localhost:8082
MEMORY_SERVICE_API_KEY=my-api-key-123
MEMORY_SERVICE_BEARER_TOKEN=alice
```

**What happens:**
1. MCP server sends these headers with every request
2. Memory service resolves: API key → clientID, bearer token → userID
3. All sessions created via MCP are owned by "alice"
4. Sessions are private to "alice" unless shared

### Embedded Mode

📍 `internal/cmd/mcp/cmd.go:60-102`

```bash
# Command
memory-service mcp embedded --db-url ./memory.db
```

**What happens:**
1. Starts in-process memory service instance
2. Auto-configures auth (line 154-159):
   ```go
   const (
       embeddedClientID    = "embedded-mcp"
       embeddedAPIKey      = "embedded-mcp-api-key"
       embeddedBearerToken = "embedded-mcp-user"
   )
   
   cfg.APIKeys[embeddedAPIKey] = embeddedClientID
   ```
3. All operations run as user "embedded-mcp-user"
4. Single-user setup (perfect for local dev)

---

## Multi-Tenancy & Isolation

### How Users Are Isolated

```
User A's data:
  ┌─ Conversation Group 1
  │    └─ Memberships: [A: owner]
  │    └─ Conversations: [conv1, conv2]
  │         └─ Entries: [...]
  ├─ Conversation Group 2
       └─ Memberships: [A: owner, B: reader]  ← Shared!
       └─ Conversations: [conv3]

User B's data:
  ┌─ Conversation Group 3
  │    └─ Memberships: [B: owner]
  │    └─ Conversations: [conv4]
  └─ Conversation Group 2  ← Shared with A!
       └─ Can read conv3 (reader access)
```

**Isolation Guarantees:**

1. **Database Level**: All queries filter by user_id via membership join
2. **Cache Level**: Cache keys include user context
3. **Search Level**: Vector search filters by accessible conversation_group_ids
4. **Events Level**: Event stream subscribers only receive events for their conversations

**No Cross-User Data Leakage:**
- Alice cannot list Bob's conversations
- Alice cannot access Bob's entries
- Alice cannot search Bob's private data
- **UNLESS** Bob explicitly shares via membership

### Admin Bypass

📍 `internal/security/auth.go:30-33`

**Admin users can access ALL data** (for system maintenance):

```bash
MEMORY_SERVICE_ADMIN_USERS=admin,sysadmin
```

Admin APIs bypass membership checks:
- 📍 `internal/plugin/route/admin/*` - Admin routes
- 📍 `internal/registry/store/plugin.go:294-305` - Admin methods

---

## Source Code Reference Summary

### Authentication & Authorization

| Component | File | Key Functions/Lines |
|-----------|------|---------------------|
| **Identity Types** |
| Identity struct | `internal/security/auth.go` | Line 35: `type Identity struct` |
| Context keys | `internal/security/auth.go` | Lines 18-27: Constants |
| Roles | `internal/security/auth.go` | Lines 29-33: Admin/Auditor/Indexer |
| **Token Resolution** |
| TokenResolver | `internal/security/auth.go` | Line 54: `type TokenResolver struct` |
| OIDC setup | `internal/security/auth.go` | Line 71: `NewTokenResolver()` |
| Resolve identity | `internal/security/auth.go` | Line 149: `Resolve()` |
| JWT verification | `internal/security/auth.go` | Line 172: OIDC verifier |
| User ID extraction | `internal/security/auth.go` | Lines 179-197: Claims parsing |
| API key auth | `internal/security/auth.go` | Lines 156-162, 216: Simple mode |
| **HTTP Middleware** |
| Auth middleware | `internal/security/auth.go` | Line 297: `HTTPAuthMiddleware()` |
| Get user ID | `internal/security/auth.go` | Line 257: `GetUserID()` |
| Get client ID | `internal/security/auth.go` | Line 262: `GetClientID()` |
| Check admin | `internal/security/auth.go` | Line 267: `IsAdmin()` |

### Access Control Models

| Component | File | Key Types/Lines |
|-----------|------|-----------------|
| **Data Models** |
| AccessLevel enum | `internal/model/model.go` | Lines 20-28: owner/manager/writer/reader |
| Access rank | `internal/model/model.go` | Line 35: `accessRank()` |
| IsAtLeast check | `internal/model/model.go` | Line 31: `IsAtLeast()` |
| ConversationGroup | `internal/model/model.go` | Line 68: Fork tree root + ACL scope |
| Conversation | `internal/model/model.go` | Line 78: Single thread in group |
| Membership | `internal/model/model.go` | Line 99: Per-user group access |

### Membership Operations

| Operation | File | Function/Line |
|-----------|------|---------------|
| **Routes** |
| List memberships | `internal/plugin/route/memberships/memberships.go` | Line 41, Line 75: `listMemberships()` |
| Share conversation | `internal/plugin/route/memberships/memberships.go` | Line 44, Line 102: `shareConversation()` |
| Update membership | `internal/plugin/route/memberships/memberships.go` | Line 47: `updateMembership()` |
| Delete membership | `internal/plugin/route/memberships/memberships.go` | Line 50: `deleteMembership()` |
| **Store Interface** |
| ShareConversation | `internal/registry/store/plugin.go` | Line 260: Interface method |
| UpdateMembership | `internal/registry/store/plugin.go` | Line 261: Interface method |
| DeleteMembership | `internal/registry/store/plugin.go` | Line 262: Interface method |
| ListMemberships | - | Via store implementation |

### MCP Server Auth

| Component | File | Line/Function |
|-----------|------|---------------|
| Remote flags | `internal/cmd/mcp/cmd.go` | Line 104: `remoteFlags()` - URL, API key, bearer token |
| Remote client | `internal/cmd/mcp/cmd.go` | Line 140: `newRemoteClient()` - Header setup |
| Embedded auth | `internal/cmd/mcp/cmd.go` | Line 154: `ensureEmbeddedAuth()` - Auto config |
| Embedded constants | `internal/cmd/mcp/cmd.go` | Lines 17-20: clientID, apiKey, bearerToken |

### Privacy Enforcement

| Operation | File | Where userID is checked |
|-----------|------|-------------------------|
| Create conversation | `internal/plugin/route/conversations/conversations.go` | Line 125: `createConversation()` gets userID from context |
| List conversations | `internal/plugin/route/conversations/conversations.go` | Line 100: `listConversations()` filters by userID |
| Get conversation | `internal/plugin/route/conversations/conversations.go` | Line 227: `getConversation()` verifies access |
| List entries | `internal/plugin/route/entries/entries.go` | Line 55: `listEntries()` checks access |
| Search | `internal/plugin/route/search/search.go` | Line 78: `searchConversationsInReadTx()` |
| List group IDs | `internal/registry/store/plugin.go` | Line 290: `ListConversationGroupIDs()` - Returns only accessible groups |

---

## Summary

### Identity Resolution Chain

```
1. MCP Server
   ↓ (sends headers)
2. HTTP Auth Middleware
   ↓ (resolves to Identity)
3. Gin Context
   ↓ (stores userID, clientID, roles)
4. Route Handlers
   ↓ (extracts userID)
5. Store Operations
   ↓ (filters by userID membership)
6. Database Queries
   ↓ (JOIN conversation_memberships WHERE user_id = ?)
7. Returns ONLY accessible data
```

### Privacy Guarantees

✅ **User isolation enforced at every layer**
- Database: JOIN on memberships table
- Cache: User-scoped keys
- Search: Pre-filtered by accessible group IDs
- Events: User-scoped channels

✅ **No cross-user data leakage**
- Cannot list other users' conversations
- Cannot access other users' entries
- Cannot search other users' content

✅ **Explicit sharing required**
- Owner must grant access via memberships
- Access levels control permissions
- Sharing applies to entire fork tree (group)

✅ **Multi-tenant safe**
- One memory service instance serves multiple users
- Complete data isolation per user
- Admin bypass for system maintenance only

**TL;DR:** Memory is **strictly private** per user by default. Access is controlled via explicit memberships on conversation groups. Every operation is scoped to the authenticated user's ID, enforced from HTTP headers through database queries.
