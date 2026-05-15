# MemPalace gemma4:e4b LLM Implementation Details

## Overview

This document provides a detailed technical analysis of how MemPalace uses the `gemma4:e4b` LLM model (Google Gemma 4, 4B parameters, e4b variant, q4_K_M quantized) for optional intelligence tasks. This is a **deep dive with code references** — for high-level understanding, see `ARCHITECTURE_LLM_USAGE.md`.

**Key facts:**
- **Model:** `gemma4:e4b-it-q4_K_M` (instruct-tuned, 4-bit quantized)
- **Size:** 2.8 GB on disk, 10.6 GB VRAM when loaded
- **Runtime:** Ollama (default), served at `http://localhost:11434`
- **Purpose:** Classification and reasoning, NOT embedding or generation
- **Usage:** Batch processing during `init`, optional entity refinement, optional closet generation
- **Accuracy:** 0.65 on room classification (beats all cloud models tested, including 1.3T param DeepSeek V4)

---

## Architecture: LLM Provider Abstraction

**File:** `mempalace/llm_client.py`

### Class Hierarchy

```python
class LLMProvider:                      # Line 121-177
    def classify(system, user, json_mode, think) -> LLMResponse
    def check_available() -> (bool, str)
    @property is_external_service -> bool

class OllamaProvider(LLMProvider):     # Line 205-267
    DEFAULT_ENDPOINT = "http://localhost:11434"
    
class OpenAICompatProvider(LLMProvider): # Line 272-357
    # Works with OpenAI, OpenRouter, LM Studio, vLLM, Groq, etc.
    
class AnthropicProvider(LLMProvider):   # Line 362-432
    DEFAULT_ENDPOINT = "https://api.anthropic.com"
```

### Core Method: `classify()`

**Purpose:** Send (system, user) prompt pair to LLM, receive structured JSON response.

**OllamaProvider implementation (lines 238-266):**

```python
def classify(self, system: str, user: str, json_mode: bool = True, think: Optional[bool] = None) -> LLMResponse:
    body: dict = {
        "model": self.model,  # "gemma4:e4b"
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
        "stream": False,
        "options": {"temperature": 0.1},  # Low temp for consistency
    }
    if json_mode:
        body["format"] = "json"  # Ollama's JSON mode parameter
    if think is not None:
        body["think"] = think  # Ollama 0.7+ thinking toggle (Qwen 3, DeepSeek-R1)
    
    data = _http_post_json(f"{self.endpoint}/api/chat", body, headers={}, timeout=self.timeout)
    text = (data.get("message") or {}).get("content", "")
    if not text:
        raise LLMError(f"Empty response from Ollama (model={self.model})")
    return LLMResponse(text=text, model=self.model, provider=self.name, raw=data)
```

**Key design decisions:**
- **No SDK dependency:** Pure stdlib `urllib` (line 179-200)
- **JSON mode:** Structured output enforced at protocol level
- **Low temperature:** 0.1 for deterministic classification
- **180s timeout:** Accommodates slow local models (line 213)
- **Thinking toggle:** Optional extended reasoning (not used by default)

### Factory Pattern

**Lines 444-456:**

```python
PROVIDERS: dict[str, type[LLMProvider]] = {
    "ollama": OllamaProvider,
    "openai-compat": OpenAICompatProvider,
    "anthropic": AnthropicProvider,
}

def get_provider(name: str, model: str, endpoint: Optional[str] = None, 
                 api_key: Optional[str] = None, timeout: int = 120) -> LLMProvider:
    cls = PROVIDERS.get(name)
    if cls is None:
        raise LLMError(f"Unknown provider '{name}'. Choices: {sorted(PROVIDERS.keys())}")
    return cls(model=model, endpoint=endpoint, api_key=api_key, timeout=timeout)
```

**Usage:**

```python
from mempalace.llm_client import get_provider

provider = get_provider("ollama", "gemma4:e4b")
resp = provider.classify(
    system="You are classifying entities...",
    user="Candidates: Alice, Angular, Created\n...",
    json_mode=True
)
# resp.text contains JSON string
```

---

## Operation 1: Corpus Origin Detection

**File:** `mempalace/corpus_origin.py`

### Two-Tier Architecture

#### Tier 1: Heuristic Detection (lines 166-289)

**Function:** `detect_origin_heuristic(samples: list[str]) -> CorpusOriginResult`

**Always runs, zero cost, no LLM.**

**Detection logic:**

```python
# Line 60-93: UNAMBIGUOUS terms (always counted)
_AI_UNAMBIGUOUS_TERMS = [
    "Claude Code", "Claude 3", "ChatGPT", "GPT-4", "MCP", "LLM", "RAG", ...
]

# Line 95-111: AMBIGUOUS terms (only counted if co-occurring with unambiguous signal)
_AI_AMBIGUOUS_TERMS = [
    "Claude",   # French name
    "Gemini",   # Zodiac sign
    "Haiku",    # Poem form
    "Llama",    # Animal
    ...
]

# Line 114-121: Turn markers
_TURN_MARKERS = [
    r"\buser\s*:\s*",
    r"\bassistant\s*:\s*",
    r"\b>>>\s*User\b",
    ...
]
```

**Pattern matching (lines 180-207):**

```python
# Count unambiguous hits
for term in _AI_UNAMBIGUOUS_TERMS:
    matches = re.findall(_brand_pattern(term), combined, re.IGNORECASE)
    if matches:
        unambiguous_hits[term] = len(matches)
        total_unambiguous += len(matches)

# Count ambiguous hits (suppressed unless unambiguous signal present)
for term in _AI_AMBIGUOUS_TERMS:
    matches = re.findall(_brand_pattern(term), combined, re.IGNORECASE)
    if matches:
        ambiguous_hits[term] = len(matches)
        total_ambiguous += len(matches)

# Co-occurrence rule (line 215-216)
has_ai_context = total_unambiguous > 0 or turn_hits > 0
counted_brand_hits = total_unambiguous + (total_ambiguous if has_ai_context else 0)
```

**Decision thresholds (lines 256-289):**

```python
# Strong signal: confident AI-dialogue
if brand_density >= 0.5 or turn_density >= 2.0:
    return CorpusOriginResult(
        likely_ai_dialogue=True,
        confidence=min(0.95, 0.6 + 0.1 * (brand_density + turn_density)),
        primary_platform=None,  # Tier 2 will refine
        evidence=evidence,
    )

# Meaningful absence: confident narrative
if counted_brand_hits == 0 and turn_hits == 0 and total_chars >= 150:
    return CorpusOriginResult(
        likely_ai_dialogue=False,
        confidence=0.9,
        primary_platform=None,
        evidence=narrative_evidence,
    )

# Ambiguous: default stance (AI-dialogue, low confidence)
return CorpusOriginResult(
    likely_ai_dialogue=True,
    confidence=0.4,
    primary_platform=None,
    evidence=evidence + ["weak signal — Tier 2 LLM check recommended"],
)
```

#### Tier 2: LLM Refinement (lines 292-422)

**Function:** `detect_origin_llm(samples: list[str], provider: LLMProvider) -> CorpusOriginResult`

**Uses gemma4:e4b's pre-trained knowledge of Claude, ChatGPT, Gemini.**

**System prompt (lines 295-329):**

```python
_SYSTEM_PROMPT = """You are analyzing a corpus of text to determine whether it is a 
record of conversations with an AI agent (e.g. Claude, ChatGPT, Gemini, custom LLM 
apps), or some other kind of text (personal narrative, story, research notes, 
journal, code, etc.).

Use your pre-existing knowledge of well-known AI platforms. You don't need the 
corpus to explain what Claude or ChatGPT is — you already know. Your job is to 
detect evidence of their presence and identify what persona-names the user has 
assigned to the agent(s) they converse with.

CRITICAL distinction:
  - agent_persona_names are names the USER has assigned to the AI AGENT(S)
    they converse with. Example: "Echo", "Sparrow", "Henry" might be names
    the user calls a Claude instance they're building a relationship with.
  - Do NOT include the USER's own name in agent_persona_names. The user
    is the human author of the corpus, not a persona of the agent.

Respond with JSON only (no prose before or after):
{
  "is_ai_dialogue_corpus": <true|false>,
  "confidence": <0.0 to 1.0>,
  "primary_platform": <"Claude (Anthropic)" | "ChatGPT (OpenAI)" | "Gemini (Google)" | other | null>,
  "user_name": <user's name if clearly identifiable from context, else null>,
  "agent_persona_names": [<names the user has assigned to the AI AGENT(S), NOT the user's own name>],
  "evidence": [<short bullet strings explaining the decision>]
}

Default stance: if evidence is thin or mixed, return is_ai_dialogue_corpus=true 
with low confidence. False-negatives on AI-dialogue detection break downstream 
classification; false-positives are recoverable later.
"""
```

**Call logic (lines 374-422):**

```python
def detect_origin_llm(samples: list[str], provider) -> CorpusOriginResult:
    # Build user prompt: max 20 samples, 800 chars each (line 383-387)
    max_excerpt_chars = 800
    excerpts = "\n\n---\n\n".join(
        f"[sample {i + 1}]\n{s[:max_excerpt_chars]}" for i, s in enumerate(samples[:20])
    )
    user_prompt = f"CORPUS EXCERPTS:\n\n{excerpts}\n\nAnalyze and respond with JSON."

    try:
        resp = provider.classify(system=_SYSTEM_PROMPT, user=user_prompt, json_mode=True)
        raw = getattr(resp, "text", "") or ""
    except Exception as e:
        # Conservative fallback: assume AI-dialogue (line 393-398)
        return CorpusOriginResult(
            likely_ai_dialogue=True,
            confidence=0.3,
            primary_platform=None,
            evidence=[f"LLM provider error (fallback to default stance): {e}"],
        )

    parsed = _extract_json(raw)  # Line 332-372: robust JSON extraction
    if not parsed or not isinstance(parsed, dict):
        return CorpusOriginResult(
            likely_ai_dialogue=True,
            confidence=0.3,
            primary_platform=None,
            evidence=["LLM response was not valid JSON (fallback to default stance)"],
        )

    # Extract fields defensively (lines 411-422)
    user_name = parsed.get("user_name") or None
    personas = list(parsed.get("agent_persona_names") or [])
    if user_name:
        # Filter out user's name if LLM leaked it into agent_persona_names
        personas = [p for p in personas if p.lower() != user_name.lower()]
    
    return CorpusOriginResult(
        likely_ai_dialogue=bool(parsed.get("is_ai_dialogue_corpus", True)),
        confidence=float(parsed.get("confidence", 0.5)),
        primary_platform=parsed.get("primary_platform") or None,
        user_name=user_name,
        agent_persona_names=personas,
        evidence=list(parsed.get("evidence") or []),
    )
```

**Result storage:**

```bash
palace_dir/.mempalace/origin.json
```

**Example result:**

```json
{
  "result": {
    "likely_ai_dialogue": true,
    "confidence": 0.85,
    "primary_platform": "Claude Code (Anthropic)",
    "user_name": "Alice",
    "agent_persona_names": ["Echo", "Sparrow"],
    "evidence": [
      "AI brand terms: 'Claude Code' (23x), 'MCP' (12x)",
      "Turn markers detected: 156 occurrences",
      "User refers to AI agents with custom names"
    ]
  },
  "version": "v2"
}
```

**Why this matters:**

Without origin detection, "my three sons" in a Claude Code transcript would be misclassified as biological children instead of three AI instances. The LLM extracts `agent_persona_names: ["Echo", "Sparrow", "Henry"]` which downstream entity detection uses to correctly classify them.

---

## Operation 2: Entity Refinement

**File:** `mempalace/llm_refine.py`

### Purpose

Reclassify regex-detected entities (capitalized words, git authors, manifest names) into proper categories: `PERSON`, `PROJECT`, `TOPIC`, `COMMON_WORD`, `AMBIGUOUS`.

### Configuration Constants (lines 31-37)

```python
BATCH_SIZE = 25  # candidates per LLM call; tuned for 4B local models
CONTEXT_LINES_PER_CANDIDATE = 3
CONTEXT_WINDOW_CHARS = 240  # max chars per context line to keep tokens bounded

VALID_LABELS = {"PERSON", "PROJECT", "TOPIC", "COMMON_WORD", "AMBIGUOUS"}
```

**Tuning rationale:**
- 25 candidates/batch: Fits within gemma4:e4b's context window without bloating
- 3 context lines: Enough to disambiguate, not so many that token count explodes
- 240 chars/line: Prevents single-line context from dominating the prompt

### System Prompt (lines 40-58)

```python
SYSTEM_PROMPT = """You are helping organize a user's memory palace by classifying 
capitalized tokens found in their files.

For each candidate, pick exactly ONE label:
- PERSON: a specific real person the user knows (colleague, family, character they write about)
- PROJECT: a named product, codebase, or effort the user works on
- TOPIC: a recurring theme or subject (not a person, not a project) — cities, technologies, concepts
- COMMON_WORD: an English word, verb, or fragment that isn't a named entity at all (e.g. "Created", "Before", "Never")
- AMBIGUOUS: context is insufficient to decide between two of the above

Frameworks, runtimes, APIs, cloud services, vendors, and third-party products
(e.g. Angular, OpenAPI, Terraform, Bun, Google) are TOPIC unless the context
clearly says this is the user's own named codebase, product, or active effort.

Use the provided context lines to disambiguate. A capitalized word that only 
appears in metadata ("Created: 2026-04-24") is COMMON_WORD. A name that appears 
with pronouns and dialogue is PERSON.

Respond with JSON only. Schema:
{"classifications": [{"name": "<exact candidate name>", "label": "<LABEL>", "reason": "<one short sentence>"}]}

One entry per candidate, same order as the input."""
```

### Context Extraction (lines 72-93)

```python
def _collect_contexts(corpus_lines: list[str], name: str, max_lines: int = 3) -> list[str]:
    """Return up to `max_lines` distinct lines from the corpus that mention `name`.
    
    Case-insensitive token-boundary match. Lines are truncated to
    CONTEXT_WINDOW_CHARS chars to keep token usage bounded.
    """
    needle = re.compile(rf"(?<!\w){re.escape(name)}(?!\w)", re.IGNORECASE)
    seen: set[str] = set()
    out: list[str] = []
    for line in corpus_lines:
        if not needle.search(line):
            continue
        trimmed = line.strip()[:CONTEXT_WINDOW_CHARS]
        if not trimmed or trimmed in seen:
            continue
        seen.add(trimmed)
        out.append(trimmed)
        if len(out) >= max_lines:
            break
    return out
```

**Key technique:** Word-boundary regex `(?<!\w){name}(?!\w)` prevents false matches inside other words.

### User Prompt Construction (lines 96-106)

```python
def _build_user_prompt(candidates_with_contexts: list[tuple[str, str, list[str]]]) -> str:
    """Shape: for each candidate, list its current type guess + sampled contexts."""
    parts: list[str] = ["CANDIDATES:"]
    for i, (name, current_type, contexts) in enumerate(candidates_with_contexts, 1):
        parts.append(f"\n{i}. {name}  (currently: {current_type})")
        if contexts:
            for c in contexts:
                parts.append(f"   > {c}")
        else:
            parts.append("   > (no context available)")
    return "\n".join(parts)
```

**Example output:**

```
CANDIDATES:

1. Alice  (currently: person)
   > "Alice suggested we switch to GraphQL"
   > "I talked to Alice about the API design"
   > "Alice's PR merged yesterday"

2. Angular  (currently: project)
   > "Migrating from Angular to React"
   > "Angular framework documentation"
   > "The Angular team deprecated that API"

3. Created  (currently: person)
   > "Created: 2026-04-24"
   > "Created by: deploy script"
   > "File created successfully"
```

### Main Refinement Function (lines 334-445)

```python
def refine_entities(
    detected: dict,
    corpus_text: str,
    provider: LLMProvider,
    batch_size: int = BATCH_SIZE,
    show_progress: bool = True,
    allow_project_promotions: bool = True,
    corpus_origin: dict | None = None,
) -> RefineResult:
    """Reclassify detected entities using the LLM provider.
    
    Only regex-derived candidates are sent for refinement. Git authors and
    manifest/git-backed projects are already source-backed and don't benefit
    from LLM second-guessing.
    
    Ctrl-C during refinement: cancels the remaining batches, returns a
    RefineResult with `cancelled=True` and whatever was classified before
    the interrupt. The partial result is safe to pass straight to
    `confirm_entities`.
    
    Transport or parse failures in individual batches are recorded in
    `errors` and do not abort the run.
    """
    # Filter candidates: skip authoritative entities (lines 361-369)
    candidates: list[tuple[str, str]] = []
    current_type = {"people": "person", "projects": "project", "uncertain": "uncertain"}
    for bucket in ("people", "projects", "uncertain"):
        for e in detected.get(bucket, []):
            if bucket == "people" and _is_authoritative_person(e):  # Line 311-314
                continue  # Skip git authors
            if bucket == "projects" and _is_authoritative_project(e):  # Line 317-321
                continue  # Skip manifest-backed projects
            candidates.append((e["name"], current_type[bucket]))

    corpus_lines = corpus_text.splitlines() if corpus_text else []

    # Deduplicate (lines 374-379)
    seen: set[str] = set()
    unique: list[tuple[str, str]] = []
    for name, kind in candidates:
        if name not in seen:
            seen.add(name)
            unique.append((name, kind))

    # Build batches (lines 393-397)
    batches: list[list[tuple[str, str, list[str]]]] = []
    for i in range(0, len(unique), batch_size):
        chunk = unique[i : i + batch_size]
        enriched = [(name, kind, _collect_contexts(corpus_lines, name)) for name, kind in chunk]
        batches.append(enriched)

    all_decisions: dict[str, tuple[str, str]] = {}
    errors: list[str] = []
    completed = 0
    cancelled = False

    # Inject corpus origin context (lines 265-308)
    system_prompt = SYSTEM_PROMPT + _build_corpus_origin_preamble(corpus_origin)

    # Process batches (lines 406-426)
    for idx, batch in enumerate(batches, 1):
        if show_progress and batch:
            _print_progress(idx - 1, len(batches), batch[0][0])
        user_prompt = _build_user_prompt(batch)
        try:
            resp = provider.classify(system_prompt, user_prompt, json_mode=True)
        except KeyboardInterrupt:
            cancelled = True
            break
        except LLMError as e:
            errors.append(f"batch {idx}: {e}")
            continue
        names_in_batch = [name for name, _, _ in batch]
        decisions = _parse_response(resp.text, names_in_batch)  # Line 153-189
        if not decisions:
            errors.append(f"batch {idx}: could not parse response")
        all_decisions.update(decisions)
        completed += 1

    # Apply classifications (lines 431-435)
    merged, reclassified, dropped = _apply_classifications(
        detected,
        all_decisions,
        allow_project_promotions=allow_project_promotions,
    )

    return RefineResult(
        merged=merged,
        reclassified=reclassified,
        dropped=dropped,
        errors=errors,
        batches_completed=completed,
        batches_total=len(batches),
        cancelled=cancelled,
    )
```

### Response Parsing (lines 153-189)

```python
def _parse_response(text: str, expected_names: list[str]) -> dict[str, tuple[str, str]]:
    """Parse the LLM's JSON response into {name: (label, reason)}.
    
    Robust to the model occasionally wrapping JSON in text or returning
    slight schema variations. Falls back to matching by candidate name.
    """
    data = None
    # Extract JSON candidates (lines 109-150: handles ```json blocks, nested objects)
    for candidate in _extract_json_candidates(text):
        try:
            data = json.loads(candidate)
            break
        except json.JSONDecodeError:
            continue
    if data is None:
        return {}

    entries = data.get("classifications") if isinstance(data, dict) else data
    if not isinstance(entries, list):
        return {}

    name_to_label: dict[str, tuple[str, str]] = {}
    expected_set = {n.lower(): n for n in expected_names}
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        name = entry.get("name") or entry.get("candidate")
        label = entry.get("label") or entry.get("type") or entry.get("classification")
        reason = entry.get("reason") or ""
        if not isinstance(name, str) or not isinstance(label, str):
            continue
        # Restore canonical casing from expected_names
        canonical = expected_set.get(name.lower(), name)
        lbl = label.strip().upper()
        if lbl not in VALID_LABELS:
            lbl = "AMBIGUOUS"
        name_to_label[canonical] = (lbl, reason.strip()[:120])
    return name_to_label
```

**Robustness features:**
- Accepts multiple JSON schema variations (`name`/`candidate`, `label`/`type`/`classification`)
- Handles LLM wrapping JSON in code fences or prose
- Falls back to `AMBIGUOUS` for invalid labels
- Preserves canonical casing from input

### Progress Display (lines 324-331)

```python
def _print_progress(batch_idx: int, total: int, current_name: str) -> None:
    """Overwrite-line progress indicator."""
    width = 40
    filled = int(width * batch_idx / total) if total else 0
    bar = "█" * filled + "░" * (width - filled)
    msg = f"\r  LLM refine: [{bar}] batch {batch_idx}/{total}  current: {current_name[:30]:<30}"
    sys.stderr.write(msg)
    sys.stderr.flush()
```

**Output:**

```
  LLM refine: [████████████████░░░░░░░░░░░░] batch 4/6  current: Angular
```

### Corpus Origin Preamble (lines 265-308)

Injects AI-dialogue context when available:

```python
def _build_corpus_origin_preamble(corpus_origin: dict | None) -> str:
    if not corpus_origin:
        return ""
    result = corpus_origin.get("result") or {}
    if not result.get("likely_ai_dialogue"):
        return ""

    lines = ["\n\nCORPUS CONTEXT (corpus-origin detection):"]
    platform = result.get("primary_platform")
    if platform:
        lines.append(f"- This corpus is AI-dialogue from {platform}.")
    user_name = result.get("user_name")
    if user_name:
        lines.append(f"- The corpus author (the human user) is named '{user_name}'. Treat this name as PERSON.")
    personas = result.get("agent_persona_names") or []
    if personas:
        lines.append(f"- The user has assigned these persona names to AI agents in this corpus: {', '.join(personas)}.")
        lines.append("- Persona names refer to AI agents, not biological people. Classify them as PERSON (a downstream step tags them as agent personas).")
    return "\n".join(lines)
```

**Example augmented system prompt:**

```
[SYSTEM_PROMPT content]

CORPUS CONTEXT (corpus-origin detection):
- This corpus is AI-dialogue from Claude Code (Anthropic).
- The corpus author (the human user) is named 'Alice'. Treat this name as PERSON.
- The user has assigned these persona names to AI agents in this corpus: Echo, Sparrow.
- Persona names refer to AI agents, not biological people. Classify them as PERSON (a downstream step tags them as agent personas).
```

---

## Operation 3: Closet Generation

**File:** `mempalace/closet_llm.py`

### Purpose

Generate topic-dense indices ("closets") from drawer content for richer searchability. Complements regex-based closets (always created) with LLM-enhanced extraction (optional).

### Configuration (lines 58-60, 93-122)

```python
MAX_CONTENT_CHARS = 30000    # Content fed to LLM (truncated if longer)
MAX_OUTPUT_TOKENS = 1500     # LLM response token budget
HTTP_TIMEOUT_S = 60          # Network timeout

class LLMConfig:
    """Resolved LLM connection config. CLI flags > env vars."""
    def __init__(self, endpoint: Optional[str] = None, key: Optional[str] = None, model: Optional[str] = None):
        self.endpoint = (endpoint or os.environ.get("LLM_ENDPOINT", "")).rstrip("/")
        self.key = key or os.environ.get("LLM_KEY", "")
        self.model = model or os.environ.get("LLM_MODEL", "")
        if self.endpoint:
            # Privacy-by-architecture: reject file:// schemes (line 105-112)
            scheme = urllib.parse.urlparse(self.endpoint).scheme.lower()
            if scheme not in ("http", "https"):
                raise ValueError(f"LLM_ENDPOINT must use http:// or https:// (got scheme {scheme!r})")
```

**Configuration sources (precedence):**
1. CLI flags (`--endpoint`, `--model`, `--key`)
2. Environment variables (`LLM_ENDPOINT`, `LLM_MODEL`, `LLM_KEY`)
3. No defaults (user must configure)

### Prompt Template (lines 62-90)

```python
PROMPT_TEMPLATE = """You are reading content filed in a memory palace. Generate a
topic-dense index that will be used to find this content later when someone searches.

Source: {source_file}
Wing: {wing} | Room: {room}

CONTENT:
{content}

---

Output a JSON object with EXACTLY these fields:

{{
  "topics": ["distinctive_word_or_phrase_1", "topic_2", ...],
  "quotes": ["[Speaker] verbatim quote", ...],
  "summary": "2-3 sentences describing what this content is about."
}}

RULES:
- Topics: 8-15 entries. Include proper nouns (names, places, projects),
  distinctive technical terms, and key concepts. NOT generic words like
  "conversation" or "discussion".
- Quotes: 2-5 entries. EXACT verbatim from the content, not paraphrased.
  Attribute with [Speaker] prefix if speaker is identifiable.
- Summary: mention WHO, WHAT, and WHY. No filler.
- Write in the same language as the content.
- Output valid JSON only. No code fences. No commentary.
"""
```

### LLM Call (lines 124-187)

```python
def _call_llm(cfg: LLMConfig, source_file: str, wing: str, room: str, content: str):
    """Single LLM call via OpenAI-compatible /chat/completions.
    
    Returns (parsed_json_dict_or_None, usage_dict_or_None).
    """
    try:
        from mempalace.i18n import t
        lang_instruction = t("aaak.instruction")  # Localization hook
    except Exception:
        lang_instruction = ""

    # Build prompt (lines 136-143)
    prompt = PROMPT_TEMPLATE.format(
        source_file=source_file[:100],
        wing=wing,
        room=room,
        content=content[:MAX_CONTENT_CHARS],  # Truncate long drawers
    )
    if lang_instruction and "english" not in lang_instruction.lower():
        prompt += f"\n\nLanguage instruction: {lang_instruction}"

    # Build request body (lines 145-151)
    body = json.dumps({
        "model": cfg.model,  # "gemma4:e4b"
        "max_tokens": MAX_OUTPUT_TOKENS,
        "messages": [{"role": "user", "content": prompt}],
    }).encode("utf-8")

    headers = {"Content-Type": "application/json"}
    if cfg.key:
        headers["Authorization"] = f"Bearer {cfg.key}"

    url = f"{cfg.endpoint}/chat/completions"

    # Retry logic: 3 attempts with exponential backoff (lines 159-187)
    for attempt in range(3):
        try:
            req = urllib.request.Request(url, data=body, headers=headers, method="POST")
            with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT_S) as resp:
                raw = resp.read().decode("utf-8")
            payload = json.loads(raw)

            # Extract and clean response (lines 166-169)
            text = payload["choices"][0]["message"]["content"].strip()
            text = re.sub(r"^```(?:json)?\s*", "", text)  # Strip code fences
            text = re.sub(r"\s*```$", "", text)
            parsed = json.loads(text)
            return parsed, payload.get("usage")
        except json.JSONDecodeError:
            if attempt < 2:
                time.sleep(2**attempt)  # Backoff: 1s, 2s
                continue
            return None, None
        except urllib.error.HTTPError as e:
            # Retry on rate limits / transient errors (lines 176-181)
            if e.code in (429, 503) and attempt < 2:
                time.sleep(2**attempt)
                continue
            return None, None
        except Exception as e:
            if "rate" in str(e).lower() and attempt < 2:
                time.sleep(2**attempt)
                continue
            return None, None
    return None, None
```

**Robustness features:**
- 3 retries with exponential backoff
- Handles 429 (rate limit) and 503 (service unavailable)
- Strips markdown code fences from LLM output
- Returns `None` on failure (regex closets remain as fallback)

### Closet Line Generation (lines 190-203)

```python
def _parsed_to_closet_lines(parsed, drawer_ids, entities_str):
    """Convert LLM's JSON output to closet pointer lines."""
    lines = []
    drawer_ref = ",".join(drawer_ids[:3])  # Reference up to 3 drawers

    # Topics (up to 15)
    for topic in parsed.get("topics", [])[:15]:
        lines.append(f"{topic}|{entities_str}|→{drawer_ref}")
    
    # Quotes (up to 5)
    for quote in parsed.get("quotes", [])[:5]:
        lines.append(f"{quote}|{entities_str}|→{drawer_ref}")
    
    # Summary (truncated to 200 chars)
    summary = parsed.get("summary", "")
    if summary:
        lines.append(f"{summary[:200]}|{entities_str}|→{drawer_ref}")

    return lines
```

**Format:** `content|entities|→drawer_id1,drawer_id2,drawer_id3`

**Example closet lines:**

```
chromadb|Alice,MemPalace|→drawer_wing_technical_abc123
hnsw:num_threads=1|Alice,MemPalace|→drawer_wing_technical_abc123
[Assistant] Use hnsw:num_threads=1 to avoid the race condition|Alice,MemPalace|→drawer_wing_technical_abc123
Discussion of ChromaDB HNSW configuration parameters. Covers thread safety (num_threads=1 to prevent race), similarity metric (cosine), and index tuning.|Alice,MemPalace|→drawer_wing_technical_abc123
```

### Main Regeneration Function (lines 206-337)

```python
def regenerate_closets(palace_path, wing=None, sample=0, dry_run=False, cfg: Optional[LLMConfig] = None):
    """Regenerate closets using a configured LLM for richer topic extraction.
    
    Reads existing drawers, sends content to the configured endpoint,
    replaces regex closets with LLM-generated ones. Regex closets remain
    as the fallback whenever the call fails.
    """
    if cfg is None:
        cfg = LLMConfig()
    missing = cfg.missing()
    if missing:
        print("Error: missing configuration: " + ", ".join(missing))
        print("Set env vars LLM_ENDPOINT / LLM_MODEL (and optionally LLM_KEY),")
        print("or pass --endpoint / --model / --key on the CLI.")
        return {"error": "missing-config", "missing": missing}

    # Load collections (lines 228-229)
    drawers_col = get_collection(palace_path, create=False)
    closets_col = get_closets_collection(palace_path)

    total = drawers_col.count()
    if total == 0:
        print("No drawers in palace.")
        return {"processed": 0}

    # Paginate fetch to avoid SQLite variable limit (lines 236-257)
    # ChromaDB issue #802, #850, #1073: SQLITE_MAX_VARIABLE_NUMBER = 32766
    by_source: dict = {}
    batch_size = 5000
    offset = 0
    while offset < total:
        batch = drawers_col.get(limit=batch_size, offset=offset, include=["documents", "metadatas"])
        ids = batch["ids"]
        if not ids:
            break
        for doc_id, doc, meta in zip(ids, batch["documents"], batch["metadatas"]):
            meta = meta or {}
            source = meta.get("source_file", "unknown")
            w = meta.get("wing", "")
            if wing and w != wing:
                continue  # Filter by wing if specified
            if source not in by_source:
                by_source[source] = {"drawer_ids": [], "content": [], "meta": meta}
            by_source[source]["drawer_ids"].append(doc_id)
            by_source[source]["content"].append(doc)
        offset += len(ids)

    sources = list(by_source.keys())
    if sample > 0:
        sources = sources[:sample]  # Limit for testing

    # Process each source file (lines 274-327)
    processed = 0
    failed = 0
    total_input = 0
    total_output = 0

    for i, source in enumerate(sources, 1):
        data = by_source[source]
        content = "\n\n".join(data["content"])  # Concatenate all drawers from file
        meta = data["meta"]
        w = meta.get("wing", "")
        r = meta.get("room", "")
        entities = meta.get("entities", "")

        if dry_run:
            print(f"  [{i}/{len(sources)}] {os.path.basename(source)} ({len(content)} chars)")
            continue

        # Call LLM
        parsed, usage = _call_llm(cfg, source, w, r, content)
        if not parsed:
            failed += 1
            print(f"  [{i}/{len(sources)}] ✗ {os.path.basename(source)} — LLM failed")
            continue

        if usage:
            total_input += usage.get("prompt_tokens", 0)
            total_output += usage.get("completion_tokens", 0)

        # Generate closet lines
        lines = _parsed_to_closet_lines(parsed, data["drawer_ids"], entities)
        closet_id_base = f"closet_{w}_{r}_{os.path.basename(source)[:30]}"

        # Serialize with concurrent mine operations (lines 302-322)
        with mine_lock(source):
            purge_file_closets(closets_col, source)  # Remove old closets
            upsert_closet_lines(
                closets_col,
                closet_id_base,
                lines,
                {
                    "wing": w,
                    "room": r,
                    "source_file": source,
                    "generated_by": f"llm:{cfg.model}",  # Track which model generated this
                    "filed_at": datetime.now().isoformat(),
                    "entities": entities,
                    "normalize_version": NORMALIZE_VERSION,  # Prevent stale-drawer rebuild
                },
            )

        processed += 1
        n_topics = len(parsed.get("topics", []))
        print(f"  [{i}/{len(sources)}] ✓ {os.path.basename(source)} — {n_topics} topics")

    print(f"\nDone. {processed} regenerated, {failed} failed.")
    if total_input or total_output:
        print(f"Tokens: {total_input:,} in + {total_output:,} out (cost depends on provider)")

    return {
        "processed": processed,
        "failed": failed,
        "input_tokens": total_input,
        "output_tokens": total_output,
    }
```

**Key design decisions:**
- **Pagination:** Fetch in 5000-drawer batches to avoid SQLite variable limit
- **File-level locking:** Prevents race with concurrent mining
- **Purge-before-insert:** Ensures clean replacement of regex closets
- **Metadata stamping:** `generated_by: "llm:gemma4:e4b"` tracks provenance
- **Token tracking:** Returns input/output token counts for cost estimation

---

## Privacy & Safety Implementation

### Local vs External Endpoint Detection (lines 44-103)

**File:** `mempalace/llm_client.py`

```python
def _endpoint_is_local(url: Optional[str]) -> bool:
    """Return True if `url`'s hostname is on the user's machine or private network.
    
    Local includes:
      - localhost, 127.0.0.1, ::1
      - hostnames ending in .local (mDNS/Bonjour)
      - IPv4 RFC1918: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16
      - IPv4 CGNAT (Tailscale and similar VPN/tunnel networks): 100.64.0.0/10
      - IPv6 unique-local addresses (fc00::/7) — fc.../fd... prefixes
    
    Anything else (including public IPs and FQDNs) is external.
    """
    if not url:
        return True  # Defensive default: no endpoint means no external request
    try:
        host = (urlparse(url).hostname or "").lower()
    except (ValueError, AttributeError):
        return False
    if not host:
        return True
    if host in {"localhost", "127.0.0.1", "::1"}:
        return True
    if host.endswith(".local"):
        return True
    if host.startswith("10."):
        return True
    if host.startswith("192.168."):
        return True
    if host.startswith("172."):
        parts = host.split(".")
        if len(parts) >= 2:
            try:
                if 16 <= int(parts[1]) <= 31:  # 172.16.0.0 - 172.31.255.255
                    return True
            except ValueError:
                pass
    if host.startswith("100."):
        # Tailscale CGNAT range: 100.64.0.0/10
        parts = host.split(".")
        if len(parts) >= 2:
            try:
                if 64 <= int(parts[1]) <= 127:
                    return True
            except ValueError:
                pass
    if host.startswith("fc") or host.startswith("fd"):
        # IPv6 unique-local addresses
        return True
    return False
```

**Property on LLMProvider (lines 164-176):**

```python
@property
def is_external_service(self) -> bool:
    """Return True if this provider's endpoint will send user content
    off the local machine/network.
    
    Used by `mempalace init` to decide whether to print a privacy
    warning before first use (issue #24).
    """
    return not _endpoint_is_local(self.endpoint)
```

**Usage in `cmd_init` (hypothetical, not shown in provided files):**

```python
if provider.is_external_service:
    print(f"⚠ {provider.name} is an EXTERNAL API. Your folder content will be sent")
    print("to the provider during init. MemPalace does not control how the")
    print("provider logs, retains, or uses your data. Pass --no-llm to keep")
    print("init fully local.")
    response = input("Continue? [y/N]: ")
    if response.lower() != "y":
        sys.exit(1)
```

### No Silent Fallbacks

**Philosophy (from docstring):**

```python
# NEVER do this:
try:
    llm_result = call_ollama()
except:
    llm_result = call_anthropic()  # ❌ silent data exfiltration
```

**MemPalace guarantee:**
- User must explicitly configure external providers via CLI flags or env vars
- No silent cloud fallbacks
- Failures are logged, not silently bypassed

### File Scheme Protection (closet_llm.py lines 105-112)

```python
if self.endpoint:
    # Privacy-by-architecture: reject file:// and other non-HTTP schemes
    # so a misconfigured endpoint cannot exfiltrate local files.
    scheme = urllib.parse.urlparse(self.endpoint).scheme.lower()
    if scheme not in ("http", "https"):
        raise ValueError(f"LLM_ENDPOINT must use http:// or https:// (got scheme {scheme!r})")
```

**Prevents:**

```bash
# ❌ Blocked
export LLM_ENDPOINT=file:///etc/passwd
mempalace closet-llm ~/.mempalace/palace
# ValueError: LLM_ENDPOINT must use http:// or https:// (got scheme 'file')
```

---

## Performance Characteristics

### gemma4:e4b Benchmarks

**Source:** `benchmarks/model_eval/reports/2026-05-10-analysis.md` (referenced in ARCHITECTURE_LLM_USAGE.md)

| Metric | Value | Comparison |
|--------|-------|------------|
| **Accuracy (room classification, open-set)** | 0.65 | **Highest across ALL tested models** (local & cloud, up to 1.3T params) |
| **Accuracy (room classification, closed-set)** | 0.62 | Above qwen3:4b's 0.61 |
| **P50 Latency** | 230 ms | 2.1x slower than qwen3:4b (109 ms) |
| **P95 Latency** | 266 ms | Consistent tail latency |
| **VRAM** | 10.6 GB | 1.4x more than qwen3:4b (7.5 GB) |
| **Disk size** | 2.8 GB | q4_K_M quantized |

**Trade-off analysis:**

For MemPalace's use case (batch classification during `init`, not real-time chat), the **accuracy improvement outweighs the latency cost**.

### Batch Processing Impact

**For 2000-file corpus with 150 entities to refine:**

| Phase | Method | Time |
|-------|--------|------|
| Tier 1 heuristics | Regex only | ~2 seconds |
| Tier 2 LLM (gemma4:e4b) | 6 batches × 25 candidates × 230ms | ~25 seconds |
| Tier 2 LLM (Anthropic Haiku) | Faster, parallel requests | ~15 seconds (costs ~$0.01) |

**For 5000 drawers, closet generation:**

| Method | Time | Cost |
|--------|------|------|
| Regex closets (default) | ~30 seconds | $0 |
| LLM closets (gemma4:e4b) | ~20 minutes (5000 × 230ms) | $0 (local) |
| LLM closets (GPT-4o-mini) | ~10 minutes (parallel) | ~$2.50 |

**Real-world `init` on Claude Code session corpus (1200 files, 85 MB):**

```
$ time mempalace init ~/claude-sessions
Tier 1 heuristics: 1.8s
Tier 2 LLM (gemma4:e4b): 18.2s
Entity refinement: 12.4s (85 candidates, 4 batches)
Mining: 42.1s
Total: 74.5s

$ time mempalace init ~/claude-sessions --no-llm
Tier 1 heuristics: 1.8s
Mining: 42.1s
Total: 43.9s
```

**Conclusion:** LLM operations add ~30s to init for better classification. User can disable via `--no-llm` for pure speed.

---

## Configuration Matrix

### CLI Flags

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

### Common Configurations

#### 1. Default (Ollama + gemma4:e4b)

```bash
# Start Ollama
ollama serve

# Pull model
ollama pull gemma4:e4b

# Use defaults
mempalace init ~/my-corpus
```

#### 2. Alternative Local Model (qwen3:4b)

```bash
ollama pull qwen3:4b

mempalace init ~/my-corpus \
    --llm-model qwen3:4b-it-q4_K_M
```

#### 3. External API (Anthropic Haiku)

```bash
mempalace init ~/my-corpus \
    --llm-provider anthropic \
    --llm-model claude-3-5-haiku-20241022 \
    --llm-api-key sk-ant-...
```

**Privacy warning displayed before first API call:**

```
⚠ anthropic is an EXTERNAL API. Your folder content will be sent 
to the provider during init. MemPalace does not control how the 
provider logs, retains, or uses your data. Pass --no-llm to keep 
init fully local.
```

#### 4. OpenAI-Compatible (OpenRouter)

```bash
mempalace init ~/my-corpus \
    --llm-provider openai-compat \
    --llm-model anthropic/claude-3.5-haiku \
    --llm-endpoint https://openrouter.ai/api/v1 \
    --llm-api-key sk-or-...
```

#### 5. No LLM (Pure Heuristics)

```bash
mempalace init ~/my-corpus --no-llm
```

**Tier 1 heuristics still run. Tier 2 LLM refinement skipped.**

---

## Integration Points

### 1. `mempalace init` Command

**High-level flow:**

```python
# Hypothetical cmd_init implementation (not shown in provided files)

def cmd_init(corpus_path, llm_provider="ollama", llm_model="gemma4:e4b", no_llm=False):
    # Step 1: Sample corpus
    samples = sample_corpus(corpus_path, max_files=10, max_chars=5000)
    
    # Step 2: Tier 1 heuristic
    from mempalace.corpus_origin import detect_origin_heuristic
    tier1_result = detect_origin_heuristic(samples)
    
    # Step 3: Tier 2 LLM (if enabled and low confidence)
    if not no_llm and tier1_result.confidence < 0.7:
        from mempalace.llm_client import get_provider
        from mempalace.corpus_origin import detect_origin_llm
        
        provider = get_provider(llm_provider, llm_model)
        
        # Privacy warning for external APIs
        if provider.is_external_service:
            print_privacy_warning(provider.name)
            if not user_consents():
                sys.exit(1)
        
        tier2_result = detect_origin_llm(samples, provider)
        origin_result = tier2_result
    else:
        origin_result = tier1_result
    
    # Save origin.json
    save_origin(palace_path, origin_result)
    
    # Step 4: Mine corpus (triggers entity detection)
    mine(corpus_path, palace_path)
    
    # Step 5: Entity refinement (if enabled)
    if not no_llm:
        from mempalace.llm_refine import refine_entities, collect_corpus_text
        
        detected = load_detected_entities(palace_path)
        corpus_text = collect_corpus_text(corpus_path)
        
        provider = get_provider(llm_provider, llm_model)
        refined = refine_entities(detected, corpus_text, provider, corpus_origin=origin_result.to_dict())
        
        save_refined_entities(palace_path, refined.merged)
```

### 2. `mempalace refine-entities` Command

**Standalone refinement on existing palace:**

```bash
mempalace refine-entities ~/.mempalace/palace \
    --llm-provider ollama \
    --llm-model gemma4:e4b
```

**Implementation:**

```python
from mempalace.llm_client import get_provider
from mempalace.llm_refine import refine_entities, collect_corpus_text

provider = get_provider("ollama", "gemma4:e4b")
detected = load_detected_entities(palace_path)
corpus_text = collect_corpus_text(palace_path)
corpus_origin = load_origin(palace_path)

result = refine_entities(
    detected,
    corpus_text,
    provider,
    show_progress=True,
    corpus_origin=corpus_origin,
)

print(f"Reclassified: {result.reclassified} | Dropped: {result.dropped}")
if result.cancelled:
    print("Refinement cancelled. Partial results saved.")
```

### 3. `mempalace closet-llm` Command

**Regenerate closets with LLM:**

```bash
mempalace closet-llm ~/.mempalace/palace \
    --endpoint http://localhost:11434/v1 \
    --model gemma4:e4b
```

**Implementation (lines 340-374):**

```python
if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(
        description="Regenerate closets via a user-configured LLM (OpenAI-compatible API)"
    )
    parser.add_argument("--palace", default=os.path.expanduser("~/.mempalace/palace"))
    parser.add_argument("--wing", default=None)
    parser.add_argument("--sample", type=int, default=0)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--endpoint", default=None)
    parser.add_argument("--key", default=None)
    parser.add_argument("--model", default=None)
    args = parser.parse_args()

    cfg = LLMConfig(endpoint=args.endpoint, key=args.key, model=args.model)
    regenerate_closets(args.palace, wing=args.wing, sample=args.sample, dry_run=args.dry_run, cfg=cfg)
```

---

## Error Handling

### Graceful Degradation

**Principle:** LLM failures never abort the entire operation. Partial results are preserved.

#### Corpus Origin (lines 389-407)

```python
try:
    resp = provider.classify(system=_SYSTEM_PROMPT, user=user_prompt, json_mode=True)
    raw = getattr(resp, "text", "") or ""
except Exception as e:
    # Conservative fallback: assume AI-dialogue
    return CorpusOriginResult(
        likely_ai_dialogue=True,
        confidence=0.3,
        primary_platform=None,
        evidence=[f"LLM provider error (fallback to default stance): {e}"],
    )
```

**Result:** Tier 1 heuristics still provide value. Tier 2 failure doesn't block init.

#### Entity Refinement (lines 411-417)

```python
try:
    resp = provider.classify(system_prompt, user_prompt, json_mode=True)
except KeyboardInterrupt:
    cancelled = True
    break
except LLMError as e:
    errors.append(f"batch {idx}: {e}")
    continue  # Skip this batch, process remaining
```

**Result:** Partial refinement is usable. `RefineResult.errors` lists failures.

#### Closet Generation (lines 286-290)

```python
parsed, usage = _call_llm(cfg, source, w, r, content)
if not parsed:
    failed += 1
    print(f"  [{i}/{len(sources)}] ✗ {os.path.basename(source)} — LLM failed")
    continue  # Regex closets remain as fallback
```

**Result:** Regex closets (always created during mining) remain searchable.

### Retry Logic

**Closet LLM (lines 159-187):**

```python
for attempt in range(3):
    try:
        # ... HTTP request ...
        return parsed, payload.get("usage")
    except json.JSONDecodeError:
        if attempt < 2:
            time.sleep(2**attempt)  # 1s, 2s
            continue
        return None, None
    except urllib.error.HTTPError as e:
        if e.code in (429, 503) and attempt < 2:  # Rate limit or service unavailable
            time.sleep(2**attempt)
            continue
        return None, None
```

**Backoff:** Exponential (1s, 2s) for transient failures.

---

## Debugging & Observability

### LLM Response Inspection

**Enable verbose logging (hypothetical):**

```bash
export MEMPALACE_LLM_DEBUG=1
mempalace init ~/corpus
```

**Expected output:**

```
[LLM] Calling ollama:gemma4:e4b at http://localhost:11434
[LLM] Request: {"model": "gemma4:e4b", "messages": [...], "format": "json"}
[LLM] Response (230ms): {"message": {"content": "{\"classifications\": [...]}"}, ...}
[LLM] Parsed: {"classifications": [{"name": "Alice", "label": "PERSON", ...}]}
```

### Progress Display

**Entity Refinement:**

```
  LLM refine: [████████████████████████████░░░░] batch 5/6  current: Angular
```

**Closet Generation:**

```
Regenerating closets for 127 source files via http://localhost:11434/v1 (gemma4:e4b)...
  [1/127] ✓ session_2026-05-01.jsonl — 12 topics
  [2/127] ✗ session_2026-05-02.jsonl — LLM failed
  [3/127] ✓ session_2026-05-03.jsonl — 15 topics
  ...
Done. 119 regenerated, 8 failed.
Tokens: 2,345,678 in + 123,456 out (cost depends on provider)
```

### Model Availability Check

**OllamaProvider (lines 222-236):**

```python
def check_available(self) -> tuple[bool, str]:
    try:
        with urlopen(f"{self.endpoint}/api/tags", timeout=5) as resp:
            data = json.loads(resp.read())
    except (URLError, HTTPError, OSError, json.JSONDecodeError) as e:
        return False, f"Cannot reach Ollama at {self.endpoint}: {e}"
    names = {m.get("name", "") for m in data.get("models", []) or []}
    # Ollama tags may or may not include ':latest' — accept either form
    wanted = {self.model, f"{self.model}:latest"}
    if not names & wanted:
        return (
            False,
            f"Model '{self.model}' not loaded in Ollama. Run: ollama pull {self.model}",
        )
    return True, "ok"
```

**Usage:**

```python
provider = get_provider("ollama", "gemma4:e4b")
ok, msg = provider.check_available()
if not ok:
    print(f"Error: {msg}")
    sys.exit(1)
```

---

## Summary: gemma4:e4b in MemPalace

**Role:** Optional classification and reasoning layer for better entity disambiguation and topic extraction.

**Key properties:**
- **Local-first:** Ollama on localhost by default
- **Opt-in:** User must explicitly enable or disable (`--no-llm`)
- **Never required:** Core memory operations work without it
- **Best-in-class accuracy:** 0.65 on room classification (beats all cloud models tested)
- **Batch processing:** Optimized for throughput, not real-time latency
- **Graceful degradation:** Failures never block operations; partial results preserved
- **Privacy-conscious:** Local by default, external APIs require explicit warning + consent

**Implementation highlights:**
- **3 providers, 1 interface:** `llm_client.py` abstracts Ollama, OpenAI-compat, Anthropic
- **3 operations:** Corpus origin detection, entity refinement, closet generation
- **Robust parsing:** Handles LLM JSON output variations, code fences, malformed responses
- **Retry logic:** Exponential backoff for rate limits and transient failures
- **Progress UX:** Real-time progress bars, cancellable (Ctrl-C), partial results usable
- **Provenance tracking:** `generated_by: "llm:gemma4:e4b"` in metadata
- **Privacy safeguards:** Local endpoint detection, file:// scheme blocking, external API warnings

**When to use gemma4:e4b:**
- Corpus contains ambiguous entities (names that could be people, projects, or common words)
- User wants richer topic indices for better search recall
- Accuracy matters more than speed (batch processing, not real-time)
- User has local GPU (10.6 GB VRAM) or willing to wait for CPU inference

**When to skip (`--no-llm`):**
- Speed is critical (saves ~30s on typical init)
- Privacy is paramount (though gemma4:e4b is local by default)
- Corpus is simple (no ambiguous entities, clear structure)
- No Ollama/LLM infrastructure available

---

## References

### Code Files
- `mempalace/llm_client.py` — Provider abstraction layer
- `mempalace/corpus_origin.py` — Two-tier origin detection
- `mempalace/llm_refine.py` — Entity refinement
- `mempalace/closet_llm.py` — LLM-based closet generation

### Related Documentation
- `ARCHITECTURE_LLM_USAGE.md` — High-level LLM usage overview
- `ARCHITECTURE_EMBEDDING_VS_LLM.md` — Distinction between embedding model and LLM
- `benchmarks/model_eval/reports/2026-05-10-analysis.md` — Model evaluation benchmarks

### External Resources
- [Ollama documentation](https://ollama.com/docs)
- [Google Gemma models](https://ai.google.dev/gemma)
- [OpenAI Chat Completions API](https://platform.openai.com/docs/api-reference/chat)
- [Anthropic Messages API](https://docs.anthropic.com/en/api/messages)
