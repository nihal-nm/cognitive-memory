# MemPalace: Embedding Model vs LLM — Critical Distinction

## TL;DR

MemPalace uses **TWO different kinds of models** that are often confused:

| Feature | Embedding Model | LLM (Language Model) |
|---------|----------------|----------------------|
| **Model Name** | `all-MiniLM-L6-v2` | `gemma4:e4b` (default) |
| **Size** | ~90 MB | ~2.8 GB (4B params) |
| **Runtime** | ONNX Runtime (built into ChromaDB) | Ollama (separate HTTP server) |
| **Purpose** | Convert text → vectors for similarity search | Classify and reason about text |
| **Required?** | **YES** (for vector search) | **NO** (optional) |
| **When Used** | Every mine, every search | Only during `init` and optional refinement |
| **Can Disable?** | Technically yes, but search degrades to BM25-only | Yes (`--no-llm`) |
| **CPU/GPU** | Supports CUDA, CoreML, DirectML | Ollama manages its own GPU |
| **Dependencies** | `onnxruntime` | None (HTTP API) |
| **Runs Where** | In-process with ChromaDB | Separate Ollama server |

**Important:** Disabling the LLM (`--no-llm`) does NOT disable embeddings. The embedding model keeps working.

---

## The Confusion: Why People Mix Them Up

Both are "AI models," but:

- **Embedding Model** = Small, specialized, encoder-only transformer
  - Input: "Configure ChromaDB for production"
  - Output: `[0.23, -0.45, 0.12, ..., 0.67]` (384 numbers)
  - No reasoning, no text generation, just vector conversion

- **LLM** = Large, general-purpose, decoder or encoder-decoder transformer
  - Input: "Is 'Alice' a PERSON or a PROJECT?"
  - Output: `{"label": "PERSON", "reason": "mentioned with pronouns and dialogue"}`
  - Full reasoning, text generation, classification

**Analogy:**
- **Embedding Model** = A camera that converts scenes into JPEG files
- **LLM** = A detective who looks at evidence and writes a report

Both are "AI," but they do completely different jobs.

---

## Deep Dive: Embedding Model

### What It Is

**Model:** `all-MiniLM-L6-v2` (sentence-transformers family)

**Architecture:**
- BERT-based encoder (6 layers, 384 hidden dimensions)
- Trained via contrastive learning on sentence pairs
- Optimized for semantic similarity (cosine distance in vector space)

**Output:**
- 384-dimensional vector (floating-point array)
- Similar sentences → similar vectors
- Different sentences → distant vectors

**Example:**

```python
embedding_function = ONNXMiniLM_L6_V2()

# Input text
texts = [
    "Configure ChromaDB for production",
    "Set up ChromaDB in production environment",
    "How to train a neural network"
]

# Output vectors (simplified for illustration)
vectors = embedding_function(texts)
# [
#   [0.23, -0.45, 0.12, ..., 0.67],  ← production setup
#   [0.25, -0.43, 0.14, ..., 0.65],  ← production setup (CLOSE)
#   [-0.89, 0.12, 0.56, ..., -0.23]  ← neural networks (FAR)
# ]

# Cosine similarity between vectors 0 and 1: 0.95 (very similar)
# Cosine similarity between vectors 0 and 2: 0.12 (different)
```

### When It Runs

**During Mining:**

```
User chat transcript
         ↓
    Normalize format
         ↓
    Chunk into drawers
         ↓
    ┌────────────────────────────────────┐
    │  EMBEDDING MODEL CALLED HERE       │
    │  Text → 384-dim vector             │
    └────────────────────────────────────┘
         ↓
    Store in ChromaDB
    (document + vector + metadata)
```

**During Search:**

```
User query: "chromadb setup"
         ↓
    ┌────────────────────────────────────┐
    │  EMBEDDING MODEL CALLED HERE       │
    │  Query text → 384-dim vector       │
    └────────────────────────────────────┘
         ↓
    HNSW index lookup (vector similarity)
         +
    BM25 full-text search
         ↓
    Hybrid ranked results
```

**Every single drawer** stored in the palace has an embedding vector. **Every single search** converts the query to a vector.

### How It's Invoked

**Internally by ChromaDB:**

```python
# File: mempalace/palace.py

from .embedding import get_embedding_function

def get_collection(palace_path: str):
    client = ChromaBackend.make_client(palace_path)
    
    # Get embedding function (ONNX Runtime)
    ef = get_embedding_function()  # ← Returns ONNXMiniLM_L6_V2 instance
    
    # Pass to ChromaDB collection
    collection = client.get_or_create_collection(
        name="memories",
        embedding_function=ef  # ← ChromaDB calls this for every embed
    )
    
    return collection
```

**User never calls it directly** — it's handled by ChromaDB internally during:
- `collection.add()` / `collection.upsert()` → embed documents
- `collection.query()` → embed query text

### Hardware Acceleration

**Configuration:**

```bash
# Auto-detect best available device
export MEMPALACE_EMBEDDING_DEVICE=auto

# Force NVIDIA GPU (requires onnxruntime-gpu)
export MEMPALACE_EMBEDDING_DEVICE=cuda

# Force Apple Neural Engine (macOS only)
export MEMPALACE_EMBEDDING_DEVICE=coreml

# Force DirectML (Windows, AMD/Intel GPUs)
export MEMPALACE_EMBEDDING_DEVICE=dml

# Force CPU (no acceleration)
export MEMPALACE_EMBEDDING_DEVICE=cpu
```

**Installation:**

```bash
# CPU only (default)
pip install mempalace

# NVIDIA GPU acceleration
pip install mempalace[gpu]
# This installs: onnxruntime-gpu

# Apple Neural Engine
pip install mempalace[coreml]
# This installs: onnxruntime-silicon (macOS ARM64 only)

# DirectML (Windows)
pip install mempalace[dml]
# This installs: onnxruntime-directml
```

**Runtime Resolution:**

```python
# File: mempalace/embedding.py

def _resolve_providers(device: str) -> tuple[list, str]:
    """Return (provider_list, effective_device)."""
    
    import onnxruntime as ort
    available = set(ort.get_available_providers())
    
    if device == "auto":
        # Try in order: CUDA → CoreML → DirectML → CPU
        if "CUDAExecutionProvider" in available:
            return (["CUDAExecutionProvider", "CPUExecutionProvider"], "cuda")
        if "CoreMLExecutionProvider" in available:
            return (["CoreMLExecutionProvider", "CPUExecutionProvider"], "coreml")
        if "DmlExecutionProvider" in available:
            return (["DmlExecutionProvider", "CPUExecutionProvider"], "dml")
        return (["CPUExecutionProvider"], "cpu")
    
    # ... handle explicit device requests ...
```

**Performance Impact:**

On a typical consumer GPU (RTX 3060):
- **CPU:** ~150 documents/sec
- **CUDA:** ~800 documents/sec (5.3x speedup)

For a 10,000-drawer mine:
- **CPU:** ~67 seconds
- **CUDA:** ~12 seconds

### Model Files

**Where they live:**

```
~/.cache/chroma/onnx_models/all-MiniLM-L6-v2/
├── onnx.tar.gz           ← Downloaded on first use
└── onnx/
    └── model.onnx        ← 90 MB ONNX model file
```

**First-run download:**

```
$ mempalace init /path/to/corpus

Downloading model files...
  all-MiniLM-L6-v2: 100% [=================] 90.3 MB

Embedding function initialized (device=cuda providers=['CUDAExecutionProvider'])
```

**Offline use:**

Once downloaded, the model is cached. No internet required for subsequent runs.

### What It Does NOT Do

❌ **Generate text** — It only produces vectors  
❌ **Classify entities** — It has no reasoning capability  
❌ **Understand context** — It's a bag-of-embeddings model  
❌ **Answer questions** — It's not a chat model  
❌ **Summarize documents** — It's not a generative model  

**It ONLY converts text to vectors for similarity search.**

---

## Deep Dive: LLM (Language Model)

### What It Is

**Model:** `gemma4:e4b-it-q4_K_M` (default, configurable)

**Architecture:**
- 4 billion parameter decoder-only transformer
- Instruction-tuned (the `-it` suffix)
- Quantized to 4-bit (the `q4_K_M` suffix)
- ~2.8 GB on disk, ~10.6 GB VRAM when loaded

**Output:**
- Natural language text
- Structured JSON (when requested)
- Classifications, summaries, reasoning

**Example:**

```python
from mempalace.llm_client import get_provider

provider = get_provider("ollama", model="gemma4:e4b")

response = provider.classify(
    system="Classify this entity as PERSON, PROJECT, or TOPIC.",
    user=json.dumps({
        "name": "Alice",
        "contexts": [
            "Alice suggested we switch to GraphQL",
            "I talked to Alice about the API design"
        ]
    }),
    json_mode=True
)

print(response.text)
# Output:
# {
#   "label": "PERSON",
#   "reason": "Mentioned with pronouns and dialogue context"
# }
```

### When It Runs

**Only during optional classification tasks:**

```
1. Init (mempalace init /path/to/corpus)
   ├─ Corpus origin detection (Tier 2)  ← LLM USED HERE
   ├─ Entity refinement (optional)      ← LLM USED HERE
   └─ Mining transcripts                ← NO LLM (embeddings only)

2. Closet generation (mempalace closet-llm ...)
   └─ Generate enriched topic indices   ← LLM USED HERE

3. Search (mempalace search "query")
   └─ Semantic search                   ← NO LLM (embeddings + BM25)

4. MCP tools (mempalace_search, mempalace_add_drawer, ...)
   └─ All palace operations             ← NO LLM (direct DB access)
```

**Key distinction:**

- **Embedding model:** Runs on EVERY drawer, EVERY search
- **LLM:** Runs ONCE during init, then never again (unless you re-run optional refinement)

### How It's Invoked

**Via HTTP API to Ollama:**

```python
# File: mempalace/llm_client.py

class OllamaProvider(LLMProvider):
    def classify(self, system: str, user: str, json_mode: bool = True) -> LLMResponse:
        endpoint = self.endpoint or "http://localhost:11434"
        
        payload = {
            "model": self.model,  # "gemma4:e4b"
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user}
            ],
            "stream": False
        }
        
        if json_mode:
            payload["format"] = "json"  # Force JSON output
        
        # HTTP POST to Ollama
        req = Request(
            f"{endpoint}/api/chat",
            data=json.dumps(payload).encode(),
            headers={"Content-Type": "application/json"}
        )
        
        with urlopen(req, timeout=self.timeout) as resp:
            result = json.load(resp)
            return LLMResponse(
                text=result["message"]["content"],
                model=self.model,
                provider="ollama",
                raw=result
            )
```

**Requires Ollama running:**

```bash
# Terminal 1: Start Ollama server
$ ollama serve

# Terminal 2: Pull model (first time only)
$ ollama pull gemma4:e4b

# Terminal 3: Run MemPalace init
$ mempalace init /path/to/corpus
```

### What It Does

**1. Corpus Origin Detection (Tier 2)**

```
Input: Sample conversation excerpts
Output: {
  "likely_ai_dialogue": true,
  "primary_platform": "Claude Code",
  "agent_persona_names": ["Claude"],
  "confidence": "high"
}
```

**2. Entity Refinement**

```
Input: List of candidate entities + context lines
Output: {
  "classifications": [
    {"name": "Alice", "label": "PERSON", "reason": "..."},
    {"name": "Angular", "label": "TOPIC", "reason": "..."}
  ]
}
```

**3. Closet Generation**

```
Input: Drawer content (up to 30K chars)
Output: {
  "topics": ["chromadb", "hnsw", "configuration", ...],
  "quotes": ["[User] how should we configure chromadb?", ...],
  "summary": "Discussion of ChromaDB configuration parameters..."
}
```

### Configuration

**Provider Options:**

```bash
# Ollama (local, default)
mempalace init /path/to/corpus \
    --llm-provider ollama \
    --llm-model gemma4:e4b

# Anthropic (cloud, requires API key)
mempalace init /path/to/corpus \
    --llm-provider anthropic \
    --llm-model claude-3-5-haiku-20241022 \
    --llm-api-key sk-ant-...

# OpenAI (cloud, requires API key)
mempalace init /path/to/corpus \
    --llm-provider openai-compat \
    --llm-model gpt-4o-mini \
    --llm-endpoint https://api.openai.com/v1 \
    --llm-api-key sk-...

# Disable entirely (heuristics only)
mempalace init /path/to/corpus --no-llm
```

**Environment Variables:**

```bash
export MEMPALACE_LLM_PROVIDER=ollama
export MEMPALACE_LLM_MODEL=gemma4:e4b
export MEMPALACE_LLM_ENDPOINT=http://localhost:11434
export MEMPALACE_LLM_API_KEY=  # Empty for local Ollama
```

### What It Does NOT Do

❌ **Generate embeddings** — That's the embedding model's job  
❌ **Run during search** — Search uses embeddings + BM25 only  
❌ **Run during mining** — Mining uses embeddings only  
❌ **Handle MCP tools** — MCP tools are direct database operations  
❌ **Chat with users** — That's Claude Code's job  

**It ONLY classifies and reasons about text during init/refinement.**

---

## Side-by-Side Comparison

### Example 1: Mining a Conversation

**Input:** Claude Code session transcript

```
> how do I configure chromadb?

Use hnsw:num_threads=1 to avoid the race condition...

> thanks!
```

**Embedding Model's Job:**

```python
# Convert each drawer to a vector
drawer_1 = "> how do I configure chromadb?"
vector_1 = embedding_model(drawer_1)
# → [0.23, -0.45, 0.12, ..., 0.67]  (384 numbers)

drawer_2 = "Use hnsw:num_threads=1 to avoid the race condition..."
vector_2 = embedding_model(drawer_2)
# → [0.25, -0.43, 0.15, ..., 0.64]  (384 numbers)

# Store both in ChromaDB with vectors
```

**LLM's Job:**

```
(LLM is NOT invoked during mining)
```

---

### Example 2: Searching

**Query:** "chromadb configuration"

**Embedding Model's Job:**

```python
# Convert query to vector
query_vector = embedding_model("chromadb configuration")
# → [0.24, -0.44, 0.13, ..., 0.66]  (384 numbers)

# Find similar vectors in HNSW index
similar_drawers = hnsw_index.search(query_vector, k=5)
# → Returns drawer IDs with cosine similarity scores
```

**LLM's Job:**

```
(LLM is NOT invoked during search)
```

---

### Example 3: Init with Entity Detection

**Corpus:** Chat transcripts mentioning "Alice"

**Embedding Model's Job:**

```python
# Embed all drawers as usual
for drawer in corpus:
    vector = embedding_model(drawer)
    store(drawer, vector)
```

**LLM's Job:**

```python
# Classify "Alice" as PERSON vs PROJECT vs TOPIC
response = llm.classify(
    system="Classify this entity...",
    user=json.dumps({
        "name": "Alice",
        "contexts": [
            "Alice suggested we switch to GraphQL",
            "I talked to Alice about the API"
        ]
    })
)
# → {"label": "PERSON", "reason": "..."}
```

**Both models run, but at different stages and for different purposes.**

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                    MemPalace Mining Flow                         │
└─────────────────────────────────────────────────────────────────┘

User Transcript
      │
      ▼
┌──────────────────┐
│ Normalize Format │  (no model)
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│ Chunk into       │  (no model, pure regex)
│ Drawers          │
└────────┬─────────┘
         │
         ▼
┌────────────────────────────────────────────┐
│ EMBEDDING MODEL (all-MiniLM-L6-v2)        │  ← REQUIRED
│                                            │
│ Input:  "Configure ChromaDB..."           │
│ Output: [0.23, -0.45, ..., 0.67]          │
│                                            │
│ Runtime: ONNX Runtime (in-process)        │
│ When:    EVERY drawer, EVERY search       │
└────────┬───────────────────────────────────┘
         │
         ▼
┌──────────────────┐
│ Store in ChromaDB│  (vector + metadata + document)
└────────┬─────────┘
         │
         ▼
    ┌────────────────────────────────────────────┐
    │ Optional: LLM Classification               │  ← OPTIONAL
    │                                            │
    │ gemma4:e4b via Ollama                      │
    │                                            │
    │ Input:  Entity candidates + contexts      │
    │ Output: {"label": "PERSON", ...}          │
    │                                            │
    │ Runtime: Ollama HTTP API (separate proc)  │
    │ When:    ONCE during init, then never     │
    └────────────────────────────────────────────┘
```

---

## Common Misconceptions

### ❌ "The LLM generates embeddings"

**Wrong.** The LLM (`gemma4:e4b`) never touches embeddings. That's the embedding model's job (`all-MiniLM-L6-v2`).

### ❌ "Disabling the LLM breaks search"

**Wrong.** Search uses embeddings + BM25, not the LLM. Disabling `--no-llm` only disables classification.

### ❌ "I need Ollama running for search to work"

**Wrong.** Ollama is only for the LLM. Embeddings run via ONNX Runtime (built into ChromaDB).

### ❌ "The embedding model does entity classification"

**Wrong.** The embedding model only converts text to vectors. It has no reasoning capability.

### ❌ "The LLM runs on every search"

**Wrong.** The LLM runs ONCE during init (optional). Search uses pre-computed embeddings.

### ❌ "I can use ChatGPT for embeddings"

**Wrong.** OpenAI's embedding API (`text-embedding-3-small`) produces different-dimensional vectors incompatible with MemPalace's ChromaDB setup. The embedding model is fixed to `all-MiniLM-L6-v2`.

### ❌ "Both models need GPU"

**Partially wrong.** Embedding model can use GPU (via ONNX Runtime providers). LLM uses GPU via Ollama's own management. They're independent.

---

## What Happens With Different Configurations

### Configuration 1: Default (Both Enabled)

```bash
mempalace init /path/to/corpus
# Uses: all-MiniLM-L6-v2 (embeddings) + gemma4:e4b (LLM)
```

**Result:**
- ✅ Vector search works
- ✅ BM25 search works
- ✅ Entity classification (PERSON/PROJECT/TOPIC)
- ✅ Corpus origin detection (Tier 2)
- ✅ Enriched closets (if requested)

---

### Configuration 2: LLM Disabled

```bash
mempalace init /path/to/corpus --no-llm
# Uses: all-MiniLM-L6-v2 (embeddings only)
```

**Result:**
- ✅ Vector search works (embeddings still run!)
- ✅ BM25 search works
- ⚠️ Entity classification falls back to regex heuristics
- ⚠️ Corpus origin detection (Tier 1 heuristics only)
- ❌ No enriched closets

**Common use case:** Privacy-sensitive environments, or when Ollama unavailable.

---

### Configuration 3: CUDA-Accelerated Embeddings + Cloud LLM

```bash
export MEMPALACE_EMBEDDING_DEVICE=cuda

mempalace init /path/to/corpus \
    --llm-provider anthropic \
    --llm-model claude-3-5-haiku-20241022 \
    --llm-api-key sk-ant-...
```

**Result:**
- ✅ Vector search works (5x faster with CUDA)
- ✅ BM25 search works
- ✅ Entity classification (higher quality with Haiku)
- ✅ Corpus origin detection (Tier 2, cloud API)
- ✅ Enriched closets (if requested)
- ⚠️ Costs ~$0.01 per init

**Common use case:** Maximum quality, willing to pay for cloud API.

---

### Configuration 4: CPU-Only, No LLM

```bash
export MEMPALACE_EMBEDDING_DEVICE=cpu

mempalace init /path/to/corpus --no-llm
```

**Result:**
- ✅ Vector search works (slower, ~150 docs/sec)
- ✅ BM25 search works
- ⚠️ Entity classification (regex only)
- ⚠️ Corpus origin detection (heuristics only)
- ❌ No enriched closets

**Common use case:** Older hardware, no GPU, no internet, maximum privacy.

---

## Troubleshooting

### Problem: "Embedding function not found"

**Symptoms:**
```
chromadb.errors.InvalidDimensionException: Embedding dimension 384 does not match collection 1536
```

**Cause:** Mixing embedding models. MemPalace uses `all-MiniLM-L6-v2` (384-dim). OpenAI uses `text-embedding-3-small` (1536-dim).

**Solution:** Don't mix embedding models. Stick with MemPalace's default.

---

### Problem: "Cannot reach http://localhost:11434"

**Symptoms:**
```
LLM corpus-origin tier failed (Cannot reach http://localhost:11434)
```

**Cause:** Ollama server not running.

**Solution:**
```bash
# Option 1: Start Ollama
ollama serve

# Option 2: Disable LLM
mempalace init /path/to/corpus --no-llm
```

---

### Problem: "CUDA provider not available"

**Symptoms:**
```
embedding_device='cuda' requested but CUDAExecutionProvider is not installed — falling back to CPU
```

**Cause:** `onnxruntime-gpu` not installed.

**Solution:**
```bash
pip install mempalace[gpu]
# or: pip install onnxruntime-gpu
```

---

### Problem: "Search returns no results"

**Debug checklist:**

1. **Check if embeddings are stored:**
   ```bash
   sqlite3 ~/.mempalace/palace/chroma.sqlite3 "SELECT COUNT(*) FROM embeddings;"
   ```

2. **Check if embedding dimension matches:**
   ```bash
   python -c "from mempalace.embedding import get_embedding_function; print(len(get_embedding_function()(['test'])[0]))"
   # Should output: 384
   ```

3. **Check if LLM is involved (it shouldn't be):**
   ```bash
   # Search does NOT use LLM, only embeddings
   mempalace search "query" --debug
   ```

---

### Problem: "Slow mining performance"

**Check which model is bottleneck:**

```bash
# Profile embedding speed
time python -c "
from mempalace.embedding import get_embedding_function
ef = get_embedding_function()
ef(['test'] * 1000)
"

# Check if GPU acceleration engaged
# Look for: "Embedding function initialized (device=cuda ...)"
```

**Solutions:**
- Enable GPU for embeddings: `export MEMPALACE_EMBEDDING_DEVICE=cuda`
- LLM speed doesn't matter (only runs once during init)

---

## FAQ

### Q: Can I use a different embedding model?

**A:** Not easily. ChromaDB collections are bound to their embedding dimension (384 for `all-MiniLM-L6-v2`). Switching models requires rebuilding the entire palace.

### Q: Can I use a different LLM?

**A:** Yes! Any Ollama model, Anthropic model, or OpenAI-compatible API works:

```bash
# Use qwen3:4b (faster, less accurate)
mempalace init /path/to/corpus --llm-model qwen3:4b

# Use Claude Haiku (cloud, costs money)
mempalace init /path/to/corpus \
    --llm-provider anthropic \
    --llm-model claude-3-5-haiku-20241022
```

### Q: Why not use OpenAI embeddings?

**A:** OpenAI's `text-embedding-3-small` produces 1536-dim vectors, incompatible with MemPalace's 384-dim setup. Migrating would require rebuilding every palace.

### Q: Do embeddings cost money?

**A:** No. The embedding model (`all-MiniLM-L6-v2`) runs locally via ONNX Runtime. Zero cost.

### Q: Does the LLM cost money?

**A:** Only if using a cloud provider:
- Ollama (local): $0
- Anthropic Haiku: ~$0.01 per init
- OpenAI GPT-4o-mini: ~$0.02 per init

### Q: Can I run both models on GPU?

**A:** Yes, independently:
- Embeddings: `export MEMPALACE_EMBEDDING_DEVICE=cuda`
- LLM: Ollama manages GPU automatically

### Q: What if I have multiple GPUs?

**Embeddings:**
```bash
# ONNX Runtime uses CUDA_VISIBLE_DEVICES
export CUDA_VISIBLE_DEVICES=0
export MEMPALACE_EMBEDDING_DEVICE=cuda
```

**LLM (Ollama):**
```bash
# Ollama uses its own GPU selection
ollama serve
# Check: ollama ps
```

### Q: Why is gemma4:e4b the default LLM?

**A:** From benchmarks, it has the best accuracy on entity/room classification (0.65, beating even 1.3T-parameter cloud models). See `benchmarks/model_eval/reports/2026-05-10-analysis.md`.

### Q: Can I skip the LLM entirely?

**A:** Yes! Use `--no-llm`. Embeddings still work, search still works. You only lose classification quality.

---

## Performance Comparison

### Embedding Model Performance

**Hardware:** RTX 3060 (consumer GPU)

| Device | Throughput | 10K Drawers |
|--------|------------|-------------|
| CPU | 150 docs/sec | ~67 sec |
| CUDA | 800 docs/sec | ~12 sec |
| CoreML (M1) | 600 docs/sec | ~17 sec |

**Takeaway:** GPU acceleration for embeddings is worth it if mining large corpora frequently.

---

### LLM Performance

**Hardware:** RTX 3060

| Model | Task | Latency (P50) | Accuracy |
|-------|------|---------------|----------|
| `gemma4:e4b` | Entity classification | 230 ms | 0.65 (best) |
| `qwen3:4b` | Entity classification | 109 ms | 0.61 |
| `claude-3-5-haiku` (cloud) | Entity classification | 180 ms | ~0.70 (estimated) |

**For 150 entities (typical init):**
- `gemma4:e4b`: ~35 seconds total
- `qwen3:4b`: ~16 seconds total
- `claude-3-5-haiku`: ~27 seconds + $0.01 cost

**Takeaway:** LLM speed matters less (only runs once). Accuracy matters more.

---

## Summary Table

| Operation | Embedding Model | LLM | Both? |
|-----------|----------------|-----|-------|
| **Mining transcripts** | ✅ (every drawer) | ❌ | Embedding only |
| **Search queries** | ✅ (every search) | ❌ | Embedding only |
| **Entity classification** | ❌ | ✅ (init only) | LLM only |
| **Corpus origin detection** | ❌ | ✅ (init only) | LLM only |
| **Closet generation** | ❌ | ✅ (optional) | LLM only |
| **MCP tools** | ❌ | ❌ | Neither (direct DB) |
| **Background hooks** | ✅ (mining) | ❌ | Embedding only |

---

## Key Takeaways

1. **Two completely different models** serving different purposes
2. **Embedding model is required** for vector search (runs constantly)
3. **LLM is optional** for classification (runs once during init)
4. **Disabling LLM does NOT disable embeddings** (common misconception)
5. **Search never uses LLM** (only embeddings + BM25)
6. **Both can use GPU**, but independently configured
7. **Embedding model is fixed** (`all-MiniLM-L6-v2`, can't change easily)
8. **LLM is swappable** (Ollama models, Anthropic, OpenAI, etc.)

---

## References

- Embedding implementation: `mempalace/embedding.py`
- LLM client: `mempalace/llm_client.py`
- LLM usage: `ARCHITECTURE_LLM_USAGE.md`
- Model benchmarks: `benchmarks/model_eval/reports/2026-05-10-analysis.md`
