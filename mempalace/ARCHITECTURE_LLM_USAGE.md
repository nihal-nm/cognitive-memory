# MemPalace LLM Usage: Local Intelligence Layer

## Overview

MemPalace uses a **local LLM** (default: `gemma4:e4b` via Ollama) for optional intelligence tasks during mining and classification. These operations are:

- **100% optional** — core memory operations work without any LLM
- **Local-first** — default is Ollama on localhost, no external API required
- **Opt-in** — user must explicitly enable (`--llm-provider`) or disable (`--no-llm`)
- **BYOK-compatible** — supports Anthropic, OpenAI, any OpenAI-compatible endpoint

The LLM is **NOT** used for:
- Embeddings (handled by sentence-transformers)
- Chunking (pure regex/heuristics)
- Search (BM25 + HNSW)
- MCP server operations (direct database access)

---

## Why `gemma4:e4b`?

From model evaluation benchmarks (see `benchmarks/model_eval/reports/2026-05-10-analysis.md`):

> **`gemma4:e4b-it-q4_K_M` is the new local leader for room classification.** Closed-set 0.62 (above qwen3:4b q4_K_M's 0.61), open-set **0.65 (highest score across ALL measured models, local AND cloud)**. The cloud "ceiling" on open-set was 0.61. A 4B local model now exceeds every cloud reference up to 1T parameters.

**Trade-offs:**
- **Accuracy:** Best-in-class for entity/room classification
- **Speed:** 230ms p50 (2.1x slower than qwen3:4b's 109ms)
- **Memory:** 10.6 GB VRAM (1.4x more than qwen3:4b's 7.5 GB)

For MemPalace's use case (batch classification during init/mining, not real-time chat), the accuracy improvement outweighs the latency cost.

---

## The Three LLM Operations

### 1. Corpus Origin Detection (Tier 2)

**File:** `mempalace/corpus_origin.py`

**Purpose:** Detect whether a corpus is an AI dialogue transcript or human-written content, and if AI dialogue, extract metadata about the platform and personas.

**Two-tier approach:**

#### Tier 1: Heuristic (always runs, no LLM)
```python
def detect_origin_heuristic(samples: list[str]) -> CorpusOriginResult
```

- Fast, zero-cost, regex-based
- Looks for:
  - Turn markers (`>`, `User:`, `Assistant:`)
  - AI brand terms (`Claude Code`, `ChatGPT`, `GPT-4`, `MCP`)
  - Platform-specific patterns
- Returns hypothesis with confidence score

#### Tier 2: LLM Refinement (optional)
```python
def detect_origin_llm(samples: list[str], provider: LLMProvider) -> CorpusOriginResult
```

- Uses LLM's pre-trained knowledge of Claude/ChatGPT/Gemini
- Extracts:
  - `primary_platform` (e.g., "Claude Code", "ChatGPT")
  - `user_name` (if identifiable from transcript)
  - `agent_persona_names` (custom names user assigned to AI)
- **Does NOT override** Tier 1's `likely_ai_dialogue` or `confidence`
- **Merges** persona/platform fields into Tier 1 result

**Why this matters:**

Without origin detection, a drawer containing "my three sons" in a Claude Code dialogue corpus would be misclassified as "three biological children" instead of "three AI instances."

**Example LLM prompt:**

```
You are analyzing conversation transcripts to determine their origin.

SAMPLES:
> how do I configure chromadb?

Use hnsw:num_threads=1 to avoid the race condition...

> thanks claude!

---

Output JSON:
{
  "likely_ai_dialogue": true,
  "confidence": "high",
  "primary_platform": "Claude Code",
  "user_name": null,
  "agent_persona_names": ["Claude"],
  "evidence": ["turn markers detected", "explicit AI name reference"]
}
```

**Invocation:**

```bash
# Default: Ollama with gemma4:e4b
mempalace init /path/to/corpus

# External API (requires explicit flag + key)
mempalace init /path/to/corpus \
    --llm-provider anthropic \
    --llm-model claude-3-5-haiku-20241022 \
    --llm-api-key sk-ant-...

# Disable LLM entirely (heuristics only)
mempalace init /path/to/corpus --no-llm
```

**Result stored in:**
```
palace_dir/.mempalace/origin.json
```

---

### 2. Entity Refinement

**File:** `mempalace/llm_refine.py`

**Purpose:** Reclassify regex-detected entities (capitalized words, git authors, manifest names) into proper categories: PERSON, PROJECT, TOPIC, COMMON_WORD, AMBIGUOUS.

**Phase 1: Regex Detection** (no LLM)
- Scans corpus for capitalized tokens
- Extracts git authors from `.git/`
- Reads `package.json`, `pyproject.toml`, etc.
- Produces candidate set

**Phase 2: LLM Refinement** (optional)
```python
def refine_entities(
    detected: dict,
    corpus_text: str,
    provider: LLMProvider,
    interactive: bool = True
) -> RefineResult
```

- Feeds candidates + context lines to LLM
- Batch size: 25 candidates per call (tuned for 4B models)
- Context: 3 lines per candidate, max 240 chars each
- Returns: `PERSON | PROJECT | TOPIC | COMMON_WORD | AMBIGUOUS`

**Example LLM prompt:**

```
CANDIDATES:

1. Alice  (currently: PERSON)
   Context:
   - "Alice suggested we switch to GraphQL"
   - "I talked to Alice about the API design"
   - "Alice's PR merged yesterday"

2. Angular  (currently: PROJECT)
   Context:
   - "Migrating from Angular to React"
   - "Angular framework documentation"
   - "The Angular team deprecated that API"

3. Created  (currently: PERSON)
   Context:
   - "Created: 2026-04-24"
   - "Created by: deploy script"
   - "File created successfully"

---

Classify each. Output JSON:
{
  "classifications": [
    {"name": "Alice", "label": "PERSON", "reason": "Colleague mentioned with pronouns and dialogue"},
    {"name": "Angular", "label": "TOPIC", "reason": "Third-party framework, not user's own project"},
    {"name": "Created", "label": "COMMON_WORD", "reason": "Appears only in metadata timestamps"}
  ]
}
```

**Why this matters:**

Regex can't distinguish:
- "Alice" (person) vs "Alice" (project codename)
- "Angular" (framework/topic) vs "Angular" (user's own project named Angular)
- "Created" (common word) vs "Created" (person's nickname)

The LLM uses context to disambiguate.

**Invocation:**

```bash
# During init (automatic if LLM enabled)
mempalace init /path/to/corpus

# Standalone refinement on existing palace
mempalace refine-entities ~/.mempalace/palace \
    --llm-provider ollama \
    --llm-model gemma4:e4b
```

**Interactive UX:**

```
Refining entities with gemma4:e4b...
  Batch 1/5: 25 candidates... ✓ (2.3s)
  Batch 2/5: 25 candidates... ✓ (2.1s)
  ^C
  
Refinement cancelled. Processed 50/125 candidates.
Reclassified: 12 | Dropped: 8 (common words)
```

---

### 3. Closet Generation (Enriched Topic Index)

**File:** `mempalace/closet_llm.py`

**Purpose:** Generate richer topic indices ("closets") from drawer content for better searchability.

**Regex closets (always created):**
- Action verbs
- Headers (# Markdown, --- separators)
- Proper nouns (capitalized words)
- **Limitation:** Misses implicit topics, foreign languages, contextual references

**LLM closets (optional):**
```python
def generate_closet_via_llm(
    content: str,
    wing: str,
    room: str,
    source_file: str,
    provider: LLMProvider
) -> dict
```

- Reads full drawer content (up to 30K chars)
- Extracts:
  - **Topics:** 8-15 distinctive terms (proper nouns, technical terms, concepts)
  - **Quotes:** 2-5 verbatim quotes with speaker attribution
  - **Summary:** 2-3 sentences (WHO, WHAT, WHY)
- Outputs valid JSON

**Example LLM prompt:**

```
You are reading content filed in a memory palace. Generate a
topic-dense index that will be used to find this content later.

Source: session_2026-05-10.jsonl
Wing: wing_myproject | Room: technical

CONTENT:
> how should we configure chromadb?

Use hnsw:num_threads=1 to avoid the race condition in 
repairConnectionsForUpdate. Also set hnsw:space=cosine 
for similarity search.

> what about the index size limit?

Set hnsw:construction_ef and hnsw:M based on your dataset...

---

Output JSON:
{
  "topics": [
    "chromadb",
    "hnsw",
    "num_threads",
    "cosine similarity",
    "construction_ef",
    "race condition",
    "repairConnectionsForUpdate",
    "index tuning"
  ],
  "quotes": [
    "[Assistant] Use hnsw:num_threads=1 to avoid the race condition",
    "[User] how should we configure chromadb?"
  ],
  "summary": "Discussion of ChromaDB HNSW configuration parameters. Covers thread safety (num_threads=1 to prevent race), similarity metric (cosine), and index tuning parameters (construction_ef, M) based on dataset size."
}
```

**Why this matters:**

Searching for "vector database setup" won't match "chromadb configuration" without the enriched index. The LLM bridges semantic gaps that BM25 alone can't handle.

**Invocation:**

```bash
# Standalone closet regeneration
mempalace closet-llm ~/.mempalace/palace \
    --endpoint http://localhost:11434/v1 \
    --model gemma4:e4b

# Or use external API
mempalace closet-llm ~/.mempalace/palace \
    --endpoint https://api.openai.com/v1 \
    --model gpt-4o-mini \
    --key sk-...
```

**Output:**

Closets are stored alongside drawers in ChromaDB:
- Collection: `{palace_name}_closets`
- Metadata: `wing`, `room`, `source_file`, `closet_type` (regex vs llm)
- Searchable via same hybrid BM25 + HNSW

---

## LLM Client Abstraction

**File:** `mempalace/llm_client.py`

**Three providers, one interface:**

```python
class LLMProvider:
    def classify(
        self, 
        system: str, 
        user: str, 
        json_mode: bool = True,
        think: Optional[bool] = None
    ) -> LLMResponse:
        """Send (system, user) prompt, get structured JSON response."""
```

### Provider 1: Ollama (default)

```python
provider = OllamaProvider(
    model="gemma4:e4b",
    endpoint="http://localhost:11434",
    timeout=120
)
```

**Endpoint:** `POST /api/chat`

**JSON mode:** `{"format": "json"}`

**Local-first:** No external API, fully offline.

### Provider 2: OpenAI-Compatible

```python
provider = OpenAICompatProvider(
    model="gpt-4o-mini",
    endpoint="https://api.openai.com/v1",
    api_key="sk-...",
    timeout=120
)
```

**Endpoint:** `POST /v1/chat/completions`

**JSON mode:** `{"response_format": {"type": "json_object"}}`

**Covers:**
- OpenAI API
- OpenRouter
- LM Studio
- llama.cpp server
- vLLM
- Groq, Fireworks, Together
- Most self-hosted setups

### Provider 3: Anthropic

```python
provider = AnthropicProvider(
    model="claude-3-5-haiku-20241022",
    endpoint="https://api.anthropic.com",
    api_key="sk-ant-...",
    timeout=120
)
```

**Endpoint:** `POST /v1/messages`

**JSON mode:** Prompt-level instruction (no API parameter)

**Use case:** Users who want Haiku quality without local model setup.

---

## Privacy & Safety

### 1. Local-First by Default

**Ollama on localhost is NOT an external service:**

```python
def _endpoint_is_local(url: str) -> bool:
    """Detect local vs external endpoints."""
    host = urlparse(url).hostname.lower()
    
    # Local: localhost, 127.0.0.1, ::1
    if host in {"localhost", "127.0.0.1", "::1"}:
        return True
    
    # Local: .local (mDNS/Bonjour)
    if host.endswith(".local"):
        return True
    
    # Local: RFC1918 private IPs
    if host.startswith(("10.", "192.168.")):
        return True
    if host.startswith("172."):
        parts = host.split(".")
        if 16 <= int(parts[1]) <= 31:
            return True
    
    # Local: Tailscale CGNAT (100.64.0.0/10)
    if host.startswith("100."):
        parts = host.split(".")
        if 64 <= int(parts[1]) <= 127:
            return True
    
    # Local: IPv6 unique-local (fc00::/7)
    if host.startswith(("fc", "fd")):
        return True
    
    return False
```

### 2. External API Warning (Issue #24)

When user configures an external API (Anthropic, OpenAI, cloud services):

```
⚠ anthropic is an EXTERNAL API. Your folder content will be sent 
to the provider during init. MemPalace does not control how the 
provider logs, retains, or uses your data. Pass --no-llm to keep 
init fully local.
```

Displayed **before** any data is sent, giving user chance to cancel.

### 3. No Silent Fallbacks

```python
# NEVER do this:
try:
    llm_result = call_ollama()
except:
    llm_result = call_anthropic()  # ❌ silent data exfiltration
```

MemPalace philosophy:
- **Explicit is better than implicit**
- User must configure external providers via flags
- No env-var-based silent cloud fallbacks
- Failures are logged, not silently bypassed

---

## Performance Characteristics

**From benchmarks on consumer hardware (Z690 platform, RTX GPU):**

| Model | Task | P50 Latency | P95 Latency | VRAM | Accuracy |
|-------|------|-------------|-------------|------|----------|
| `gemma4:e4b-it-q4_K_M` | Room classification | 230 ms | 266 ms | 10.6 GB | **0.65** (best) |
| `qwen3:4b-it-q4_K_M` | Room classification | 109 ms | 123 ms | 7.5 GB | 0.61 |
| `gemma3:1b-it-q4_K_M` | Room classification | 218 ms | 319 ms | 1.4 GB | 0.22 (poor) |
| `gemma3:270m-it-q8_0` | Room classification | 254 ms | 382 ms | 742 MB | 0.05 (unusable) |

**Batch processing impact:**

For a 2000-file corpus with 150 entities to refine:
- **Tier 1 heuristics:** ~2 seconds (regex only)
- **Tier 2 LLM (gemma4:e4b):** ~25 seconds (6 batches × 25 candidates × 230ms)
- **Tier 2 LLM (Anthropic Haiku):** ~15 seconds (faster but costs ~$0.01)

**Closet generation:**

For 5000 drawers:
- **Regex closets:** ~30 seconds (built during mining)
- **LLM closets (gemma4:e4b):** ~20 minutes (5000 × 230ms)
- **LLM closets (GPT-4o-mini):** ~10 minutes (faster but costs ~$2.50)

---

## Configuration Reference

### Command-Line Flags

```bash
# LLM provider selection
--llm-provider {ollama|openai-compat|anthropic}

# Model name
--llm-model MODEL_NAME

# Endpoint override
--llm-endpoint URL

# API key (if required)
--llm-api-key KEY

# Disable LLM entirely
--no-llm
```

### Environment Variables

```bash
# Provider (default: ollama)
export MEMPALACE_LLM_PROVIDER=ollama

# Model (default: gemma4:e4b)
export MEMPALACE_LLM_MODEL=gemma4:e4b

# Endpoint (default: http://localhost:11434 for Ollama)
export MEMPALACE_LLM_ENDPOINT=http://localhost:11434

# API key (if needed)
export MEMPALACE_LLM_API_KEY=sk-...
```

**Precedence:** CLI flags > environment variables > defaults

### Privacy Best Practices

1. **Use Ollama by default** — fully local, no data leaves your machine
2. **External APIs require explicit flags** — no silent cloud fallbacks
3. **Review privacy warning** — displayed before any external API call
4. **Use `--no-llm`** — when privacy is paramount or LLM is unavailable

---

## When LLM is NOT Used

MemPalace core operations remain **100% LLM-free:**

### ✅ No LLM Required

- **Embeddings:** sentence-transformers (local model, not generative)
- **Chunking:** regex patterns, exchange detection
- **Normalization:** format detection, noise stripping
- **Search:** BM25 (SQLite FTS) + HNSW (vector similarity)
- **Filing:** ChromaDB upsert, deterministic IDs
- **Knowledge graph:** SQLite temporal facts
- **MCP server:** direct database access
- **Hooks:** background mining triggers

### ⚠️ LLM Optional (Can Disable)

- **Corpus origin detection (Tier 2):** platform/persona extraction
- **Entity refinement:** PERSON vs PROJECT vs TOPIC classification
- **Closet generation:** enriched topic indexing

### 🚫 Never Uses LLM

- **Conversation with user:** that's Claude Code's job, not MemPalace's
- **Diary writes:** AI writes diary via MCP tools, MemPalace just stores it
- **Embeddings:** handled by sentence-transformers, not LLM

---

## Alternative Models

### Faster, Less Accurate

```bash
# qwen3:4b — 2.1x faster, 1.4x less VRAM, 6% less accurate
mempalace init /path/to/corpus \
    --llm-model qwen3:4b-it-q4_K_M

# qwen2.5:3b — 3x faster, 2x less VRAM, 15% less accurate
mempalace init /path/to/corpus \
    --llm-model qwen2.5:3b-instruct-q4_K_M
```

### Cloud (Higher Quality)

```bash
# Anthropic Haiku — faster than gemma4:e4b, costs ~$0.01 per init
mempalace init /path/to/corpus \
    --llm-provider anthropic \
    --llm-model claude-3-5-haiku-20241022 \
    --llm-api-key sk-ant-...

# OpenAI GPT-4o-mini — fastest, costs ~$0.02 per init
mempalace init /path/to/corpus \
    --llm-provider openai-compat \
    --llm-model gpt-4o-mini \
    --llm-endpoint https://api.openai.com/v1 \
    --llm-api-key sk-...
```

### Experimental Large Models

```bash
# DeepSeek V4 Pro — 1.3T params, 7% better than gemma4:e4b on room classification
# Requires API access (not available via Ollama as of 2026-05-15)
mempalace init /path/to/corpus \
    --llm-provider openai-compat \
    --llm-model deepseek-v4-pro \
    --llm-endpoint https://api.deepseek.com/v1 \
    --llm-api-key sk-...
```

---

## Summary

The LLM layer in MemPalace is:

1. **Optional** — core memory works without it
2. **Local-first** — default is Ollama on localhost
3. **Focused** — used only for classification, not generation
4. **Transparent** — privacy warnings before external API calls
5. **Accurate** — gemma4:e4b beats all cloud models (up to 1.3T params) on key tasks

**Philosophy:**

> Use the LLM's pre-trained knowledge of the world (Claude, ChatGPT, common names, frameworks) to classify corpus-specific entities, not to re-discover what "Claude" or "Angular" means. Keep the user's content local unless they explicitly opt into external APIs.

The result: **better entity disambiguation, better search, better recall** — without sacrificing privacy or requiring API keys.

---

## References

- LLM client: `mempalace/llm_client.py`
- Corpus origin: `mempalace/corpus_origin.py`
- Entity refinement: `mempalace/llm_refine.py`
- Closet generation: `mempalace/closet_llm.py`
- Model benchmarks: `benchmarks/model_eval/reports/2026-05-10-analysis.md`
